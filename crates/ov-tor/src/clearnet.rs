//! Clearnet consent allowlist, host globbing, and SOCKS5 pending-connection coordination.
//!
//! When strict `hidden_service_only` mode is disabled (`allow_clearnet = true`),
//! clearnet connections are still blocked by default until the user explicitly
//! grants consent for the host or domain pattern (e.g. `*.tor.taxi` for 3 days,
//! or a session-only rule).

use std::{
    collections::{HashMap, HashSet, VecDeque},
    net::IpAddr,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use serde::{Deserialize, Serialize};
use tokio::sync::watch;

/// Default persistent TTL for user-approved clearnet rules: 3 days (`259,200` seconds).
pub const DEFAULT_CLEARNET_TTL_SECS: u64 = 3 * 24 * 60 * 60;

/// Maximum time an unapproved clearnet SOCKS5 connection waits for interactive consent.
pub const DEFAULT_CONSENT_WAIT_TIMEOUT: Duration = Duration::from_secs(30);

/// File name inside `state_dir` or `data_dir` storing persistent clearnet rules.
pub const CLEARNET_ALLOWLIST_FILENAME: &str = "clearnet_allowlist.json";

/// Unsolicited Chromium internal Google/Cloudflare background endpoints that must be
/// silently refused when not explicitly matched by a user allow-rule so background
/// browser pings never trigger user consent sheets.
const CHROMIUM_BACKGROUND_PATTERNS: &[&str] = &[
    "*.googleapis.com",
    "*.gstatic.com",
    "*.gvt1.com",
    "*.pki.goog",
    "chrome.cloudflare-dns.com",
];

/// Common two-label ccTLD public suffixes whose registrable domain requires three labels
/// (e.g. `www.bbc.co.uk` -> `*.bbc.co.uk` rather than `*.co.uk`).
const COMPOUND_CCTLD_SECOND_LEVELS: &[&str] = &[
    "ac", "co", "com", "edu", "go", "gov", "gob", "mil", "ne", "net", "nom", "or", "org", "sch",
];

/// Current wall-clock UNIX epoch timestamp in milliseconds.
pub fn now_epoch_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

/// Normalize a hostname or glob pattern for canonical comparison:
/// trims whitespace, strips optional URL scheme/path if pasted, removes IPv6 brackets
/// and trailing DNS root dots, and lowercases ASCII characters.
pub fn normalize_host_or_pattern(raw: &str) -> String {
    let mut s = raw.trim();
    if s.is_empty() {
        return String::new();
    }

    if let Some((_, rest)) = s.split_once("://") {
        s = rest.split(['/', '?', '#']).next().unwrap_or(rest);
        if let Some((_, hp)) = s.rsplit_once('@') {
            s = hp;
        }
    }

    if let Some(bracketed) = s.strip_prefix('[') {
        if let Some((ipv6, _)) = bracketed.split_once(']') {
            return ipv6.trim().to_ascii_lowercase();
        }
    } else if let Some((h, port)) = s.rsplit_once(':') {
        // Strip `:port` from non-IPv6 inputs while leaving bare IPv6 literals intact.
        if !h.contains(':') && !port.is_empty() && port.bytes().all(|b| b.is_ascii_digit()) {
            s = h;
        }
    }

    let stripped = s
        .trim_start_matches('[')
        .trim_end_matches(']')
        .trim_end_matches('.');
    stripped.to_ascii_lowercase()
}

/// Match `host` against `pattern` with case-insensitive DNS normalization, `*` and `?`
/// wildcards anywhere, and root-inclusive `*.example.tld` matching (`*.tor.taxi`
/// matches `tor.taxi`, `www.tor.taxi`, and `a.b.tor.taxi`).
pub fn matches_host_glob(pattern: &str, host: &str) -> bool {
    let p = normalize_host_or_pattern(pattern);
    let h = normalize_host_or_pattern(host);
    if p.is_empty() || h.is_empty() {
        return false;
    }

    if let Some(rest) = p.strip_prefix("*.").filter(|r| !r.is_empty()) {
        if wildcard_match(rest, &h) {
            return true;
        }
    }

    wildcard_match(&p, &h)
}

fn wildcard_match(pat: &str, text: &str) -> bool {
    let p: Vec<char> = pat.chars().collect();
    let t: Vec<char> = text.chars().collect();
    let (mut pi, mut ti) = (0usize, 0usize);
    let (mut star_pi, mut star_ti) = (None::<usize>, 0usize);

    while ti < t.len() {
        if pi < p.len() && (p[pi] == '?' || p[pi] == t[ti]) {
            pi += 1;
            ti += 1;
        } else if pi < p.len() && p[pi] == '*' {
            star_pi = Some(pi);
            pi += 1;
            star_ti = ti;
        } else if let Some(sp) = star_pi {
            pi = sp + 1;
            star_ti += 1;
            ti = star_ti;
        } else {
            return false;
        }
    }

    while pi < p.len() && p[pi] == '*' {
        pi += 1;
    }

    pi == p.len()
}

/// Suggest a default consent glob pattern for `host`:
/// - IPv4/IPv6 literals and single-label hosts return normalized `host` as-is.
/// - Domain names return `*.<registrable_base>` (e.g. `tor.taxi` -> `*.tor.taxi`,
///   `www.tor.taxi` -> `*.tor.taxi`, `sub.tor.taxi` -> `*.tor.taxi`,
///   `www.bbc.co.uk` -> `*.bbc.co.uk`).
pub fn suggested_glob_for_host(host: &str) -> String {
    let normalized = normalize_host_or_pattern(host);
    if normalized.is_empty() {
        return String::new();
    }

    if normalized.parse::<IpAddr>().is_ok() {
        return normalized;
    }

    if normalized == "onion" || normalized.ends_with(".onion") {
        return normalized;
    }

    let labels: Vec<&str> = normalized.split('.').filter(|s| !s.is_empty()).collect();
    if labels.len() <= 1 {
        return normalized;
    }

    let tld = labels[labels.len() - 1];
    let second = labels[labels.len() - 2];
    let is_compound_cctld = tld.len() == 2
        && tld.bytes().all(|b| b.is_ascii_alphabetic())
        && COMPOUND_CCTLD_SECOND_LEVELS.contains(&second);

    if is_compound_cctld {
        if labels.len() >= 3 {
            let base = labels[labels.len() - 3..].join(".");
            format!("*.{base}")
        } else {
            normalized
        }
    } else {
        let base = labels[labels.len() - 2..].join(".");
        format!("*.{base}")
    }
}

/// Returns `true` if `host` is a built-in Chromium/Google background telemetry, update,
/// autofill, PKI, or DoH endpoint that should be silently refused unless explicitly allowed.
pub fn is_chromium_background_endpoint(host: &str) -> bool {
    let normalized = normalize_host_or_pattern(host);
    if normalized.is_empty() {
        return false;
    }
    CHROMIUM_BACKGROUND_PATTERNS
        .iter()
        .any(|pat| matches_host_glob(pat, &normalized))
}

/// A single clearnet consent rule (persistent with wall-clock expiry or session-only in memory).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClearnetRule {
    /// Lowercase host or glob pattern (e.g. `"*.tor.taxi"` or `"tor.taxi"`).
    pub pattern: String,
    /// Creation timestamp in UNIX epoch milliseconds.
    pub created_at_ms: u64,
    /// Expiration timestamp in UNIX epoch milliseconds (`0` when `session_only == true`).
    pub expires_at_ms: u64,
    /// `true` when granted only for the current process session (not written to disk).
    pub session_only: bool,
}

impl ClearnetRule {
    /// `true` when this rule has expired at `now_ms`.
    pub fn is_expired(&self, now_ms: u64) -> bool {
        !self.session_only && self.expires_at_ms <= now_ms
    }
}

struct ClearnetInner {
    rules: Vec<ClearnetRule>,
    session_denied: HashSet<String>,
    pending_hosts: VecDeque<String>,
    active_waiters: HashMap<String, usize>,
    revision: u64,
}

/// Thread-safe clearnet consent policy store, atomic JSON persistence engine,
/// and async SOCKS5 waiter coordinator.
pub struct ClearnetAllowlist {
    storage_path: Option<PathBuf>,
    inner: Mutex<ClearnetInner>,
    change_tx: watch::Sender<u64>,
}

/// RAII guard for an in-flight SOCKS5 connection awaiting interactive clearnet consent.
pub struct PendingWaitGuard {
    allowlist: Arc<ClearnetAllowlist>,
    host: String,
    released: bool,
}

impl PendingWaitGuard {
    /// Explicitly release this waiter slot before drop.
    pub fn release(mut self) {
        self.release_inner();
        self.released = true;
    }

    fn release_inner(&mut self) {
        if self.released || self.host.is_empty() {
            return;
        }
        self.allowlist.finish_pending_wait(&self.host);
    }
}

impl Drop for PendingWaitGuard {
    fn drop(&mut self) {
        self.release_inner();
    }
}

impl Default for ClearnetAllowlist {
    fn default() -> Self {
        Self::in_memory()
    }
}

/// Result of atomically evaluating clearnet consent or registering a pending waiter.
pub enum PendingRegistration {
    /// Host is already covered by an active allowlist rule.
    AlreadyAllowed,
    /// Host was denied during this session.
    SessionDenied,
    /// Host is now registered in `pending_hosts` awaiting user consent.
    Waiting(PendingWaitGuard, watch::Receiver<u64>),
}

impl ClearnetAllowlist {
    /// Create a purely in-memory allowlist (used in unit tests or pre-initialization).
    pub fn in_memory() -> Self {
        let (change_tx, _) = watch::channel(0u64);
        Self {
            storage_path: None,
            inner: Mutex::new(ClearnetInner {
                rules: Vec::new(),
                session_denied: HashSet::new(),
                pending_hosts: VecDeque::new(),
                active_waiters: HashMap::new(),
                revision: 0,
            }),
            change_tx,
        }
    }

    /// Load persistent clearnet rules from `path` (if present), pruning expired entries
    /// immediately and configuring atomic persistence to `path`.
    pub fn load_or_new(path: impl Into<PathBuf>) -> Self {
        Self::load_or_new_at(path.into(), now_epoch_ms())
    }

    /// Deterministic clock variant of [`Self::load_or_new`].
    pub fn load_or_new_at(path: PathBuf, now_ms: u64) -> Self {
        let mut loaded_rules: Vec<ClearnetRule> = Vec::new();
        let mut pruned_on_load = false;

        if path.is_file() {
            match std::fs::read_to_string(&path) {
                Ok(raw) => match serde_json::from_str::<Vec<ClearnetRule>>(&raw) {
                    Ok(parsed) => {
                        let initial_len = parsed.len();
                        for mut rule in parsed {
                            rule.pattern = normalize_host_or_pattern(&rule.pattern);
                            if rule.pattern.is_empty()
                                || rule.session_only
                                || rule.is_expired(now_ms)
                            {
                                pruned_on_load = true;
                                continue;
                            }
                            if let Some(existing) =
                                loaded_rules.iter_mut().find(|r| r.pattern == rule.pattern)
                            {
                                *existing = rule;
                                pruned_on_load = true;
                            } else {
                                loaded_rules.push(rule);
                            }
                        }
                        if loaded_rules.len() != initial_len {
                            pruned_on_load = true;
                        }
                    }
                    Err(err) => {
                        log::warn!("cannot parse clearnet allowlist {}: {err}", path.display());
                    }
                },
                Err(err) => {
                    log::warn!("cannot read clearnet allowlist {}: {err}", path.display());
                }
            }
        }

        if pruned_on_load {
            let _ = persist_rules_atomic(&path, &loaded_rules, now_ms);
        }

        let (change_tx, _) = watch::channel(0u64);
        Self {
            storage_path: Some(path),
            inner: Mutex::new(ClearnetInner {
                rules: loaded_rules,
                session_denied: HashSet::new(),
                pending_hosts: VecDeque::new(),
                active_waiters: HashMap::new(),
                revision: 0,
            }),
            change_tx,
        }
    }

    fn lock_inner(&self) -> std::sync::MutexGuard<'_, ClearnetInner> {
        match self.inner.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        }
    }

    fn bump_and_broadcast(&self, guard: &mut ClearnetInner) {
        guard.revision = guard.revision.wrapping_add(1);
        let rev = guard.revision;
        let _ = self.change_tx.send(rev);
    }

    fn prune_expired_locked(&self, guard: &mut ClearnetInner, now_ms: u64) -> bool {
        let before = guard.rules.len();
        guard.rules.retain(|r| !r.is_expired(now_ms));
        guard.rules.len() != before
    }

    fn persist_if_configured(&self, rules: &[ClearnetRule], now_ms: u64) -> bool {
        let Some(ref path) = self.storage_path else {
            return true;
        };
        match persist_rules_atomic(path, rules, now_ms) {
            Ok(()) => true,
            Err(err) => {
                log::error!(
                    "cannot persist clearnet allowlist to {}: {err:#}",
                    path.display()
                );
                false
            }
        }
    }

    /// Backing storage path on disk, if persistent.
    pub fn storage_path(&self) -> Option<&Path> {
        self.storage_path.as_deref()
    }

    /// Check whether `host` is currently permitted by at least one active rule at `now_ms`.
    /// Automatically prunes any expired rules encountered.
    pub fn is_allowed(&self, host: &str, now_ms: u64) -> bool {
        let normalized = normalize_host_or_pattern(host);
        if normalized.is_empty() {
            return false;
        }

        let (allowed, snapshot_to_persist) = {
            let mut guard = self.lock_inner();
            let pruned = self.prune_expired_locked(&mut guard, now_ms);
            let matched = guard
                .rules
                .iter()
                .any(|rule| matches_host_glob(&rule.pattern, &normalized));
            let to_persist = pruned.then(|| guard.rules.clone());
            (matched, to_persist)
        };

        if let Some(rules) = snapshot_to_persist {
            let _ = self.persist_if_configured(&rules, now_ms);
        }

        allowed
    }

    /// Check whether `host` was denied by the user during this session.
    pub fn is_session_denied(&self, host: &str) -> bool {
        let normalized = normalize_host_or_pattern(host);
        if normalized.is_empty() {
            return false;
        }
        let guard = self.lock_inner();
        guard
            .session_denied
            .iter()
            .any(|denied| denied == &normalized || matches_host_glob(denied, &normalized))
    }

    /// Add or refresh a clearnet rule (`ttl_seconds <= 0` creates a `session_only: true` rule;
    /// `ttl_seconds > 0` persists to disk for `ttl_seconds` seconds), clear matching session
    /// denials and pending hosts, wake all waiting SOCKS5 streams, and return active rules.
    pub fn allow(&self, pattern: &str, ttl_seconds: i64) -> Vec<ClearnetRule> {
        self.allow_at(pattern, ttl_seconds, now_epoch_ms())
    }

    /// Deterministic clock variant of [`Self::allow`].
    pub fn allow_at(&self, pattern: &str, ttl_seconds: i64, now_ms: u64) -> Vec<ClearnetRule> {
        let normalized = normalize_host_or_pattern(pattern);
        if normalized.is_empty() {
            return self.list_rules_at(now_ms);
        }

        let session_only = ttl_seconds <= 0;
        let expires_at_ms = if session_only {
            0
        } else {
            now_ms.saturating_add((ttl_seconds as u64).saturating_mul(1_000))
        };

        let new_rule = ClearnetRule {
            pattern: normalized.clone(),
            created_at_ms: now_ms,
            expires_at_ms,
            session_only,
        };

        let active_rules = {
            let mut guard = self.lock_inner();
            self.prune_expired_locked(&mut guard, now_ms);

            guard.session_denied.retain(|denied| {
                denied != &normalized
                    && !matches_host_glob(&normalized, denied)
                    && !matches_host_glob(denied, &normalized)
            });

            guard
                .pending_hosts
                .retain(|pending| !matches_host_glob(&normalized, pending));

            if let Some(existing) = guard.rules.iter_mut().find(|r| r.pattern == normalized) {
                *existing = new_rule;
            } else {
                guard.rules.push(new_rule);
            }

            self.bump_and_broadcast(&mut guard);
            guard.rules.clone()
        };

        let _ = self.persist_if_configured(&active_rules, now_ms);
        active_rules
    }

    /// Deny `host` for the remainder of this session, remove matching entries from
    /// `pending_hosts`, and wake all waiting SOCKS5 connections immediately.
    pub fn deny(&self, host: &str) -> bool {
        let normalized = normalize_host_or_pattern(host);
        if normalized.is_empty() {
            return false;
        }

        let mut guard = self.lock_inner();
        guard.session_denied.insert(normalized.clone());
        guard
            .pending_hosts
            .retain(|pending| pending != &normalized && !matches_host_glob(&normalized, pending));
        self.bump_and_broadcast(&mut guard);
        true
    }

    /// Return all non-expired clearnet rules (pruning any expired entries).
    pub fn list_rules(&self) -> Vec<ClearnetRule> {
        self.list_rules_at(now_epoch_ms())
    }

    /// Deterministic clock variant of [`Self::list_rules`].
    pub fn list_rules_at(&self, now_ms: u64) -> Vec<ClearnetRule> {
        let (rules, pruned) = {
            let mut guard = self.lock_inner();
            let pruned = self.prune_expired_locked(&mut guard, now_ms);
            (guard.rules.clone(), pruned)
        };
        if pruned {
            let _ = self.persist_if_configured(&rules, now_ms);
        }
        rules
    }

    /// Remove `pattern` from the allowlist, persist to disk, notify listeners, and
    /// return the updated list of active rules.
    pub fn revoke_rule(&self, pattern: &str) -> Vec<ClearnetRule> {
        self.revoke_rule_at(pattern, now_epoch_ms())
    }

    /// Deterministic clock variant of [`Self::revoke_rule`].
    pub fn revoke_rule_at(&self, pattern: &str, now_ms: u64) -> Vec<ClearnetRule> {
        let normalized = normalize_host_or_pattern(pattern);
        let rules = {
            let mut guard = self.lock_inner();
            self.prune_expired_locked(&mut guard, now_ms);
            if !normalized.is_empty() {
                guard.rules.retain(|r| r.pattern != normalized);
            }
            self.bump_and_broadcast(&mut guard);
            guard.rules.clone()
        };
        let _ = self.persist_if_configured(&rules, now_ms);
        rules
    }

    /// Clear all persistent/session allow rules and session denials, persist to disk,
    /// and notify listeners.
    pub fn clear_all(&self) -> bool {
        let now_ms = now_epoch_ms();
        {
            let mut guard = self.lock_inner();
            guard.rules.clear();
            guard.session_denied.clear();
            self.bump_and_broadcast(&mut guard);
        }
        self.persist_if_configured(&[], now_ms)
    }

    /// Oldest unresolved pending clearnet host awaiting user consent on `Socks5Server`.
    pub fn oldest_pending_host(&self) -> Option<String> {
        let guard = self.lock_inner();
        guard.pending_hosts.front().cloned()
    }

    /// Snapshot of all deduplicated pending clearnet hosts in FIFO order.
    pub fn pending_hosts(&self) -> Vec<String> {
        let guard = self.lock_inner();
        guard.pending_hosts.iter().cloned().collect()
    }

    /// Wake all waiting SOCKS5 streams when outer policy (such as `hidden_service_only`) changes.
    pub fn notify_policy_changed(&self) {
        let mut guard = self.lock_inner();
        self.bump_and_broadcast(&mut guard);
    }

    /// Clear all pending hosts and wake all waiting SOCKS5 streams (e.g. when entering
    /// strict `hidden_service_only` mode or shutting down).
    pub fn clear_pending(&self) {
        let mut guard = self.lock_inner();
        guard.pending_hosts.clear();
        self.bump_and_broadcast(&mut guard);
    }

    /// Atomically evaluate `host` at `now_ms`, or register an in-flight SOCKS5 waiter
    /// in deduplicated FIFO `pending_hosts` with a race-free revision receiver.
    pub fn try_begin_pending_wait(
        self: &Arc<Self>,
        host: &str,
        now_ms: u64,
    ) -> PendingRegistration {
        let normalized = normalize_host_or_pattern(host);
        if normalized.is_empty() {
            return PendingRegistration::SessionDenied;
        }

        let mut rx = self.change_tx.subscribe();
        let mut to_persist: Option<Vec<ClearnetRule>> = None;

        let outcome = {
            let mut guard = self.lock_inner();
            if self.prune_expired_locked(&mut guard, now_ms) {
                to_persist = Some(guard.rules.clone());
            }

            if guard
                .rules
                .iter()
                .any(|rule| matches_host_glob(&rule.pattern, &normalized))
            {
                PendingRegistration::AlreadyAllowed
            } else if guard
                .session_denied
                .iter()
                .any(|denied| denied == &normalized || matches_host_glob(denied, &normalized))
            {
                PendingRegistration::SessionDenied
            } else {
                *guard.active_waiters.entry(normalized.clone()).or_insert(0) += 1;
                if !guard.pending_hosts.iter().any(|h| h == &normalized) {
                    guard.pending_hosts.push_back(normalized.clone());
                }
                let _ = *rx.borrow_and_update();
                PendingRegistration::Waiting(
                    PendingWaitGuard {
                        allowlist: Arc::clone(self),
                        host: normalized,
                        released: false,
                    },
                    rx,
                )
            }
        };

        if let Some(rules) = to_persist {
            let _ = self.persist_if_configured(&rules, now_ms);
        }

        outcome
    }

    /// Register an in-flight SOCKS5 connection waiting for user consent on `host`.
    ///
    /// Atomically records `host` in deduplicated FIFO `pending_hosts` and subscribes
    /// to policy revisions so no concurrent `allow`/`deny` call can be missed.
    pub fn begin_pending_wait(
        self: &Arc<Self>,
        host: &str,
    ) -> (PendingWaitGuard, watch::Receiver<u64>) {
        let normalized = normalize_host_or_pattern(host);
        let mut rx = self.change_tx.subscribe();
        {
            let mut guard = self.lock_inner();
            if !normalized.is_empty() {
                *guard.active_waiters.entry(normalized.clone()).or_insert(0) += 1;
                if !guard.pending_hosts.iter().any(|h| h == &normalized) {
                    guard.pending_hosts.push_back(normalized.clone());
                }
            }
            // Mark current revision as seen while holding the mutex so `rx.changed()`
            // wakes on the very next mutation after `begin_pending_wait`.
            let _ = *rx.borrow_and_update();
        }
        (
            PendingWaitGuard {
                allowlist: Arc::clone(self),
                host: normalized,
                released: false,
            },
            rx,
        )
    }

    fn finish_pending_wait(&self, normalized_host: &str) {
        let mut guard = self.lock_inner();
        if let Some(count) = guard.active_waiters.get_mut(normalized_host) {
            if *count > 1 {
                *count -= 1;
                return;
            }
        }
        guard.active_waiters.remove(normalized_host);
        guard.pending_hosts.retain(|h| h != normalized_host);
    }
}

fn persist_rules_atomic(path: &Path, rules: &[ClearnetRule], now_ms: u64) -> anyhow::Result<()> {
    if let Some(parent) = path.parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent)?;
        }
    }

    let persistent: Vec<&ClearnetRule> = rules
        .iter()
        .filter(|r| !r.session_only && !r.is_expired(now_ms))
        .collect();
    let payload = serde_json::to_vec_pretty(&persistent)?;

    let file_name = path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or(CLEARNET_ALLOWLIST_FILENAME);
    let tmp_path = path.with_file_name(format!(
        ".{file_name}.tmp.{}.{}",
        std::process::id(),
        now_ms
    ));

    {
        use std::io::Write;
        let mut file = std::fs::File::create(&tmp_path)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = file.set_permissions(std::fs::Permissions::from_mode(0o600));
        }
        file.write_all(&payload)?;
        file.sync_all()?;
    }

    std::fs::rename(&tmp_path, path)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn glob_matching_supports_root_inclusive_subdomain_wildcards_and_single_char_globs() {
        assert!(matches_host_glob("tor.taxi", "tor.taxi"));
        assert!(matches_host_glob("TOR.TAXI.", "tor.taxi."));
        assert!(!matches_host_glob("tor.taxi", "www.tor.taxi"));

        // Leading `*.example.tld` MUST match both `example.tld` and any depth of subdomain:
        assert!(matches_host_glob("*.tor.taxi", "tor.taxi"));
        assert!(matches_host_glob("*.tor.taxi", "www.tor.taxi"));
        assert!(matches_host_glob("*.tor.taxi", "a.b.c.tor.taxi"));
        assert!(!matches_host_glob("*.tor.taxi", "eviltor.taxi"));
        assert!(!matches_host_glob("*.tor.taxi", "tor.taxi.evil.com"));

        // Global `*` and `?` wildcards:
        assert!(matches_host_glob("*", "tor.taxi"));
        assert!(matches_host_glob("*", "a.b.example.org"));
        assert!(matches_host_glob("cdn?.tor.taxi", "cdn1.tor.taxi"));
        assert!(!matches_host_glob("cdn?.tor.taxi", "cdn12.tor.taxi"));
        assert!(matches_host_glob(
            "*mirror*.tor.taxi",
            "eu-mirror-2.tor.taxi"
        ));
    }

    #[test]
    fn suggested_glob_handles_domains_subdomains_cctlds_ips_and_single_labels() {
        assert_eq!(suggested_glob_for_host("tor.taxi"), "*.tor.taxi");
        assert_eq!(suggested_glob_for_host("www.tor.taxi"), "*.tor.taxi");
        assert_eq!(suggested_glob_for_host("sub.tor.taxi"), "*.tor.taxi");
        assert_eq!(suggested_glob_for_host("TOR.TAXI."), "*.tor.taxi");
        assert_eq!(suggested_glob_for_host("www.bbc.co.uk"), "*.bbc.co.uk");
        assert_eq!(suggested_glob_for_host("bbc.co.uk"), "*.bbc.co.uk");
        assert_eq!(suggested_glob_for_host("1.1.1.1"), "1.1.1.1");
        assert_eq!(
            suggested_glob_for_host("[2606:4700:4700::1111]"),
            "2606:4700:4700::1111"
        );
        assert_eq!(suggested_glob_for_host("intranet"), "intranet");
    }

    #[test]
    fn chromium_background_endpoints_are_detected_accurately() {
        assert!(is_chromium_background_endpoint(
            "content-autofill.googleapis.com"
        ));
        assert!(is_chromium_background_endpoint("googleapis.com"));
        assert!(is_chromium_background_endpoint("www.gstatic.com"));
        assert!(is_chromium_background_endpoint("edgedl.me.gvt1.com"));
        assert!(is_chromium_background_endpoint("ocsp.pki.goog"));
        assert!(is_chromium_background_endpoint("chrome.cloudflare-dns.com"));

        assert!(!is_chromium_background_endpoint("tor.taxi"));
        assert!(!is_chromium_background_endpoint("1.1.1.1"));
        assert!(!is_chromium_background_endpoint("cloudflare-dns.com"));
    }

    #[test]
    fn three_day_ttl_persists_across_reload_prunes_expired_and_excludes_session_rules() {
        let dir = std::env::temp_dir().join(format!("ov-clearnet-unit-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let file_path = dir.join(CLEARNET_ALLOWLIST_FILENAME);

        let t0: u64 = 1_700_000_000_000;
        let allowlist = ClearnetAllowlist::load_or_new_at(file_path.clone(), t0);

        // Add 3-day persistent rule + session-only rule + short 10s rule:
        allowlist.allow_at("*.tor.taxi", DEFAULT_CLEARNET_TTL_SECS as i64, t0);
        allowlist.allow_at("session.example", 0, t0);
        allowlist.allow_at("short.example", 10, t0);

        assert!(allowlist.is_allowed("tor.taxi", t0 + 1_000));
        assert!(allowlist.is_allowed("www.tor.taxi", t0 + 1_000));
        assert!(allowlist.is_allowed("session.example", t0 + 1_000));
        assert!(allowlist.is_allowed("short.example", t0 + 5_000));
        assert_eq!(allowlist.list_rules_at(t0 + 1_000).len(), 3);

        // Reload at t0 + 15s: `session.example` (in-memory only) and `short.example` (expired)
        // must be absent, while `*.tor.taxi` remains active for 3 days:
        let reloaded = ClearnetAllowlist::load_or_new_at(file_path.clone(), t0 + 15_000);
        let rules = reloaded.list_rules_at(t0 + 15_000);
        assert_eq!(rules.len(), 1);
        assert_eq!(rules[0].pattern, "*.tor.taxi");
        assert!(!rules[0].session_only);
        assert_eq!(
            rules[0].expires_at_ms,
            t0 + DEFAULT_CLEARNET_TTL_SECS * 1_000
        );
        assert!(reloaded.is_allowed("tor.taxi", t0 + 2 * 24 * 60 * 60 * 1_000));
        assert!(!reloaded.is_allowed("session.example", t0 + 15_000));
        assert!(!reloaded.is_allowed("short.example", t0 + 15_000));

        // After 3 days (`t0 + 259_200_000`), `*.tor.taxi` expires and is pruned automatically:
        assert!(!reloaded.is_allowed("tor.taxi", t0 + DEFAULT_CLEARNET_TTL_SECS * 1_000));
        assert!(reloaded
            .list_rules_at(t0 + DEFAULT_CLEARNET_TTL_SECS * 1_000)
            .is_empty());

        let _ = std::fs::remove_dir_all(&dir);
    }
}
