//! Circuit isolation for hidden services.
//!
//! Tor's stream isolation guarantees that two different hidden service
//! destinations never share a circuit.  Arti implements this with both
//! [`TorClient::isolated_client`] and per-stream [`IsolationToken`]s.
//!
//! Trap isolates per *service key* of a v3 `.onion` address: the 56-character
//! RFC 4648 base32 service label immediately preceding `.onion` identifies the
//! Ed25519 hidden service, and its first 16 base32 characters form the
//! deterministic service isolation prefix (`iso-<16char_apex>`).

use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
};

use arti_client::{IsolationToken, TorClient};
use tor_rtcompat::PreferredRuntime;

/// Full length of a Tor v3 `.onion` base32 service label.
pub const V3_ONION_LABEL_LEN: usize = 56;

/// Prefix length of a v3 `.onion` service label used as the circuit isolation key.
pub const V3_APEX_LEN: usize = 16;

/// Active isolated circuit group owned by [`IsolationRegistry`].
#[derive(Clone)]
pub struct IsolationGroup {
    /// Isolation key (16-char base32 v3 service prefix or RFC 1929 SOCKS username).
    pub key: String,
    /// Human-readable isolation token surfaced to native UI (`iso-<key>`).
    pub label: String,
    /// Cryptographic stream isolation token passed to Arti `StreamPrefs`.
    pub token: IsolationToken,
    /// Isolated Arti `TorClient` owning dedicated circuits.
    pub client: Arc<TorClient<PreferredRuntime>>,
}

/// Keeps one isolated [`TorClient`] + [`IsolationToken`] per isolation key.
pub struct IsolationRegistry {
    base: Arc<TorClient<PreferredRuntime>>,
    groups: Mutex<HashMap<String, IsolationGroup>>,
    isolate: bool,
}

impl IsolationRegistry {
    /// Create a registry on top of the main unisolated `TorClient`.
    pub fn new(base: Arc<TorClient<PreferredRuntime>>, isolate: bool) -> Self {
        Self {
            base,
            groups: Mutex::new(HashMap::new()),
            isolate,
        }
    }

    fn lock_groups(&self) -> std::sync::MutexGuard<'_, HashMap<String, IsolationGroup>> {
        match self.groups.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        }
    }

    /// Base client used when isolation is disabled or no key applies.
    pub fn base_client(&self) -> Arc<TorClient<PreferredRuntime>> {
        self.base.clone()
    }

    /// Synchronous lookup/allocation of an isolated [`IsolationGroup`] for `key`.
    pub fn group_for_sync(&self, key: &str) -> Option<IsolationGroup> {
        if !self.isolate || key.is_empty() {
            return None;
        }

        let mut groups = self.lock_groups();
        if let Some(existing) = groups.get(key) {
            return Some(existing.clone());
        }

        let group = IsolationGroup {
            key: key.to_string(),
            label: format!("iso-{key}"),
            token: IsolationToken::new(),
            client: self.base.isolated_client(),
        };
        groups.insert(key.to_string(), group.clone());
        log::info!("created isolated Tor circuit group {}", group.label);
        Some(group)
    }

    /// Return the isolated `(TorClient, Option<IsolationToken>)` for `key`.
    pub fn client_and_token_for(
        &self,
        key: &str,
    ) -> (Arc<TorClient<PreferredRuntime>>, Option<IsolationToken>) {
        match self.group_for_sync(key) {
            Some(group) => (group.client, Some(group.token)),
            None => (self.base.clone(), None),
        }
    }

    /// Return the `TorClient` for `key`, creating an isolated one on first use.
    pub async fn client_for(&self, key: &str) -> Arc<TorClient<PreferredRuntime>> {
        self.client_and_token_for(key).0
    }

    /// Synchronous count of currently active isolated circuit groups.
    pub fn group_count_sync(&self) -> usize {
        self.lock_groups().len()
    }

    /// Async count of currently active isolated circuit groups.
    pub async fn group_count(&self) -> usize {
        self.group_count_sync()
    }

    /// Return the UI isolation token (`"iso-<16char_apex>"`) for a valid v3
    /// `.onion` URL or host, or `""` for clearnet / `about:blank` / invalid URLs.
    pub fn isolation_token_for_url(&self, current_url: &str) -> String {
        if !self.isolate {
            return String::new();
        }
        let Some(host) = extract_host(current_url) else {
            return String::new();
        };
        let Some(apex) = apex_domain(&host) else {
            return String::new();
        };
        let groups = self.lock_groups();
        if let Some(existing) = groups.get(&apex) {
            return existing.label.clone();
        }
        format!("iso-{apex}")
    }

    /// Evict isolated circuit group(s) so subsequent connections allocate a
    /// fresh `TorClient::isolated_client()` and a fresh `IsolationToken`.
    ///
    /// - When `target_url_or_empty` is empty, evicts ALL active isolated groups
    ///   (`"New Identity"`) and returns the number of evicted groups.
    /// - Otherwise evicts the group matching `target_url_or_empty` (either a
    ///   full `.onion` URL, `.onion` host, or raw isolation key) and returns
    ///   `1` if an active group was evicted (`0` otherwise).
    pub fn reset_isolation(&self, target_url_or_empty: &str) -> usize {
        let trimmed = target_url_or_empty.trim();
        let mut groups = self.lock_groups();
        if trimmed.is_empty() {
            let evicted = groups.len();
            groups.clear();
            log::info!("reset all {evicted} isolated Tor circuit groups");
            return evicted;
        }

        let resolved_key = extract_host(trimmed)
            .and_then(|h| apex_domain(&h))
            .or_else(|| apex_domain(trimmed))
            .unwrap_or_else(|| trimmed.strip_prefix("iso-").unwrap_or(trimmed).to_string());

        if groups.remove(&resolved_key).is_some() {
            log::info!("reset isolated Tor circuit group iso-{resolved_key}");
            1
        } else {
            0
        }
    }

    /// Extract the 16-char base32 service apex of a valid v3 `.onion` host.
    pub fn apex_domain(&self, host: &str) -> Option<String> {
        apex_domain(host)
    }
}

/// Returns `true` iff `host` is a syntactically valid Tor v3 `.onion` hostname
/// (`[sub.]*<56 base32 chars>.onion[.]`).
pub fn is_valid_v3_onion_host(host: &str) -> bool {
    apex_domain(host).is_some()
}

/// Extract the 16-character RFC 4648 base32 service prefix of a valid v3
/// `.onion` hostname.  Returns `None` for clearnet domains, IP addresses,
/// deprecated v2/short `.onion` names, and malformed/non-ASCII inputs.
pub fn apex_domain(host: &str) -> Option<String> {
    let trimmed = host.trim().trim_end_matches('.');
    if trimmed.is_empty() || !trimmed.is_ascii() {
        return None;
    }

    let lower = trimmed.to_ascii_lowercase();
    let base = lower.strip_suffix(".onion")?;
    if base.is_empty() {
        return None;
    }

    let labels: Vec<&str> = base.split('.').collect();
    let service_label = *labels.last()?;
    if service_label.len() != V3_ONION_LABEL_LEN || !is_rfc4648_base32_lower(service_label) {
        return None;
    }

    for &sub in &labels[..labels.len() - 1] {
        if !is_valid_dns_subdomain_label(sub) {
            return None;
        }
    }

    Some(service_label[..V3_APEX_LEN].to_string())
}

/// Extract the hostname component from a URL (`http://host:port/path`) or bare
/// `host[:port]` string.
pub fn extract_host(url_or_host: &str) -> Option<String> {
    let trimmed = url_or_host.trim();
    if trimmed.is_empty() {
        return None;
    }

    let lower = trimmed.to_ascii_lowercase();
    if lower.starts_with("about:")
        || lower.starts_with("javascript:")
        || lower.starts_with("data:")
        || lower.starts_with("blob:")
        || lower.starts_with("file:")
    {
        return None;
    }

    let after_scheme = if let Some((scheme, rest)) = trimmed.split_once("://") {
        if scheme.is_empty() || !scheme.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'+' || b == b'-' || b == b'.') {
            return None;
        }
        rest
    } else if trimmed.contains(':') && !trimmed.starts_with('[') {
        // Handle `host:port/path` vs unknown `scheme:opaque`.
        let (head, tail) = trimmed.split_once(':')?;
        let port_part = tail.split(['/', '?', '#']).next().unwrap_or("");
        if port_part.is_empty() || !port_part.bytes().all(|b| b.is_ascii_digit()) {
            return None;
        }
        let _ = head;
        trimmed
    } else {
        trimmed
    };

    let authority = after_scheme.split(['/', '?', '#']).next()?;
    let host_port = authority
        .rsplit_once('@')
        .map(|(_, hp)| hp)
        .unwrap_or(authority);

    if host_port.is_empty() {
        return None;
    }

    if let Some(rest) = host_port.strip_prefix('[') {
        let (ipv6, after_bracket) = rest.split_once(']')?;
        if !after_bracket.is_empty() && !after_bracket.starts_with(':') {
            return None;
        }
        return Some(ipv6.to_string());
    }

    let host = match host_port.rsplit_once(':') {
        Some((h, port)) if !port.is_empty() && port.bytes().all(|b| b.is_ascii_digit()) => h,
        Some(_) => return None,
        None => host_port,
    };

    if host.is_empty() {
        None
    } else {
        Some(host.to_string())
    }
}

fn is_rfc4648_base32_lower(s: &str) -> bool {
    s.bytes().all(|b| matches!(b, b'a'..=b'z' | b'2'..=b'7'))
}

fn is_valid_dns_subdomain_label(label: &str) -> bool {
    !label.is_empty()
        && label.len() <= 63
        && !label.starts_with('-')
        && !label.ends_with('-')
        && label
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
}

#[cfg(test)]
mod tests {
    use super::*;

    const TOR_V3: &str = "2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion";
    const DDG_V3: &str = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion";

    #[test]
    fn v3_apex_is_the_first_sixteen_characters_of_service_label() {
        assert_eq!(apex_domain(TOR_V3).as_deref(), Some("2gzyxa5ihm7nsggf"));
        assert_eq!(
            apex_domain(&format!("mail.sub.{TOR_V3}")).as_deref(),
            Some("2gzyxa5ihm7nsggf")
        );
        assert_ne!(
            apex_domain(&format!("cdn.{TOR_V3}")),
            apex_domain(&format!("cdn.{DDG_V3}")),
            "subdomains of distinct hidden services must never share an apex key"
        );
    }

    #[test]
    fn trailing_dot_and_case_are_normalised() {
        assert_eq!(
            apex_domain("2GZYXA5IHM7NSGGFXNU52RCK2VV4RVMDLKIU3ZZUI5DU4XYCLEN53WID.ONION.")
                .as_deref(),
            Some("2gzyxa5ihm7nsggf"),
        );
    }

    #[test]
    fn rejects_clearnet_short_onion_non_base32_and_utf8_without_panicking() {
        assert_eq!(apex_domain("example.com"), None);
        assert_eq!(apex_domain("onion.example.com"), None);
        assert_eq!(apex_domain("shortv2address16.onion"), None);
        assert_eq!(apex_domain("127.0.0.1.onion"), None);
        // '0', '1', '8', '9' are invalid in RFC 4648 base32:
        assert_eq!(
            apex_domain("00000000000000000000000000000000000000000000000000000000.onion"),
            None
        );
        // Multi-byte UTF-8 input must never panic on byte slicing:
        assert_eq!(apex_domain("éééééééééééééééééééééééééééé.onion"), None);
    }

    #[test]
    fn extracts_hosts_from_urls_and_rejects_about_blank() {
        assert_eq!(
            extract_host(&format!("http://{TOR_V3}:8080/compose?x=1")).as_deref(),
            Some(TOR_V3)
        );
        assert_eq!(extract_host("about:blank"), None);
        assert_eq!(extract_host(""), None);
    }
}
