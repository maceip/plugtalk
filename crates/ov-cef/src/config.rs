//! URL leak-prevention policy and proxy configuration for Trap.
//!
//! Enforces strict v3 `.onion` validation, blocks unsafe/local URL schemes and
//! loopback/private IP smuggling, and normalizes user-entered `.onion`
//! destinations while preserving `about:blank`.

use std::{
    net::{IpAddr, SocketAddr},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
};

use serde::{Deserialize, Serialize};

/// Length of the base32 service-ID label in a Tor v3 `.onion` address
/// (56 RFC 4648 lowercase base32 characters `[a-z2-7]`).
pub const V3_ONION_LABEL_LEN: usize = 56;

/// Runtime network policy shared across the browser bridge and Tor service.
#[derive(Clone, Debug)]
pub struct OvConfig {
    /// Local loopback SOCKS5 proxy endpoint bound by `ov-tor`.
    pub proxy_endpoint: SocketAddr,
    /// When `true`, clearnet destinations are blocked at both the browser
    /// request interceptor and the embedded SOCKS5 listener.
    pub hidden_service_only: Arc<AtomicBool>,
    /// When `true`, SVG subresources are blocked.
    pub block_svg: bool,
}

impl Default for OvConfig {
    fn default() -> Self {
        Self {
            proxy_endpoint: SocketAddr::from(([127, 0, 0, 1], 9050)),
            hidden_service_only: Arc::new(AtomicBool::new(true)),
            block_svg: false,
        }
    }
}

impl OvConfig {
    /// `true` when clearnet traffic is blocked.
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
/// policy.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UrlPolicyDecision {
    /// `true` when the browser may load or request the URL.
    pub allowed: bool,
    /// Empty when `allowed == true`, otherwise the exact block reason.
    pub reason: String,
    /// `true` when the destination host is a valid Tor v3 `.onion` service.
    pub is_onion: bool,
    /// Normalized URL (`"about:blank"` preserved, bare `.onion` prefixed with
    /// `"http://"`).
    pub normalized_url: String,
}

/// Normalize user-entered navigation input while preserving `about:blank`.
pub fn normalize_navigation_url(raw: &str) -> String {
    let trimmed = raw.trim();
    if trimmed.is_empty() || trimmed.eq_ignore_ascii_case("about:blank") {
        return "about:blank".to_string();
    }
    let lower = trimmed.to_ascii_lowercase();
    if lower.starts_with("about:")
        || lower.starts_with("data:")
        || lower.starts_with("blob:")
        || trimmed.contains("://")
        || lower.starts_with("javascript:")
        || lower.starts_with("file:")
        || lower.starts_with("content:")
        || lower.starts_with("intent:")
    {
        return trimmed.to_string();
    }

    // Bare hostname/path entered into the address bar: check whether its host
    // is a `.onion` destination (default to `http://` since most hidden
    // services speak HTTP over Tor's authenticated v3 circuit) or clearnet
    // (`https://`).
    let host_part = trimmed.split(['/', '?', '#']).next().unwrap_or(trimmed);
    let host_only = strip_port(host_part);
    if host_only.to_ascii_lowercase().ends_with(".onion") {
        format!("http://{trimmed}")
    } else {
        format!("https://{trimmed}")
    }
}

/// Evaluate `url` against Trap's scheme, loopback-isolation, and
/// `hidden_service_only` policy.
pub fn check_url_policy(url: &str, hidden_service_only: bool) -> UrlPolicyDecision {
    let normalized = normalize_navigation_url(url);
    let lower = normalized.to_ascii_lowercase();

    if lower == "about:blank" {
        return UrlPolicyDecision {
            allowed: true,
            reason: String::new(),
            is_onion: false,
            normalized_url: "about:blank".to_string(),
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
                reason: String::new(),
                is_onion: false,
                normalized_url: normalized,
            };
        }
        return UrlPolicyDecision {
            allowed: false,
            reason: format!("blocked unsafe data URI media type '{media_type}'"),
            is_onion: false,
            normalized_url: normalized,
        };
    }

    let Some((scheme, after_scheme)) = normalized.split_once("://") else {
        return UrlPolicyDecision {
            allowed: false,
            reason: "blocked unsupported or unsafe URL scheme".to_string(),
            is_onion: false,
            normalized_url: normalized,
        };
    };

    let scheme_lower = scheme.to_ascii_lowercase();
    if scheme_lower != "http" && scheme_lower != "https" {
        return UrlPolicyDecision {
            allowed: false,
            reason: format!("blocked unsupported URL scheme '{scheme_lower}'"),
            is_onion: false,
            normalized_url: normalized,
        };
    }

    let authority = after_scheme.split(['/', '?', '#']).next().unwrap_or("");
    let host_port = authority.rsplit('@').next().unwrap_or(authority);
    let host = strip_port(host_port).trim_end_matches('.');

    if host.is_empty() || host.bytes().any(|b| b <= 0x20 || b == 0x7f) {
        return UrlPolicyDecision {
            allowed: false,
            reason: "blocked malformed URL host".to_string(),
            is_onion: false,
            normalized_url: normalized,
        };
    }

    let host_lower = host.to_ascii_lowercase();

    // Never allow web content to probe localhost, RFC1918 private networks, or
    // link-local addresses on the user's device.
    if host_lower == "localhost" || host_lower.ends_with(".localhost") || is_non_public_ip(host) {
        return UrlPolicyDecision {
            allowed: false,
            reason: "blocked local or private network address".to_string(),
            is_onion: false,
            normalized_url: normalized,
        };
    }

    if host_lower == "onion" || host_lower.ends_with(".onion") {
        if is_valid_v3_onion_host(&host_lower) {
            return UrlPolicyDecision {
                allowed: true,
                reason: String::new(),
                is_onion: true,
                normalized_url: normalized,
            };
        }
        return UrlPolicyDecision {
            allowed: false,
            reason: "invalid Tor v3 .onion address (expected 56 base32 chars)".to_string(),
            is_onion: false,
            normalized_url: normalized,
        };
    }

    if hidden_service_only {
        return UrlPolicyDecision {
            allowed: false,
            reason: "blocked clearnet destination in hidden-service-only mode".to_string(),
            is_onion: false,
            normalized_url: normalized,
        };
    }

    UrlPolicyDecision {
        allowed: true,
        reason: String::new(),
        is_onion: false,
        normalized_url: normalized,
    }
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

    const DDG_V3: &str = "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/";

    #[test]
    fn preserves_about_blank_and_normalizes_bare_v3_onion() {
        let blank = check_url_policy("about:blank", true);
        assert!(blank.allowed);
        assert!(!blank.is_onion);
        assert_eq!(blank.normalized_url, "about:blank");

        let bare = check_url_policy(
            "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/lite",
            true,
        );
        assert!(bare.allowed);
        assert!(bare.is_onion);
        assert_eq!(
            bare.normalized_url,
            "http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/lite"
        );
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
        assert!(!check_url_policy("file:///data/data/dev.onionvault/keyring.db", false).allowed);
    }
}
