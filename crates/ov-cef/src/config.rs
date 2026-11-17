//! URL leak-prevention policy, clearnet consent evaluation, and proxy configuration for Trap.
//!
//! Enforces strict v3 `.onion` validation, blocks unsafe/local URL schemes and
//! loopback/private IP smuggling, normalizes bare `.onion` (`http://`) and bare
//! clearnet (`https://`) destinations while preserving `about:blank`, and
//! evaluates clearnet URLs against [`ov_tor::ClearnetAllowlist`].

use std::{
    net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
};

use ov_tor::clearnet::{now_epoch_ms, suggested_glob_for_host, ClearnetAllowlist};
use serde::{Deserialize, Serialize};

/// Length of the base32 service-ID label in a Tor v3 `.onion` address
/// (56 RFC 4648 lowercase base32 characters `[a-z2-7]`).
pub const V3_ONION_LABEL_LEN: usize = 56;

/// Zero-JavaScript HTML v3 `.onion` DuckDuckGo search endpoint used for all
/// non-URL address bar queries.
pub const DEFAULT_ONION_SEARCH_PREFIX: &str =
    "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/html/?q=";

/// Runtime network policy shared across the browser bridge and Tor service.
#[derive(Clone, Debug)]
pub struct OvConfig {
    /// Local loopback SOCKS5 proxy endpoint bound by `ov-tor`.
    pub proxy_endpoint: SocketAddr,
    /// When `true`, clearnet destinations are blocked unconditionally at both
    /// the browser request interceptor and the embedded SOCKS5 listener.
    /// Defaults to `false` (clearnet gated by `ClearnetAllowlist` consent).
    pub hidden_service_only: Arc<AtomicBool>,
    /// When `true`, SVG subresources are blocked.
    pub block_svg: bool,
}

impl Default for OvConfig {
    fn default() -> Self {
        Self {
            proxy_endpoint: SocketAddr::from(([127, 0, 0, 1], 9050)),
            hidden_service_only: Arc::new(AtomicBool::new(false)),
            block_svg: false,
        }
    }
}

impl OvConfig {
    /// `true` when clearnet traffic is unconditionally blocked.
    pub fn hidden_service_only(&self) -> bool {
        self.hidden_service_only.load(Ordering::Relaxed)
    }

    /// Update the clearnet/hidden-service-only policy at runtime.
    pub fn set_hidden_service_only(&self, hidden_service_only: bool) {
        self.hidden_service_only
            .store(hidden_service_only, Ordering::Relaxed);
    }

    /// `--proxy-server` value for Chromium.
    pub fn proxy_server(&self) -> String {
        format!("socks5://{}", self.proxy_endpoint)
    }

    /// `--host-resolver-rules` value preventing OS DNS leaks while allowing
    /// the loopback SOCKS5 endpoint.
    pub fn host_resolver_rules(&self) -> &'static str {
        "MAP * ~NOTFOUND , EXCLUDE 127.0.0.1"
    }
}

/// Structured result of evaluating a URL against Trap's navigation/request
/// policy and clearnet consent allowlist.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UrlPolicyDecision {
    /// `true` when the browser may load or request the URL immediately.
    pub allowed: bool,
    /// Normalized URL (`"about:blank"` preserved, bare `.onion` prefixed with
    /// `"http://"`, bare clearnet prefixed with `"https://"`).
    pub normalized_url: String,
    /// `true` when the destination host is a valid Tor v3 `.onion` service.
    pub is_onion: bool,
    /// `true` when navigation targets a valid clearnet HTTP(S) host while
    /// `hidden_service_only` is `false` and the host is not yet allowed in
    /// [`ClearnetAllowlist`].
    #[serde(default)]
    pub requires_clearnet_consent: bool,
    /// Canonical lowercase destination host (`""` for `about:blank` / `data:` / malformed URLs).
    #[serde(default)]
    pub host: String,
    /// Suggested consent glob pattern for `host` (e.g. `"*.tor.taxi"`), or `""` when N/A.
    #[serde(default)]
    pub suggested_pattern: String,
    /// Empty when `allowed == true`, otherwise the exact block or consent reason.
    pub reason: String,
}

impl UrlPolicyDecision {
    /// Serialize this decision to its JNI JSON contract.
    pub fn to_json(&self) -> String {
        serde_json::to_string(self).unwrap_or_else(|_| {
            r#"{"allowed":false,"normalizedUrl":"","isOnion":false,"requiresClearnetConsent":false,"host":"","suggestedPattern":"","reason":"URL policy serialization failed"}"#.to_string()
        })
    }
}

/// Normalize user-entered navigation input:
/// - Preserves `about:*`, `http://*`, and `https://*` (as well as explicit schemes
///   so `check_url_policy` can validate or reject them fail-closed).
/// - Treats bare input without whitespace as a domain/host URL only when it ends
///   with `.onion`, is `localhost` / IPv4 / `[IPv6]`, or has a valid hostname
///   before `/`/`:`/end containing at least one `.` and an alphabetic TLD of
///   `>= 2` chars (`http://` for `.onion`, `https://` for clearnet/host URLs).
/// - Routes all other non-empty inputs (single-word queries, multi-word queries,
///   queries with `?` or symbols) to DuckDuckGo's zero-JS HTML v3 `.onion`
///   endpoint (`DEFAULT_ONION_SEARCH_PREFIX` + RFC 3986 percent-encoded query).
pub fn normalize_navigation_url(raw: &str) -> String {
    let trimmed = raw.trim();
    if trimmed.is_empty() || trimmed.eq_ignore_ascii_case("about:blank") {
        return "about:blank".to_string();
    }
    let lower = trimmed.to_ascii_lowercase();
    if lower.starts_with("about:")
        || lower.starts_with("http://")
        || lower.starts_with("https://")
        || lower.starts_with("data:")
        || lower.starts_with("blob:")
        || lower.starts_with("javascript:")
        || lower.starts_with("vbscript:")
        || lower.starts_with("file:")
        || lower.starts_with("content:")
        || lower.starts_with("intent:")
        || lower.starts_with("mailto:")
        || lower.starts_with("tel:")
        || lower.starts_with("sms:")
        || lower.starts_with("geo:")
        || lower.starts_with("magnet:")
        || lower.starts_with("chrome:")
        || lower.starts_with("chrome-error:")
        || lower.starts_with("view-source:")
    {
        return trimmed.to_string();
    }

    if !trimmed.chars().any(char::is_whitespace) {
        if let Some((scheme_candidate, _)) = trimmed.split_once("://") {
            let valid_scheme = !scheme_candidate.is_empty()
                && scheme_candidate.as_bytes()[0].is_ascii_alphabetic()
                && scheme_candidate
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'+' | b'-' | b'.'));
            if valid_scheme {
                return trimmed.to_string();
            }
        }
    }

    if is_bare_domain_or_host(trimmed) {
        let authority = trimmed.split(['/', '?', '#']).next().unwrap_or(trimmed);
        let host_only = strip_port(authority).trim_end_matches('.');
        let host_lower = host_only.to_ascii_lowercase();
        if host_lower == "onion" || host_lower.ends_with(".onion") {
            return format!("http://{trimmed}");
        }
        return format!("https://{trimmed}");
    }

    format!(
        "{DEFAULT_ONION_SEARCH_PREFIX}{}",
        percent_encode_rfc3986(trimmed)
    )
}

/// RFC 3986 Section 2.3 percent-encoding (unreserved: `ALPHA / DIGIT / "-" / "." / "_" / "~"`).
pub fn percent_encode_rfc3986(input: &str) -> String {
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut out = String::with_capacity(input.len() + 8);
    for &b in input.as_bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'.' | b'_' | b'~') {
            out.push(b as char);
        } else {
            out.push('%');
            out.push(HEX[(b >> 4) as usize] as char);
            out.push(HEX[(b & 0x0f) as usize] as char);
        }
    }
    out
}

fn is_bare_domain_or_host(trimmed: &str) -> bool {
    if trimmed.is_empty()
        || trimmed
            .bytes()
            .any(|b| b.is_ascii_whitespace() || b <= 0x20 || b == 0x7f)
        || trimmed.chars().any(char::is_whitespace)
    {
        return false;
    }

    let authority = trimmed.split(['/', '?', '#']).next().unwrap_or("");
    if authority.is_empty() || authority.contains('@') {
        return false;
    }

    if let Some(after_open) = authority.strip_prefix('[') {
        let Some((ipv6_str, rest)) = after_open.split_once(']') else {
            return false;
        };
        if ipv6_str.parse::<Ipv6Addr>().is_err() {
            return false;
        }
        return match rest {
            "" => true,
            _ => rest.strip_prefix(':').is_some_and(is_valid_port),
        };
    }

    let host_candidate = match authority.split_once(':') {
        Some((h, port)) => {
            if !is_valid_port(port) {
                return false;
            }
            h
        }
        None => authority,
    };

    let host = host_candidate.trim_end_matches('.');
    if host.is_empty() {
        return false;
    }
    let host_lower = host.to_ascii_lowercase();

    if host_lower == "localhost" {
        return true;
    }

    if host.parse::<Ipv4Addr>().is_ok() {
        return true;
    }

    if host_lower == "onion" || host_lower.ends_with(".onion") {
        return host_lower
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'.' || b == b'-');
    }

    let labels: Vec<&str> = host_lower.split('.').collect();
    if labels.len() < 2 {
        return false;
    }
    let valid_labels = labels.iter().all(|label| {
        !label.is_empty()
            && label.len() <= 63
            && !label.starts_with('-')
            && !label.ends_with('-')
            && label
                .bytes()
                .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
    });
    if !valid_labels {
        return false;
    }
    let tld = labels[labels.len() - 1];
    (tld.len() >= 2 && tld.bytes().all(|b| b.is_ascii_lowercase()))
        || (tld.len() >= 5 && tld.starts_with("xn--"))
}

fn is_valid_port(port: &str) -> bool {
    !port.is_empty()
        && port.len() <= 5
        && port.bytes().all(|b| b.is_ascii_digit())
        && port.parse::<u16>().is_ok_and(|p| p >= 1)
}

/// Evaluate `url` against Trap's scheme, loopback-isolation, and
/// `hidden_service_only` policy (without consulting a clearnet consent list).
pub fn check_url_policy(url: &str, hidden_service_only: bool) -> UrlPolicyDecision {
    let normalized = normalize_navigation_url(url);
    let lower = normalized.to_ascii_lowercase();

    if lower == "about:blank" {
        return UrlPolicyDecision {
            allowed: true,
            normalized_url: "about:blank".to_string(),
            is_onion: false,
            requires_clearnet_consent: false,
            host: String::new(),
            suggested_pattern: String::new(),
            reason: String::new(),
        };
    }

    // Safe inline `data:` subresources (images, fonts, plain CSS) never leave
    // the device; executable/scripted schemes are always rejected.
    if let Some(rest) = lower.strip_prefix("data:") {
        let media_type = rest.split([',', ';']).next().unwrap_or("");
        if media_type.starts_with("image/")
            || media_type.starts_with("font/")
            || media_type == "text/css"
            || media_type == "text/plain"
        {
            return UrlPolicyDecision {
                allowed: true,
                normalized_url: normalized,
                is_onion: false,
                requires_clearnet_consent: false,
                host: String::new(),
                suggested_pattern: String::new(),
                reason: String::new(),
            };
        }
        return UrlPolicyDecision {
            allowed: false,
            normalized_url: normalized,
            is_onion: false,
            requires_clearnet_consent: false,
            host: String::new(),
            suggested_pattern: String::new(),
            reason: format!("blocked unsafe data URI media type '{media_type}'"),
        };
    }

    let Some((scheme, after_scheme)) = normalized.split_once("://") else {
        return UrlPolicyDecision {
            allowed: false,
            normalized_url: normalized,
            is_onion: false,
            requires_clearnet_consent: false,
            host: String::new(),
            suggested_pattern: String::new(),
            reason: "blocked unsupported or unsafe URL scheme".to_string(),
        };
    };

    let scheme_lower = scheme.to_ascii_lowercase();
    if scheme_lower != "http" && scheme_lower != "https" {
        return UrlPolicyDecision {
            allowed: false,
            normalized_url: normalized,
            is_onion: false,
            requires_clearnet_consent: false,
            host: String::new(),
            suggested_pattern: String::new(),
            reason: format!("blocked unsupported URL scheme '{scheme_lower}'"),
        };
    }

    let authority = after_scheme.split(['/', '?', '#']).next().unwrap_or("");
    let host_port = authority.rsplit('@').next().unwrap_or(authority);
    let host = strip_port(host_port).trim_end_matches('.');

    if host.is_empty() || host.bytes().any(|b| b <= 0x20 || b == 0x7f) {
        return UrlPolicyDecision {
            allowed: false,
            normalized_url: normalized,
            is_onion: false,
            requires_clearnet_consent: false,
            host: String::new(),
            suggested_pattern: String::new(),
            reason: "blocked malformed URL host".to_string(),
        };
    }

    let host_lower = host.to_ascii_lowercase();

    // Never allow web content to probe localhost, mDNS `.local`, RFC1918 private networks,
    // or link-local addresses on the user's device.
    if host_lower == "localhost"
        || host_lower.ends_with(".localhost")
        || host_lower.ends_with(".local")
        || is_non_public_ip(host)
    {
        return UrlPolicyDecision {
            allowed: false,
            normalized_url: normalized,
            is_onion: false,
            requires_clearnet_consent: false,
            host: host_lower,
            suggested_pattern: String::new(),
            reason: "blocked local or private network address".to_string(),
        };
    }

    if host_lower == "onion" || host_lower.ends_with(".onion") {
        if is_valid_v3_onion_host(&host_lower) {
            return UrlPolicyDecision {
                allowed: true,
                normalized_url: normalized,
                is_onion: true,
                requires_clearnet_consent: false,
                host: host_lower,
                suggested_pattern: String::new(),
                reason: String::new(),
            };
        }
        return UrlPolicyDecision {
            allowed: false,
            normalized_url: normalized,
            is_onion: false,
            requires_clearnet_consent: false,
            host: host_lower,
            suggested_pattern: String::new(),
            reason: "invalid Tor v3 .onion address (expected 56 base32 chars)".to_string(),
        };
    }

    let suggested = suggested_glob_for_host(&host_lower);

    if hidden_service_only {
        return UrlPolicyDecision {
            allowed: false,
            normalized_url: normalized,
            is_onion: false,
            requires_clearnet_consent: false,
            host: host_lower,
            suggested_pattern: suggested,
            reason: "blocked clearnet destination in hidden-service-only mode".to_string(),
        };
    }

    UrlPolicyDecision {
        allowed: true,
        normalized_url: normalized,
        is_onion: false,
        requires_clearnet_consent: false,
        host: host_lower,
        suggested_pattern: suggested,
        reason: String::new(),
    }
}

/// Evaluate `url` against Trap's URL policy AND `clearnet_allowlist`:
/// - Valid `.onion` v3 URLs, `about:blank`, and safe `data:` URIs are allowed immediately.
/// - When `hidden_service_only == true`, clearnet URLs are blocked (`requires_clearnet_consent: false`).
/// - When `hidden_service_only == false` and `url` is a public `http`/`https` clearnet URL:
///   - If `clearnet_allowlist.is_allowed(&host, now_ms)` is `true` => `allowed: true, requires_clearnet_consent: false`.
///   - Otherwise (even if previously in `session_denied`, since this is an explicit navigation check) =>
///     `allowed: false, requires_clearnet_consent: true, host, suggested_pattern`.
pub fn check_url_policy_with_clearnet(
    url: &str,
    hidden_service_only: bool,
    clearnet_allowlist: &ClearnetAllowlist,
) -> UrlPolicyDecision {
    check_url_policy_with_clearnet_at(url, hidden_service_only, clearnet_allowlist, now_epoch_ms())
}

/// Deterministic clock variant of [`check_url_policy_with_clearnet`].
pub fn check_url_policy_with_clearnet_at(
    url: &str,
    hidden_service_only: bool,
    clearnet_allowlist: &ClearnetAllowlist,
    now_ms: u64,
) -> UrlPolicyDecision {
    let mut decision = check_url_policy(url, hidden_service_only);
    if decision.allowed && !decision.is_onion && !decision.host.is_empty() {
        if clearnet_allowlist.is_allowed(&decision.host, now_ms) {
            decision.allowed = true;
            decision.requires_clearnet_consent = false;
            decision.reason.clear();
        } else {
            decision.allowed = false;
            decision.requires_clearnet_consent = true;
            decision.reason = format!("Clearnet consent required for {}", decision.host);
        }
    }
    decision
}

/// Validate a Tor v3 `.onion` hostname (`[<subdomains>.]<56-base32>.onion`).
pub fn is_valid_v3_onion_host(host: &str) -> bool {
    let trimmed = host.trim().trim_end_matches('.').to_ascii_lowercase();
    let Some(without_suffix) = trimmed.strip_suffix(".onion") else {
        return false;
    };
    if without_suffix.is_empty() {
        return false;
    }
    let labels: Vec<&str> = without_suffix.split('.').collect();
    let Some((&service_label, subdomains)) = labels.split_last() else {
        return false;
    };
    if service_label.len() != V3_ONION_LABEL_LEN
        || !service_label
            .bytes()
            .all(|b| matches!(b, b'a'..=b'z' | b'2'..=b'7'))
    {
        return false;
    }
    subdomains.iter().all(|sub| {
        !sub.is_empty()
            && sub.len() <= 63
            && !sub.starts_with('-')
            && !sub.ends_with('-')
            && sub
                .bytes()
                .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
    })
}

fn strip_port(host_port: &str) -> &str {
    if let Some(bracketed) = host_port.strip_prefix('[') {
        if let Some((ipv6, _rest)) = bracketed.split_once(']') {
            return ipv6;
        }
        return host_port;
    }
    match host_port.rsplit_once(':') {
        Some((h, port)) if !port.is_empty() && port.bytes().all(|b| b.is_ascii_digit()) => h,
        _ => host_port,
    }
}

fn is_non_public_ip(host: &str) -> bool {
    let candidate = host.trim_matches(['[', ']']);
    let Ok(ip) = candidate.parse::<IpAddr>() else {
        return false;
    };
    match ip {
        IpAddr::V4(v4) => {
            v4.is_loopback()
                || v4.is_private()
                || v4.is_link_local()
                || v4.is_unspecified()
                || v4.is_broadcast()
                || v4.is_multicast()
                || (v4.octets()[0] == 100 && (v4.octets()[1] & 0b1100_0000) == 64)
        }
        IpAddr::V6(v6) => {
            v6.is_loopback()
                || v6.is_unspecified()
                || v6.is_multicast()
                || (v6.segments()[0] & 0xfe00) == 0xfc00
                || (v6.segments()[0] & 0xffc0) == 0xfe80
                || v6.to_ipv4_mapped().is_some_and(|v4| {
                    v4.is_loopback() || v4.is_private() || v4.is_link_local() || v4.is_unspecified()
                })
        }
    }
}

/// `true` when the URL may be loaded in hidden-service-only mode.
pub fn is_allowed_url(url: &str) -> bool {
    check_url_policy(url, true).allowed
}

/// `true` when the URL points at an SVG resource.
pub fn is_svg_url(url: &str) -> bool {
    let lower = url.trim().to_ascii_lowercase();
    if lower.starts_with("data:image/svg+xml") {
        return true;
    }
    let path = lower.split(['?', '#']).next().unwrap_or("");
    path.ends_with(".svg")
}

#[cfg(test)]
mod tests {
    use super::*;
    use ov_tor::DEFAULT_CLEARNET_TTL_SECS;

    const DDG_V3: &str = "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/";

    #[test]
    fn default_ov_config_enables_clearnet_consent_mode() {
        let cfg = OvConfig::default();
        assert!(!cfg.hidden_service_only());
    }

    #[test]
    fn preserves_about_blank_and_normalizes_bare_onion_and_bare_clearnet() {
        let blank = check_url_policy("about:blank", true);
        assert!(blank.allowed);
        assert!(!blank.is_onion);
        assert_eq!(blank.normalized_url, "about:blank");

        let bare_onion = check_url_policy(
            "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/lite",
            true,
        );
        assert!(bare_onion.allowed);
        assert!(bare_onion.is_onion);
        assert_eq!(
            bare_onion.normalized_url,
            "http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/lite"
        );

        let bare_clearnet = check_url_policy("tor.taxi", false);
        assert_eq!(bare_clearnet.normalized_url, "https://tor.taxi");
        assert_eq!(bare_clearnet.host, "tor.taxi");
        assert_eq!(bare_clearnet.suggested_pattern, "*.tor.taxi");

        let bare_clearnet_path = check_url_policy("tor.taxi/foo?bar=1", false);
        assert_eq!(
            bare_clearnet_path.normalized_url,
            "https://tor.taxi/foo?bar=1"
        );
        assert_eq!(bare_clearnet_path.host, "tor.taxi");
    }

    #[test]
    fn blocks_clearnet_loopback_smuggling_and_malformed_onions() {
        assert!(check_url_policy(DDG_V3, true).allowed);
        assert!(!check_url_policy("https://example.com/", true).allowed);
        assert!(check_url_policy("https://example.com/", false).allowed);

        // Regression tests for Mavis's `starts_with("http://127.0.0.1")` bypass:
        assert!(!check_url_policy("http://127.0.0.1.evil.com/", true).allowed);
        assert!(!check_url_policy("http://127.0.0.1:8080/", true).allowed);
        assert!(!check_url_policy("http://127.0.0.1:8080/", false).allowed);
        assert!(!check_url_policy("http://localhost:9050/", false).allowed);
        assert!(!check_url_policy("http://192.168.1.1/", false).allowed);
        assert!(!check_url_policy("http://[::1]:8080/", false).allowed);

        // Malformed / v2 `.onion` addresses must fail closed:
        assert!(!check_url_policy("http://.onion/", true).allowed);
        assert!(!check_url_policy("http://3g2upl4pq6kufc4m.onion/", true).allowed);
        assert!(!check_url_policy("javascript:alert(1)", false).allowed);
        assert!(!check_url_policy("mailto:user@example.com", false).allowed);
        assert!(!check_url_policy("file:///data/data/dev.onionvault/keyring.db", false).allowed);
    }

    #[test]
    fn clearnet_policy_requires_consent_until_allowed_even_after_session_denial() {
        let allowlist = ClearnetAllowlist::in_memory();
        let t0 = 1_700_000_000_000u64;

        // Unapproved bare `tor.taxi` normalizes to `https://tor.taxi` and requires consent:
        let initial = check_url_policy_with_clearnet_at("tor.taxi", false, &allowlist, t0);
        assert!(!initial.allowed);
        assert!(!initial.is_onion);
        assert!(initial.requires_clearnet_consent);
        assert_eq!(initial.normalized_url, "https://tor.taxi");
        assert_eq!(initial.host, "tor.taxi");
        assert_eq!(initial.suggested_pattern, "*.tor.taxi");

        // Even if previously session-denied, explicit user navigation still prompts consent:
        allowlist.deny("tor.taxi");
        let after_deny = check_url_policy_with_clearnet_at("tor.taxi/dir", false, &allowlist, t0);
        assert!(!after_deny.allowed);
        assert!(after_deny.requires_clearnet_consent);
        assert_eq!(after_deny.normalized_url, "https://tor.taxi/dir");
        assert_eq!(after_deny.host, "tor.taxi");
        assert_eq!(after_deny.suggested_pattern, "*.tor.taxi");

        // Strict hidden_service_only mode hard-blocks without consent prompt:
        let strict = check_url_policy_with_clearnet_at("tor.taxi", true, &allowlist, t0);
        assert!(!strict.allowed);
        assert!(!strict.requires_clearnet_consent);

        // Approving `*.tor.taxi` for 3 days allows both `tor.taxi` and `www.tor.taxi` in 1 click:
        allowlist.allow_at("*.tor.taxi", DEFAULT_CLEARNET_TTL_SECS as i64, t0);
        let root_ok = check_url_policy_with_clearnet_at("tor.taxi", false, &allowlist, t0 + 1_000);
        assert!(root_ok.allowed);
        assert!(!root_ok.requires_clearnet_consent);
        assert_eq!(root_ok.normalized_url, "https://tor.taxi");

        let sub_ok = check_url_policy_with_clearnet_at(
            "https://www.tor.taxi/",
            false,
            &allowlist,
            t0 + 1_000,
        );
        assert!(sub_ok.allowed);
        assert!(!sub_ok.requires_clearnet_consent);
    }

    #[test]
    fn routes_non_url_queries_to_duckduckgo_v3_onion_html_endpoint_without_clearnet_consent() {
        let allowlist = ClearnetAllowlist::in_memory();

        // Single-word search terms route to DuckDuckGo v3 `.onion` zero-JS HTML search:
        for (query, expected_encoded) in [
            ("dread", "dread"),
            ("securedrop", "securedrop"),
            ("whistleblower drop", "whistleblower%20drop"),
            ("privacy news", "privacy%20news"),
            ("how to verify pgp?", "how%20to%20verify%20pgp%3F"),
            ("?q=onion+directory", "%3Fq%3Donion%2Bdirectory"),
            ("c++ & rust_lang~1.0", "c%2B%2B%20%26%20rust_lang~1.0"),
            ("site:propublica.org", "site%3Apropublica.org"),
            ("v0.1.32", "v0.1.32"),
        ] {
            let expected_url = format!("{DEFAULT_ONION_SEARCH_PREFIX}{expected_encoded}");
            assert_eq!(normalize_navigation_url(query), expected_url);

            // Allowed immediately over Tor without clearnet consent, even in hidden-service-only mode:
            let decision_default = check_url_policy_with_clearnet(query, false, &allowlist);
            assert!(decision_default.allowed, "failed for query={query}");
            assert!(
                decision_default.is_onion,
                "expected onion for query={query}"
            );
            assert!(
                !decision_default.requires_clearnet_consent,
                "must not prompt clearnet consent for query={query}"
            );
            assert_eq!(decision_default.normalized_url, expected_url);

            let decision_strict = check_url_policy_with_clearnet(query, true, &allowlist);
            assert!(
                decision_strict.allowed,
                "strict mode failed for query={query}"
            );
            assert!(decision_strict.is_onion);
            assert!(!decision_strict.requires_clearnet_consent);
        }

        // Bare domains, `.onion` hosts, localhost, IPv4, and `[IPv6]` remain host navigations:
        assert_eq!(normalize_navigation_url("tor.taxi"), "https://tor.taxi");
        assert_eq!(
            normalize_navigation_url("www.tor.taxi/foo?bar=1"),
            "https://www.tor.taxi/foo?bar=1"
        );
        assert_eq!(
            normalize_navigation_url("tor.taxi:8443/dir"),
            "https://tor.taxi:8443/dir"
        );
        assert_eq!(
            normalize_navigation_url(
                "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"
            ),
            "http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"
        );
        assert_eq!(
            normalize_navigation_url("localhost:9050"),
            "https://localhost:9050"
        );
        assert_eq!(
            normalize_navigation_url("127.0.0.1:8080"),
            "https://127.0.0.1:8080"
        );
        assert_eq!(normalize_navigation_url("[::1]:8080"), "https://[::1]:8080");
        assert_eq!(normalize_navigation_url("about:srcdoc"), "about:srcdoc");
        assert_eq!(
            normalize_navigation_url("http://example.com/a"),
            "http://example.com/a"
        );
        assert_eq!(
            normalize_navigation_url("https://example.com/a"),
            "https://example.com/a"
        );
    }
}
