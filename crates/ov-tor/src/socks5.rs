//! A minimal, self-contained SOCKS5 (RFC 1928 / RFC 1929) front end for the
//! embedded Arti client.
//!
//! Chromium's network stack is pointed at this listener with
//! `--proxy-server=socks5://127.0.0.1:<port>`; every browser connection is
//! therefore tunnelled through Arti and never leaves the device in the clear.
//!
//! ### `.onion` Rendezvous Coalescing & Warm-Up Pool
//! Chromium's `SOCKS5ClientSocket` enforces a hardcoded 30-second timeout
//! waiting for the 10-byte `REP_SUCCESS` handshake reply, while a cold 6-hop
//! Tor v3 `.onion` circuit (HsDir descriptor fetch + introduction + rendezvous)
//! can legitimately take 31–75s on mobile networks.  [`OnionRendezvousPool`]
//! runs every `.onion` `connect_with_prefs` inside an independent background
//! task (`tokio::spawn`) that survives client TCP disconnects up to
//! `connect_timeout` (`120s`) and stashes pre-connected [`DataStream`]s for up
//! to [`ONION_STASH_TTL`] (`15s`) keyed by `(isolation_key, host, port)`.  When
//! Chromium or `TabSessionManager` immediately retries `CONNECT host:port`, it
//! either adopts the still-building rendezvous dial or claims the ready
//! [`DataStream`] in `< 5ms`.

use std::{
    collections::{HashMap, VecDeque},
    future::Future,
    net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    time::Duration,
};

use arti_client::{DataStream, StreamPrefs};
use thiserror::Error;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    sync::{watch, Notify},
    task::AbortHandle,
    time::Instant,
};

use crate::{
    clearnet::{
        is_chromium_background_endpoint, now_epoch_ms, ClearnetAllowlist, PendingRegistration,
        DEFAULT_CONSENT_WAIT_TIMEOUT,
    },
    isolation::{apex_domain, is_valid_v3_onion_host, resolve_isolation_key, IsolationRegistry},
};

/// SOCKS5 protocol version.
pub const VERSION: u8 = 0x05;
/// RFC 1929 user/password authentication version.
const USERPASS_VERSION: u8 = 0x01;

/// "No authentication required" method.
const METHOD_NONE: u8 = 0x00;
/// "User/password authentication" method.
const METHOD_USERPASS: u8 = 0x02;
/// "No acceptable methods" answer.
const METHOD_UNACCEPTABLE: u8 = 0xff;

/// CONNECT command.
pub const CMD_CONNECT: u8 = 0x01;
/// IPv4 address type.
pub const ATYP_IPV4: u8 = 0x01;
/// Domain name address type.
pub const ATYP_DOMAIN: u8 = 0x03;
/// IPv6 address type.
pub const ATYP_IPV6: u8 = 0x04;

/// Reply codes.
pub const REP_SUCCESS: u8 = 0x00;
pub const REP_GENERAL_FAILURE: u8 = 0x01;
pub const REP_NOT_ALLOWED: u8 = 0x02;
pub const REP_HOST_UNREACHABLE: u8 = 0x04;
pub const REP_COMMAND_NOT_SUPPORTED: u8 = 0x07;
pub const REP_ADDRESS_TYPE_NOT_SUPPORTED: u8 = 0x08;

/// Maximum length of a SOCKS5 domain name.
const MAX_DOMAIN_LEN: u8 = 255;

/// Time-to-live for an unclaimed pre-connected `.onion` stream in [`OnionRendezvousPool`].
pub const ONION_STASH_TTL: Duration = Duration::from_secs(15);

/// Errors that can occur while a SOCKS5 message is parsed.
#[derive(Clone, Debug, Error, PartialEq, Eq)]
pub enum Socks5Error {
    /// The client used a SOCKS version other than 5.
    #[error("unsupported SOCKS version {0}")]
    UnsupportedVersion(u8),
    /// The client used an address type that RFC 1928 does not define.
    #[error("unsupported address type {0}")]
    UnsupportedAddressType(u8),
    /// The length of the domain name is zero or larger than 255.
    #[error("invalid domain length {0}")]
    InvalidDomainLength(u8),
    /// The message ends before the request is complete.
    #[error("the SOCKS5 request is truncated")]
    Truncated,
}

/// A decoded SOCKS5 request.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Socks5Request {
    /// Command byte (only `CONNECT` is supported).
    pub command: u8,
    /// Destination host, either a domain name or a textual IP address.
    pub host: String,
    /// Destination port.
    pub port: u16,
}

impl Socks5Request {
    /// `true` when the destination is a syntactically valid Tor v3 `.onion` host.
    pub fn is_onion(&self) -> bool {
        is_valid_v3_onion_host(&self.host)
    }

    /// `true` when the destination ends in `.onion` or `.onion.` (valid or malformed).
    pub fn has_onion_tld(&self) -> bool {
        let lower = self.host.trim().trim_end_matches('.').to_ascii_lowercase();
        lower == "onion" || lower.ends_with(".onion")
    }
}

/// What the front end does with a decoded request.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Socks5Decision {
    /// The request is allowed: the connection is placed on a circuit.
    Proceed,
    /// The request is rejected; the given reply code is sent to the client.
    Reject(u8),
}

/// Parse a SOCKS5 request message (RFC 1928, section 4).
pub fn parse_request(bytes: &[u8]) -> Result<Socks5Request, Socks5Error> {
    if bytes.len() < 4 {
        return Err(Socks5Error::Truncated);
    }
    let version = bytes[0];
    if version != VERSION {
        return Err(Socks5Error::UnsupportedVersion(version));
    }
    let command = bytes[1];
    let atyp = bytes[3];

    let (host, port_offset) = match atyp {
        ATYP_IPV4 => {
            if bytes.len() < 8 {
                return Err(Socks5Error::Truncated);
            }
            let octets = [bytes[4], bytes[5], bytes[6], bytes[7]];
            (Ipv4Addr::from(octets).to_string(), 8)
        }
        ATYP_IPV6 => {
            if bytes.len() < 20 {
                return Err(Socks5Error::Truncated);
            }
            let mut octets = [0u8; 16];
            octets.copy_from_slice(&bytes[4..20]);
            (Ipv6Addr::from(octets).to_string(), 20)
        }
        ATYP_DOMAIN => {
            if bytes.len() < 5 {
                return Err(Socks5Error::Truncated);
            }
            let len = bytes[4];
            if len == 0 {
                return Err(Socks5Error::InvalidDomainLength(len));
            }
            let end = 5 + len as usize;
            if bytes.len() < end {
                return Err(Socks5Error::Truncated);
            }
            (String::from_utf8_lossy(&bytes[5..end]).into_owned(), end)
        }
        other => return Err(Socks5Error::UnsupportedAddressType(other)),
    };

    if bytes.len() < port_offset + 2 {
        return Err(Socks5Error::Truncated);
    }
    let port = u16::from_be_bytes([bytes[port_offset], bytes[port_offset + 1]]);

    Ok(Socks5Request {
        command,
        host,
        port,
    })
}

/// The isolation key of a request.
pub fn isolation_key(host: &str, username: &str) -> String {
    if username.is_empty() {
        apex_domain(host).unwrap_or_default()
    } else {
        username.to_string()
    }
}

/// Apply the traffic policy to a decoded request.
///
/// - Only `CONNECT` on non-zero ports is permitted.
/// - Loopback, link-local, unspecified, and RFC 1918 private destinations are
///   refused unconditionally even when `allow_clearnet` is `true`.
/// - Malformed `.onion` names are refused unconditionally (RFC 7686) so they
///   can never leak to Tor exit DNS.
/// - In hidden-service-only mode (`!allow_clearnet`), every non-`.onion`
///   destination is refused with `REP_NOT_ALLOWED`.
pub fn decide(request: &Socks5Request, allow_clearnet: bool) -> Socks5Decision {
    if request.command != CMD_CONNECT {
        return Socks5Decision::Reject(REP_COMMAND_NOT_SUPPORTED);
    }
    if request.port == 0 {
        return Socks5Decision::Reject(REP_NOT_ALLOWED);
    }
    if is_forbidden_local_host(&request.host) {
        return Socks5Decision::Reject(REP_NOT_ALLOWED);
    }
    if request.has_onion_tld() && !request.is_onion() {
        return if allow_clearnet {
            Socks5Decision::Reject(REP_HOST_UNREACHABLE)
        } else {
            Socks5Decision::Reject(REP_NOT_ALLOWED)
        };
    }
    if !request.is_onion() && !allow_clearnet {
        return Socks5Decision::Reject(REP_NOT_ALLOWED);
    }
    Socks5Decision::Proceed
}

fn is_forbidden_local_host(host: &str) -> bool {
    let trimmed = host
        .trim()
        .trim_start_matches('[')
        .trim_end_matches(']')
        .trim_end_matches('.');
    if trimmed.is_empty() {
        return true;
    }

    let lower = trimmed.to_ascii_lowercase();
    if lower == "localhost" || lower.ends_with(".localhost") || lower.ends_with(".local") {
        return true;
    }

    if let Ok(ip) = trimmed.parse::<IpAddr>() {
        return match ip {
            IpAddr::V4(v4) => is_forbidden_ipv4(v4),
            IpAddr::V6(v6) => {
                if let Some(mapped) = v6.to_ipv4_mapped() {
                    return is_forbidden_ipv4(mapped);
                }
                v6.is_loopback()
                    || v6.is_unspecified()
                    || v6.is_multicast()
                    || (v6.segments()[0] & 0xfe00) == 0xfc00
                    || (v6.segments()[0] & 0xffc0) == 0xfe80
            }
        };
    }

    false
}

fn is_forbidden_ipv4(v4: Ipv4Addr) -> bool {
    let octets = v4.octets();
    let is_cgnat = octets[0] == 100 && (octets[1] & 0b1100_0000) == 64;
    v4.is_loopback()
        || v4.is_private()
        || v4.is_link_local()
        || v4.is_broadcast()
        || v4.is_unspecified()
        || v4.is_multicast()
        || is_cgnat
}

/// Build the reply message for `code`.
pub fn reply_frame(code: u8) -> Vec<u8> {
    let mut reply = vec![VERSION, code, 0x00, ATYP_IPV4];
    reply.extend_from_slice(&[0, 0, 0, 0]);
    reply.extend_from_slice(&0u16.to_be_bytes());
    reply
}

/// Key identifying an isolated `.onion` stream endpoint in [`OnionRendezvousPool`].
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub(crate) struct OnionStreamKey {
    pub isolation_key: String,
    pub host: String,
    pub port: u16,
}

impl OnionStreamKey {
    pub(crate) fn new(isolation_key: &str, host: &str, port: u16) -> Self {
        Self {
            isolation_key: isolation_key.to_string(),
            host: host.trim().trim_end_matches('.').to_ascii_lowercase(),
            port,
        }
    }
}

enum SlotState<S> {
    ClaimedInFlight,
    OrphanedInFlight,
    Ready { stream: S, ready_at: Instant },
    Failed(u8),
    Done,
}

pub(crate) struct OnionDialSlot<S> {
    state: Mutex<SlotState<S>>,
    notify: Notify,
    abort_handle: Mutex<Option<AbortHandle>>,
}

impl<S> OnionDialSlot<S> {
    fn new_claimed() -> Self {
        Self {
            state: Mutex::new(SlotState::ClaimedInFlight),
            notify: Notify::new(),
            abort_handle: Mutex::new(None),
        }
    }

    fn new_ready(stream: S) -> Self {
        Self {
            state: Mutex::new(SlotState::Ready {
                stream,
                ready_at: Instant::now(),
            }),
            notify: Notify::new(),
            abort_handle: Mutex::new(None),
        }
    }

    fn lock_state(&self) -> std::sync::MutexGuard<'_, SlotState<S>> {
        match self.state.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        }
    }

    fn cancel_and_abort(&self) {
        *self.lock_state() = SlotState::Done;
        self.notify.notify_waiters();
        let handle = match self.abort_handle.lock() {
            Ok(mut g) => g.take(),
            Err(poisoned) => poisoned.into_inner().take(),
        };
        if let Some(h) = handle {
            h.abort();
        }
    }
}

pub(crate) enum AcquireOutcome<S> {
    ImmediateReady { stream: S, age: Duration },
    AdoptedInFlight(Arc<OnionDialSlot<S>>),
    SpawnedNew(Arc<OnionDialSlot<S>>),
}

pub(crate) enum WaitSlotOutcome<S> {
    Connected(S),
    Failed(u8),
    ClientDisconnected,
    Cancelled,
}

/// Coalesces in-flight `.onion` rendezvous dials across Chromium's 30s SOCKS5
/// socket timeout and holds unclaimed ready streams for up to `stash_ttl`.
pub(crate) struct OnionRendezvousPool<S = DataStream> {
    slots: Mutex<HashMap<OnionStreamKey, VecDeque<Arc<OnionDialSlot<S>>>>>,
    stash_ttl: Duration,
}

impl<S: Send + 'static> OnionRendezvousPool<S> {
    pub(crate) fn new(stash_ttl: Duration) -> Self {
        Self {
            slots: Mutex::new(HashMap::new()),
            stash_ttl,
        }
    }

    fn lock_slots(
        &self,
    ) -> std::sync::MutexGuard<'_, HashMap<OnionStreamKey, VecDeque<Arc<OnionDialSlot<S>>>>> {
        match self.slots.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        }
    }

    fn prune_queue(queue: &mut VecDeque<Arc<OnionDialSlot<S>>>, ttl: Duration) {
        queue.retain(|slot| {
            let mut state = slot.lock_state();
            match &*state {
                SlotState::Done => false,
                SlotState::Ready { ready_at, .. } if ready_at.elapsed() > ttl => {
                    *state = SlotState::Done;
                    false
                }
                _ => true,
            }
        });
    }

    fn spawn_ttl_reaper(slot: Arc<OnionDialSlot<S>>, ttl: Duration) {
        tokio::spawn(async move {
            tokio::time::sleep(ttl).await;
            let mut state = slot.lock_state();
            if let SlotState::Ready { ready_at, .. } = &*state {
                if ready_at.elapsed() >= ttl {
                    *state = SlotState::Done;
                    slot.notify.notify_waiters();
                }
            }
        });
    }

    /// Take a ready stashed stream, adopt an orphaned in-flight dial, or spawn
    /// a new background dial task that survives client TCP disconnects.
    pub(crate) fn acquire_or_spawn<F>(
        self: &Arc<Self>,
        key: OnionStreamKey,
        mut pool_shutdown: watch::Receiver<bool>,
        dial_fn: F,
    ) -> AcquireOutcome<S>
    where
        F: Future<Output = Result<S, u8>> + Send + 'static,
    {
        let mut map = self.lock_slots();
        let ttl = self.stash_ttl;

        if map.len() > 16 {
            map.retain(|_, q| {
                Self::prune_queue(q, ttl);
                !q.is_empty()
            });
        }

        if let Some(queue) = map.get_mut(&key) {
            Self::prune_queue(queue, ttl);

            // 1. Prefer an already-connected virgin stream in the 15s warm stash.
            for idx in 0..queue.len() {
                let maybe_ready = {
                    let mut state = queue[idx].lock_state();
                    if let SlotState::Ready { ready_at, .. } = &*state {
                        let age = ready_at.elapsed();
                        if age <= ttl {
                            let prev = std::mem::replace(&mut *state, SlotState::Done);
                            if let SlotState::Ready { stream, .. } = prev {
                                Some((stream, age))
                            } else {
                                None
                            }
                        } else {
                            *state = SlotState::Done;
                            None
                        }
                    } else {
                        None
                    }
                };
                if let Some((stream, age)) = maybe_ready {
                    queue.remove(idx);
                    if queue.is_empty() {
                        map.remove(&key);
                    }
                    return AcquireOutcome::ImmediateReady { stream, age };
                }
            }

            // 2. Otherwise adopt an orphaned in-flight dial started by an earlier socket.
            for slot in queue.iter() {
                let mut state = slot.lock_state();
                if matches!(*state, SlotState::OrphanedInFlight) {
                    *state = SlotState::ClaimedInFlight;
                    return AcquireOutcome::AdoptedInFlight(slot.clone());
                }
            }
        }

        // 3. Otherwise spawn a fresh background dial task.
        let slot = Arc::new(OnionDialSlot::new_claimed());
        map.entry(key).or_default().push_back(slot.clone());
        drop(map);

        let slot_for_task = slot.clone();
        let task = tokio::spawn(async move {
            let outcome = tokio::select! {
                biased;
                _ = pool_shutdown.changed() => {
                    *slot_for_task.lock_state() = SlotState::Done;
                    slot_for_task.notify.notify_waiters();
                    return;
                }
                res = dial_fn => res,
            };

            let mut spawn_reaper = false;
            {
                let mut state = slot_for_task.lock_state();
                match (&*state, outcome) {
                    (SlotState::Done, _) => {}
                    (SlotState::ClaimedInFlight, Ok(stream)) => {
                        *state = SlotState::Ready {
                            stream,
                            ready_at: Instant::now(),
                        };
                    }
                    (SlotState::OrphanedInFlight, Ok(stream)) => {
                        *state = SlotState::Ready {
                            stream,
                            ready_at: Instant::now(),
                        };
                        spawn_reaper = true;
                    }
                    (SlotState::ClaimedInFlight, Err(code)) => {
                        *state = SlotState::Failed(code);
                    }
                    (SlotState::OrphanedInFlight, Err(_)) => {
                        *state = SlotState::Done;
                    }
                    (SlotState::Ready { .. } | SlotState::Failed(_), _) => {}
                }
            }
            slot_for_task.notify.notify_waiters();
            if spawn_reaper {
                Self::spawn_ttl_reaper(slot_for_task, ttl);
            }
        });

        if let Ok(mut guard) = slot.abort_handle.lock() {
            *guard = Some(task.abort_handle());
        }

        AcquireOutcome::SpawnedNew(slot)
    }

    /// Stash an unused connected stream (e.g. when writing `REP_SUCCESS` failed
    /// because the client closed the TCP socket right as the circuit completed).
    pub(crate) fn stash_ready(&self, key: OnionStreamKey, stream: S) {
        let slot = Arc::new(OnionDialSlot::new_ready(stream));
        let mut map = self.lock_slots();
        let queue = map.entry(key).or_default();
        Self::prune_queue(queue, self.stash_ttl);
        queue.push_back(slot.clone());
        drop(map);
        Self::spawn_ttl_reaper(slot, self.stash_ttl);
    }

    /// Wait for `slot` while watching `client_stream` for premature peer disconnect.
    pub(crate) async fn wait_claimed_slot(
        &self,
        slot: Arc<OnionDialSlot<S>>,
        client_stream: &mut TcpStream,
        shutdown_rx: &mut watch::Receiver<bool>,
    ) -> WaitSlotOutcome<S> {
        let mut monitor_disconnect = true;
        loop {
            let notified = slot.notify.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();

            {
                let mut state = slot.lock_state();
                match &*state {
                    SlotState::Ready { .. } => {
                        let prev = std::mem::replace(&mut *state, SlotState::Done);
                        if let SlotState::Ready { stream, .. } = prev {
                            return WaitSlotOutcome::Connected(stream);
                        }
                    }
                    SlotState::Failed(code) => {
                        let code = *code;
                        *state = SlotState::Done;
                        return WaitSlotOutcome::Failed(code);
                    }
                    SlotState::Done => return WaitSlotOutcome::Cancelled,
                    SlotState::ClaimedInFlight | SlotState::OrphanedInFlight => {}
                }
            }

            let mut peek_buf = [0u8; 1];
            tokio::select! {
                biased;
                _ = shutdown_rx.changed() => {
                    slot.cancel_and_abort();
                    return WaitSlotOutcome::Cancelled;
                }
                _ = &mut notified => {
                    continue;
                }
                peek_res = client_stream.peek(&mut peek_buf), if monitor_disconnect => {
                    match peek_res {
                        Ok(0) | Err(_) => {
                            let mut spawn_reaper = false;
                            {
                                let mut state = slot.lock_state();
                                match &*state {
                                    SlotState::ClaimedInFlight => {
                                        *state = SlotState::OrphanedInFlight;
                                    }
                                    SlotState::Ready { .. } => {
                                        spawn_reaper = true;
                                    }
                                    SlotState::Failed(_) => {
                                        *state = SlotState::Done;
                                    }
                                    SlotState::OrphanedInFlight | SlotState::Done => {}
                                }
                            }
                            if spawn_reaper {
                                Self::spawn_ttl_reaper(slot.clone(), self.stash_ttl);
                            }
                            return WaitSlotOutcome::ClientDisconnected;
                        }
                        Ok(_) => {
                            // Client pipelined application bytes without closing; keep waiting.
                            monitor_disconnect = false;
                        }
                    }
                }
            }
        }
    }

    /// Evict and abort all stashed or orphaned `.onion` streams matching
    /// `target_url_or_empty` (`""` clears the entire pool).
    pub(crate) fn clear_for_target(&self, target_url_or_empty: &str) {
        let mut map = self.lock_slots();
        match resolve_isolation_key(target_url_or_empty) {
            None => {
                for (_, queue) in map.drain() {
                    for slot in queue {
                        slot.cancel_and_abort();
                    }
                }
            }
            Some(iso_key) => {
                map.retain(|k, queue| {
                    if k.isolation_key == iso_key {
                        for slot in queue.drain(..) {
                            slot.cancel_and_abort();
                        }
                        false
                    } else {
                        true
                    }
                });
            }
        }
    }
}

/// The SOCKS5 listener.
pub struct Socks5Server {
    local_addr: SocketAddr,
    shutdown: watch::Sender<bool>,
    accept_task: tokio::task::JoinHandle<()>,
    allow_clearnet: Arc<AtomicBool>,
    clearnet: Arc<ClearnetAllowlist>,
    onion_pool: Arc<OnionRendezvousPool<DataStream>>,
}

impl Socks5Server {
    /// Bind the listener and start accepting connections with a default in-memory
    /// [`ClearnetAllowlist`] and 30-second interactive consent wait window.
    pub async fn start(
        bind: SocketAddr,
        isolation: Arc<IsolationRegistry>,
        allow_clearnet: bool,
        connect_timeout: Duration,
    ) -> anyhow::Result<Arc<Self>> {
        Self::start_with_clearnet(
            bind,
            isolation,
            allow_clearnet,
            Arc::new(ClearnetAllowlist::in_memory()),
            connect_timeout,
            DEFAULT_CONSENT_WAIT_TIMEOUT,
        )
        .await
    }

    /// Bind the listener and start accepting connections gated by `clearnet`.
    pub async fn start_with_clearnet(
        bind: SocketAddr,
        isolation: Arc<IsolationRegistry>,
        allow_clearnet: bool,
        clearnet: Arc<ClearnetAllowlist>,
        connect_timeout: Duration,
        consent_timeout: Duration,
    ) -> anyhow::Result<Arc<Self>> {
        let listener = TcpListener::bind(bind)
            .await
            .map_err(|e| anyhow::anyhow!("cannot bind the SOCKS5 listener on {bind}: {e}"))?;
        let local_addr = listener.local_addr()?;
        let (shutdown, mut shutdown_rx) = watch::channel(false);
        let allow_clearnet = Arc::new(AtomicBool::new(allow_clearnet));
        let onion_pool = Arc::new(OnionRendezvousPool::new(ONION_STASH_TTL));

        let accept_task = {
            let shutdown = shutdown.clone();
            let allow_clearnet = allow_clearnet.clone();
            let clearnet = clearnet.clone();
            let onion_pool = onion_pool.clone();
            tokio::spawn(async move {
                loop {
                    tokio::select! {
                        changed = shutdown_rx.changed() => {
                            if changed.is_err() || *shutdown_rx.borrow() {
                                break;
                            }
                        }
                        accepted = listener.accept() => {
                            let Ok((stream, peer)) = accepted else { continue };
                            let isolation = isolation.clone();
                            let shutdown = shutdown.subscribe();
                            let allow_clearnet = allow_clearnet.clone();
                            let clearnet = clearnet.clone();
                            let onion_pool = onion_pool.clone();
                            tokio::spawn(async move {
                                if let Err(err) = handle_connection(
                                    stream,
                                    peer,
                                    isolation,
                                    allow_clearnet,
                                    clearnet,
                                    onion_pool,
                                    connect_timeout,
                                    consent_timeout,
                                    shutdown,
                                )
                                .await
                                {
                                    log::debug!("socks5 connection from {peer} ended: {err:#}");
                                }
                            });
                        }
                    }
                }
            })
        };

        Ok(Arc::new(Self {
            local_addr,
            shutdown,
            accept_task,
            allow_clearnet,
            clearnet,
            onion_pool,
        }))
    }

    /// The address the listener is bound to, including the ephemeral port.
    pub fn local_addr(&self) -> SocketAddr {
        self.local_addr
    }

    /// Shared clearnet consent allowlist & pending-host coordinator.
    pub fn clearnet(&self) -> &Arc<ClearnetAllowlist> {
        &self.clearnet
    }

    /// Current clearnet policy (`false` means hidden-service-only mode).
    pub fn allow_clearnet(&self) -> bool {
        self.allow_clearnet.load(Ordering::Acquire)
    }

    /// Change the traffic policy of the listener immediately.
    pub fn set_allow_clearnet(&self, allow: bool) {
        self.allow_clearnet.store(allow, Ordering::Release);
        if !allow {
            self.clearnet.clear_pending();
        } else {
            self.clearnet.notify_policy_changed();
        }
    }

    /// Evict stashed or orphaned in-flight `.onion` streams (`""` clears all).
    pub fn clear_onion_pool(&self, target_url_or_empty: &str) {
        self.onion_pool.clear_for_target(target_url_or_empty);
    }

    /// Stop accepting new connections, cancel warm-up dials, and wake clearnet waiters.
    pub fn shutdown(&self) {
        let _ = self.shutdown.send(true);
        self.clearnet.clear_pending();
        self.onion_pool.clear_for_target("");
        self.accept_task.abort();
    }
}

impl Drop for Socks5Server {
    fn drop(&mut self) {
        let _ = self.shutdown.send(true);
        self.clearnet.clear_pending();
        self.onion_pool.clear_for_target("");
        self.accept_task.abort();
    }
}

/// Serve a single SOCKS5 client connection.
#[allow(clippy::too_many_arguments)]
async fn handle_connection(
    mut stream: TcpStream,
    _peer: SocketAddr,
    isolation: Arc<IsolationRegistry>,
    allow_clearnet: Arc<AtomicBool>,
    clearnet: Arc<ClearnetAllowlist>,
    onion_pool: Arc<OnionRendezvousPool<DataStream>>,
    connect_timeout: Duration,
    consent_timeout: Duration,
    mut shutdown: watch::Receiver<bool>,
) -> anyhow::Result<()> {
    stream.set_nodelay(true)?;

    let username = negotiate_auth(&mut stream).await?;

    let request = match read_request(&mut stream).await {
        Ok(Some(request)) => request,
        Ok(None) => return Ok(()),
        Err(err) => {
            log::debug!("malformed SOCKS5 request: {err:#}");
            write_reply(&mut stream, REP_ADDRESS_TYPE_NOT_SUPPORTED).await?;
            return Ok(());
        }
    };

    match decide(&request, allow_clearnet.load(Ordering::Acquire)) {
        Socks5Decision::Reject(REP_NOT_ALLOWED) => {
            log::warn!(
                "refusing connection to {}:{} under Tor leak policy",
                request.host,
                request.port
            );
            write_reply(&mut stream, REP_NOT_ALLOWED).await?;
            return Ok(());
        }
        Socks5Decision::Reject(code) => {
            log::debug!(
                "refusing SOCKS5 request to {}:{} with reply code {code}",
                request.host,
                request.port
            );
            write_reply(&mut stream, code).await?;
            return Ok(());
        }
        Socks5Decision::Proceed => {}
    }

    // Enforce interactive ClearnetAllowlist consent on EVERY non-`.onion` connection:
    let is_onion = request.is_onion();
    if !is_onion {
        let now_ms = now_epoch_ms();
        if !clearnet.is_allowed(&request.host, now_ms) {
            if is_chromium_background_endpoint(&request.host) {
                log::debug!(
                    "silently refusing unsolicited Chromium background endpoint {}:{}",
                    request.host,
                    request.port
                );
                write_reply(&mut stream, REP_NOT_ALLOWED).await?;
                return Ok(());
            }

            match clearnet.try_begin_pending_wait(&request.host, now_ms) {
                PendingRegistration::AlreadyAllowed => {}
                PendingRegistration::SessionDenied => {
                    log::debug!(
                        "refusing session-denied clearnet host {}:{}",
                        request.host,
                        request.port
                    );
                    write_reply(&mut stream, REP_NOT_ALLOWED).await?;
                    return Ok(());
                }
                PendingRegistration::Waiting(pending_guard, mut rx) => {
                    log::info!(
                        "holding clearnet SOCKS5 connection to {}:{} awaiting user consent",
                        request.host,
                        request.port
                    );
                    let deadline = tokio::time::Instant::now() + consent_timeout;
                    loop {
                        if !allow_clearnet.load(Ordering::Acquire) {
                            pending_guard.release();
                            write_reply(&mut stream, REP_NOT_ALLOWED).await?;
                            return Ok(());
                        }
                        let check_ms = now_epoch_ms();
                        if clearnet.is_allowed(&request.host, check_ms) {
                            pending_guard.release();
                            break;
                        }
                        if clearnet.is_session_denied(&request.host) {
                            pending_guard.release();
                            write_reply(&mut stream, REP_NOT_ALLOWED).await?;
                            return Ok(());
                        }

                        tokio::select! {
                            biased;
                            changed = shutdown.changed() => {
                                let _ = changed;
                                pending_guard.release();
                                return Ok(());
                            }
                            changed = rx.changed() => {
                                if changed.is_err() {
                                    pending_guard.release();
                                    write_reply(&mut stream, REP_NOT_ALLOWED).await?;
                                    return Ok(());
                                }
                            }
                            _ = tokio::time::sleep_until(deadline) => {
                                log::warn!(
                                    "clearnet consent wait timed out for {}:{}",
                                    request.host,
                                    request.port
                                );
                                pending_guard.release();
                                write_reply(&mut stream, REP_NOT_ALLOWED).await?;
                                return Ok(());
                            }
                        }
                    }
                }
            }
        }
    }

    let key = isolation_key(&request.host, &username);
    let iso_label = if key.is_empty() {
        "clearnet".to_string()
    } else {
        format!("iso-{key}")
    };
    let (client, maybe_token) = isolation.client_and_token_for(&key);

    let mut prefs = StreamPrefs::new();
    if let Some(token) = maybe_token {
        prefs.set_isolation(token);
    }

    let request_started = Instant::now();
    if is_onion {
        let onion_key = OnionStreamKey::new(&key, &request.host, request.port);
        let host_for_dial = request.host.clone();
        let port_for_dial = request.port;
        let iso_for_dial = iso_label.clone();

        let acquired =
            onion_pool.acquire_or_spawn(onion_key.clone(), shutdown.clone(), async move {
                let dial_started = Instant::now();
                log::info!(
                    "connecting to {}:{} (iso={})",
                    host_for_dial,
                    port_for_dial,
                    iso_for_dial
                );
                match tokio::time::timeout(
                    connect_timeout,
                    client.connect_with_prefs((host_for_dial.as_str(), port_for_dial), &prefs),
                )
                .await
                {
                    Ok(Ok(tor_stream)) => {
                        log::info!(
                            "connected to {}:{} (iso={}) in {:.1}s",
                            host_for_dial,
                            port_for_dial,
                            iso_for_dial,
                            dial_started.elapsed().as_secs_f64()
                        );
                        Ok(tor_stream)
                    }
                    Ok(Err(err)) => {
                        let report = anyhow::Error::new(err);
                        log::warn!(
                            "failed {}:{} (iso={}) after {:.1}s: {report:#}",
                            host_for_dial,
                            port_for_dial,
                            iso_for_dial,
                            dial_started.elapsed().as_secs_f64()
                        );
                        Err(REP_HOST_UNREACHABLE)
                    }
                    Err(_) => {
                        log::warn!(
                            "failed {}:{} (iso={}) after {:.1}s: connect_timeout ({:.0}s) elapsed",
                            host_for_dial,
                            port_for_dial,
                            iso_for_dial,
                            dial_started.elapsed().as_secs_f64(),
                            connect_timeout.as_secs_f64()
                        );
                        Err(REP_GENERAL_FAILURE)
                    }
                }
            });

        let tor_stream = match acquired {
            AcquireOutcome::ImmediateReady {
                stream: ready_stream,
                age,
            } => {
                log::info!(
                    "reused warm .onion stream to {}:{} (iso={}, stash_age={:.1}s)",
                    request.host,
                    request.port,
                    iso_label,
                    age.as_secs_f64()
                );
                ready_stream
            }
            AcquireOutcome::AdoptedInFlight(slot) => {
                log::info!(
                    "attached to in-flight .onion rendezvous dial for {}:{} (iso={})",
                    request.host,
                    request.port,
                    iso_label
                );
                match onion_pool
                    .wait_claimed_slot(slot, &mut stream, &mut shutdown)
                    .await
                {
                    WaitSlotOutcome::Connected(s) => s,
                    WaitSlotOutcome::Failed(code) => {
                        write_reply(&mut stream, code).await?;
                        return Ok(());
                    }
                    WaitSlotOutcome::ClientDisconnected => {
                        log::info!(
                            "client closed SOCKS5 socket after {:.1}s while adopted .onion rendezvous to {}:{} (iso={}) continues in background",
                            request_started.elapsed().as_secs_f64(),
                            request.host,
                            request.port,
                            iso_label
                        );
                        return Ok(());
                    }
                    WaitSlotOutcome::Cancelled => return Ok(()),
                }
            }
            AcquireOutcome::SpawnedNew(slot) => {
                match onion_pool
                    .wait_claimed_slot(slot, &mut stream, &mut shutdown)
                    .await
                {
                    WaitSlotOutcome::Connected(s) => s,
                    WaitSlotOutcome::Failed(code) => {
                        write_reply(&mut stream, code).await?;
                        return Ok(());
                    }
                    WaitSlotOutcome::ClientDisconnected => {
                        log::info!(
                            "client closed SOCKS5 socket after {:.1}s while .onion rendezvous to {}:{} (iso={}) continues in background",
                            request_started.elapsed().as_secs_f64(),
                            request.host,
                            request.port,
                            iso_label
                        );
                        return Ok(());
                    }
                    WaitSlotOutcome::Cancelled => return Ok(()),
                }
            }
        };

        if let Err(err) = write_reply(&mut stream, REP_SUCCESS).await {
            log::info!(
                "client closed SOCKS5 socket before REP_SUCCESS for {}:{} (iso={}); stashing warm stream for {}s",
                request.host,
                request.port,
                iso_label,
                ONION_STASH_TTL.as_secs()
            );
            onion_pool.stash_ready(onion_key, tor_stream);
            return Err(err);
        }

        return relay(stream, tor_stream).await;
    }

    // Clearnet destination path:
    log::info!(
        "connecting to {}:{} (iso={})",
        request.host,
        request.port,
        iso_label
    );
    let target = (request.host.as_str(), request.port);
    let tor_stream = tokio::select! {
        biased;
        changed = shutdown.changed() => {
            let _ = changed;
            return Ok(());
        }
        result = tokio::time::timeout(connect_timeout, client.connect_with_prefs(target, &prefs)) => {
            match result {
                Ok(Ok(tor_stream)) => {
                    log::info!(
                        "connected to {}:{} (iso={}) in {:.1}s",
                        request.host,
                        request.port,
                        iso_label,
                        request_started.elapsed().as_secs_f64()
                    );
                    tor_stream
                }
                Ok(Err(err)) => {
                    let report = anyhow::Error::new(err);
                    log::warn!(
                        "failed {}:{} (iso={}) after {:.1}s: {report:#}",
                        request.host,
                        request.port,
                        iso_label,
                        request_started.elapsed().as_secs_f64()
                    );
                    write_reply(&mut stream, REP_HOST_UNREACHABLE).await?;
                    return Ok(());
                }
                Err(_) => {
                    log::warn!(
                        "failed {}:{} (iso={}) after {:.1}s: connect_timeout ({:.0}s) elapsed",
                        request.host,
                        request.port,
                        iso_label,
                        request_started.elapsed().as_secs_f64(),
                        connect_timeout.as_secs_f64()
                    );
                    write_reply(&mut stream, REP_GENERAL_FAILURE).await?;
                    return Ok(());
                }
            }
        }
    };

    write_reply(&mut stream, REP_SUCCESS).await?;
    stream.flush().await?;

    relay(stream, tor_stream).await
}

/// Copy bytes in both directions until either side closes.
async fn relay(mut client: TcpStream, mut tor: DataStream) -> anyhow::Result<()> {
    let mut to_tor = vec![0u8; 32 * 1024];
    let mut to_client = vec![0u8; 32 * 1024];
    loop {
        tokio::select! {
            read = client.read(&mut to_tor) => {
                let n = read?;
                if n == 0 {
                    break;
                }
                tor.write_all(&to_tor[..n]).await?;
                tor.flush().await?;
            }
            write = tor.read(&mut to_client) => {
                let n = write?;
                if n == 0 {
                    break;
                }
                client.write_all(&to_client[..n]).await?;
                client.flush().await?;
            }
        }
    }
    Ok(())
}

/// Perform RFC 1928 method negotiation and optional RFC 1929 user/password exchange.
async fn negotiate_auth(stream: &mut TcpStream) -> anyhow::Result<String> {
    let mut header = [0u8; 2];
    stream.read_exact(&mut header).await?;
    if header[0] != VERSION {
        anyhow::bail!("unsupported SOCKS version {}", header[0]);
    }
    let method_count = header[1];
    if method_count == 0 {
        anyhow::bail!("client offered no authentication method");
    }
    let mut methods = vec![0u8; method_count as usize];
    stream.read_exact(&mut methods).await?;

    if methods.contains(&METHOD_USERPASS) {
        stream.write_all(&[VERSION, METHOD_USERPASS]).await?;
        stream.flush().await?;
        let username = read_userpass(stream).await?;
        Ok(username)
    } else if methods.contains(&METHOD_NONE) {
        stream.write_all(&[VERSION, METHOD_NONE]).await?;
        stream.flush().await?;
        Ok(String::new())
    } else {
        stream.write_all(&[VERSION, METHOD_UNACCEPTABLE]).await?;
        anyhow::bail!("client offered no supported authentication method")
    }
}

/// RFC 1929 user/password sub-negotiation.
async fn read_userpass(stream: &mut TcpStream) -> anyhow::Result<String> {
    let mut header = [0u8; 2];
    stream.read_exact(&mut header).await?;
    if header[0] != USERPASS_VERSION {
        anyhow::bail!("unsupported user/password version {}", header[0]);
    }
    let name_len = header[1];
    let mut username = vec![0u8; name_len as usize];
    stream.read_exact(&mut username).await?;

    let mut pass_len = [0u8; 1];
    stream.read_exact(&mut pass_len).await?;
    let mut password = vec![0u8; pass_len[0] as usize];
    stream.read_exact(&mut password).await?;

    stream.write_all(&[USERPASS_VERSION, 0x00]).await?;
    stream.flush().await?;

    Ok(String::from_utf8_lossy(&username).into_owned())
}

/// Read a single SOCKS5 request frame.
async fn read_request(stream: &mut TcpStream) -> anyhow::Result<Option<Socks5Request>> {
    let mut header = [0u8; 4];
    if stream.read_exact(&mut header).await.is_err() {
        return Ok(None);
    }

    let mut frame = Vec::with_capacity(4 + MAX_DOMAIN_LEN as usize + 3);
    frame.extend_from_slice(&header);

    match header[3] {
        ATYP_IPV4 | ATYP_IPV6 => {
            let len = if header[3] == ATYP_IPV4 { 4 } else { 16 };
            let mut address = vec![0u8; len];
            stream.read_exact(&mut address).await?;
            frame.extend_from_slice(&address);
        }
        ATYP_DOMAIN => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len).await?;
            if len[0] == 0 {
                anyhow::bail!("invalid domain length {}", len[0]);
            }
            let mut name = vec![0u8; len[0] as usize];
            stream.read_exact(&mut name).await?;
            frame.push(len[0]);
            frame.extend_from_slice(&name);
        }
        other => anyhow::bail!("unsupported address type {other}"),
    }

    let mut port = [0u8; 2];
    stream.read_exact(&mut port).await?;
    frame.extend_from_slice(&port);

    let request =
        parse_request(&frame).map_err(|err| anyhow::anyhow!("malformed SOCKS5 request: {err}"))?;
    Ok(Some(request))
}

async fn write_reply(stream: &mut TcpStream, code: u8) -> anyhow::Result<()> {
    stream.write_all(&reply_frame(code)).await?;
    stream.flush().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::AtomicUsize;

    use super::*;

    const TEST_ONION: &str = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion";

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn orphaned_onion_dial_survives_client_disconnect_and_serves_retry_from_stash(
    ) -> anyhow::Result<()> {
        let pool = Arc::new(OnionRendezvousPool::<tokio::io::DuplexStream>::new(
            Duration::from_secs(5),
        ));
        let (_shutdown_tx, mut shutdown_rx) = watch::channel(false);
        let dial_count = Arc::new(AtomicUsize::new(0));
        let key = OnionStreamKey::new("duckduckgogg42xj", TEST_ONION, 443);

        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await?;
        let addr = listener.local_addr()?;

        // Simulate Chromium opening socket #1 and timing out at 40ms while Tor takes 120ms:
        let client1 = TcpStream::connect(addr).await?;
        let (mut server_side1, _) = listener.accept().await?;

        let dials_clone = dial_count.clone();
        let acquired1 = pool.acquire_or_spawn(key.clone(), shutdown_rx.clone(), async move {
            dials_clone.fetch_add(1, Ordering::SeqCst);
            tokio::time::sleep(Duration::from_millis(120)).await;
            let (mut remote, local) = tokio::io::duplex(1024);
            tokio::spawn(async move {
                let _ = remote.write_all(b"HTTP/1.1 200 OK\r\n\r\n").await;
            });
            Ok(local)
        });

        let AcquireOutcome::SpawnedNew(slot1) = acquired1 else {
            panic!("expected SpawnedNew on cold .onion key");
        };

        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(40)).await;
            drop(client1);
        });

        let wait1 = pool
            .wait_claimed_slot(slot1, &mut server_side1, &mut shutdown_rx)
            .await;
        assert!(
            matches!(wait1, WaitSlotOutcome::ClientDisconnected),
            "first socket must detect client disconnect without cancelling background dial"
        );

        // Wait until the 120ms dial finishes and enters the 15s Ready stash:
        tokio::time::sleep(Duration::from_millis(110)).await;

        // Retry arrives 30ms after completion -> must claim the Ready stream in < 10ms without re-dialing!
        let retry_started = Instant::now();
        let dials_clone2 = dial_count.clone();
        let acquired2 = pool.acquire_or_spawn(key, shutdown_rx.clone(), async move {
            dials_clone2.fetch_add(1, Ordering::SeqCst);
            Err(REP_GENERAL_FAILURE)
        });

        let AcquireOutcome::ImmediateReady { mut stream, .. } = acquired2 else {
            panic!("expected ImmediateReady from warm .onion stash");
        };
        assert!(retry_started.elapsed() < Duration::from_millis(20));
        assert_eq!(
            dial_count.load(Ordering::SeqCst),
            1,
            "must not launch a second dial"
        );

        let mut buf = Vec::new();
        stream.read_to_end(&mut buf).await?;
        assert_eq!(buf, b"HTTP/1.1 200 OK\r\n\r\n");
        Ok(())
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn retry_while_orphaned_dial_still_in_flight_coalesces_onto_same_dial(
    ) -> anyhow::Result<()> {
        let pool = Arc::new(OnionRendezvousPool::<u64>::new(Duration::from_secs(5)));
        let (_shutdown_tx, mut shutdown_rx) = watch::channel(false);
        let dial_count = Arc::new(AtomicUsize::new(0));
        let key = OnionStreamKey::new("duckduckgogg42xj", TEST_ONION, 80);

        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await?;
        let addr = listener.local_addr()?;

        let client1 = TcpStream::connect(addr).await?;
        let (mut server_side1, _) = listener.accept().await?;

        let dials_clone = dial_count.clone();
        let AcquireOutcome::SpawnedNew(slot1) =
            pool.acquire_or_spawn(key.clone(), shutdown_rx.clone(), async move {
                dials_clone.fetch_add(1, Ordering::SeqCst);
                tokio::time::sleep(Duration::from_millis(160)).await;
                Ok(42_u64)
            })
        else {
            panic!("expected SpawnedNew");
        };

        // Client #1 disconnects at 30ms while dial is still at 30/160ms:
        drop(client1);
        let wait1 = pool
            .wait_claimed_slot(slot1, &mut server_side1, &mut shutdown_rx)
            .await;
        assert!(matches!(wait1, WaitSlotOutcome::ClientDisconnected));

        // Client #2 retries immediately at ~35ms -> adopts the in-flight dial!
        let _client2 = TcpStream::connect(addr).await?;
        let (mut server_side2, _) = listener.accept().await?;
        let dials_clone2 = dial_count.clone();
        let acquired2 = pool.acquire_or_spawn(key.clone(), shutdown_rx.clone(), async move {
            dials_clone2.fetch_add(1, Ordering::SeqCst);
            Ok(99_u64)
        });
        let AcquireOutcome::AdoptedInFlight(slot2) = acquired2 else {
            panic!("expected AdoptedInFlight while background dial is still running");
        };

        let wait2 = pool
            .wait_claimed_slot(slot2, &mut server_side2, &mut shutdown_rx)
            .await;
        assert!(matches!(wait2, WaitSlotOutcome::Connected(42)));
        assert_eq!(dial_count.load(Ordering::SeqCst), 1);

        // Verify clear_for_target leaves unrelated identities intact and evicts matching ones:
        pool.stash_ready(key.clone(), 77_u64);
        pool.clear_for_target("2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion");
        let untargeted =
            pool.acquire_or_spawn(key.clone(), shutdown_rx.clone(), async { Ok(66_u64) });
        assert!(
            matches!(
                untargeted,
                AcquireOutcome::ImmediateReady { stream: 77, .. }
            ),
            "untargeted identity reset must preserve stashed stream"
        );

        pool.stash_ready(key.clone(), 78_u64);
        pool.clear_for_target(TEST_ONION);
        let acquired3 = pool.acquire_or_spawn(key, shutdown_rx.clone(), async { Ok(88_u64) });
        assert!(
            matches!(acquired3, AcquireOutcome::SpawnedNew(_)),
            "clear_for_target must evict stashed stream on identity reset"
        );
        Ok(())
    }
}
