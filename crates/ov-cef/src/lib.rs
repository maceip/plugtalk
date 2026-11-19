//! Pure-Rust URL leak policy, HTML/CDP DOM PGP classifier, and snapshot ring
//! for Trap.
//!
//! This crate has **zero `libcef.so` linkage** and never executes JavaScript:
//!
//!   * [`cdp`] — Chrome DevTools Protocol (`DOM.getDocument` with
//!     `pierce: true`) JSON walker & classifier, including Blink UA
//!     `#inner-editor` live `<input>`/`<textarea>` value extraction,
//!   * [`dom_model`] — standalone HTML parser, PGP field/block classifier,
//!     deterministic synthetic node IDs, and unique CSS selectors,
//!   * [`config`] — Tor SOCKS5 proxy config and strict v3 `.onion` URL leak
//!     policy,
//!   * [`ipc`] — JSON-serializable DOM field metadata and PGP request/response
//!     schemas, and
//!   * [`snapshot`] — bounded back-stack snapshot collector.

pub mod bridge_types;
pub mod cdp;
pub mod config;
pub mod crypto_addr;
pub mod dom_model;
pub mod frame_sink;
pub mod ipc;
pub mod snapshot;

pub use bridge_types::{pending_actions, BrowserBridge, NullBridge, PendingActions};
pub use cdp::{
    classify_cdp_dom_json, classify_cdp_dom_with_hints, classify_text, classify_text_with_hints,
    live_form_control_value, parse_cdp_root, CdpClassifiedNode, CdpNode, ClassifiedText,
};
pub use config::{
    check_url_policy, check_url_policy_with_clearnet, check_url_policy_with_clearnet_at,
    is_allowed_url, is_svg_url, is_valid_v3_onion_host, normalize_navigation_url, OvConfig,
    UrlPolicyDecision, V3_ONION_LABEL_LEN,
};
pub use crypto_addr::{
    extract_monero_address, parse_bitcoin_uri, validate_btc_address, validate_btc_mainnet_address,
    validate_monero_uri_str,
};
pub use dom_model::{
    analyze_document, analyze_html, classify, css_escape_ident, decode_html_entities,
    document_title, extract_armored_block, merge_inline_style, parse_html, query_selector, scan,
    scan_html, synthetic_id, DetectedField, HtmlNode, WriteTarget,
};
pub use frame_sink::{FrameSink, PaintBuffer, PixelFormat};
pub use ipc::{
    DecryptRequest, DecryptResponse, DomFieldMetadata, DomScanReport, FieldAction, FieldKind,
    FieldValue, MutationRequest, MutationResult, NavigationInfo, NodeRect,
};
pub use snapshot::{FrameSnapshot, PageSnapshot, SnapshotCollector, MAX_DEPTH};
