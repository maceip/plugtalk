//! The DOM PGP classifier, snapshot ring, and optional CEF bindings of Trap.
//!
//! By default (`default = []`), this crate compiles completely standalone with
//! **zero `libcef.so` linkage**, exposing:
//!
//!   * [`dom_model`] — standalone HTML parser, PGP textarea/input/message/key
//!     classifier, deterministic synthetic node IDs, and unique CSS selectors
//!     for browser-process `DOM.*` anchoring (`DOM.getOuterHTML` +
//!     `DOM.querySelector` + `DOM.getBoxModel`),
//!   * [`config`] — Tor SOCKS5 proxy endpoint and hidden-service-only URL
//!     leak-prevention policy,
//!   * [`ipc`] — JSON-serializable DOM field metadata, scan reports, and
//!     encrypt/decrypt/mutation request/response types,
//!   * [`snapshot`] — bounded back-stack snapshot collector and RGBA/BGRA
//!     frame downsampler, and
//!   * [`frame_sink`] / [`bridge_types`] — platform surface and bridge traits.
//!
//! Enabling the `cef-runtime` feature additionally compiles the legacy C-API
//! `cef-rs` wrappers (`app`, `dom_pgp`, `handlers`, `keyboard`).

#[cfg(feature = "cef-runtime")]
pub mod app;
pub mod bridge_types;
pub mod config;
pub mod dom_model;
#[cfg(feature = "cef-runtime")]
pub mod dom_pgp;
pub mod frame_sink;
#[cfg(feature = "cef-runtime")]
pub mod handlers;
pub mod ipc;
#[cfg(feature = "cef-runtime")]
pub mod keyboard;
pub mod snapshot;

#[cfg(feature = "cef-runtime")]
use std::{
    path::Path,
    sync::{Arc, Mutex},
};

#[cfg(feature = "cef-runtime")]
use cef::{
    App, BrowserSettings, CefString, Client, CommandLine, ImplCommandLine, LogSeverity, Rect,
    RuntimeStyle, Settings, State, WindowInfo,
};

#[cfg(feature = "cef-runtime")]
pub use app::{OvApp, OvRenderProcessHandler};
pub use bridge_types::{pending_actions, BrowserBridge, NullBridge, PendingActions};
pub use config::{is_allowed_url, is_svg_url, OvConfig};
pub use dom_model::{
    analyze_document, analyze_html, classify, css_escape_ident, decode_html_entities,
    document_title, extract_armored_block, merge_inline_style, parse_html, query_selector, scan,
    scan_html, synthetic_id, DetectedField, HtmlNode, WriteTarget,
};
pub use frame_sink::{FrameSink, PaintBuffer, PixelFormat};
#[cfg(feature = "cef-runtime")]
pub use handlers::{BrowserSlot, OvClient, OvRenderHandler};
pub use ipc::{
    DecryptRequest, DecryptResponse, DomFieldMetadata, DomScanReport, FieldAction, FieldKind,
    FieldValue, MutationRequest, MutationResult, NavigationInfo, NodeRect,
};
pub use snapshot::{FrameSnapshot, PageSnapshot, SnapshotCollector, MAX_DEPTH};

/// Browser settings of Trap.
///
/// JavaScript is switched off in *every* browser instance; the remaining
/// switches remove the features that a hidden service could abuse to
/// fingerprint the device or to open a path around the Tor tunnel.
#[cfg(feature = "cef-runtime")]
pub fn browser_settings() -> BrowserSettings {
    BrowserSettings {
        javascript: State::DISABLED,
        javascript_close_windows: State::DISABLED,
        javascript_access_clipboard: State::DISABLED,
        javascript_dom_paste: State::DISABLED,
        databases_deprecated: State::DISABLED,
        webgl: State::DISABLED,
        local_storage: State::DISABLED,
        remote_fonts: State::DISABLED,
        image_loading: State::ENABLED,
        ..Default::default()
    }
}

/// The CEF settings of Trap.
#[cfg(feature = "cef-runtime")]
pub fn cef_settings(
    resources_dir: Option<&Path>,
    locales_dir: Option<&Path>,
    cache_dir: Option<&Path>,
    external_message_pump: bool,
) -> Settings {
    let mut settings = Settings {
        windowless_rendering_enabled: 1,
        multi_threaded_message_loop: 0,
        external_message_pump: if external_message_pump { 1 } else { 0 },
        log_severity: LogSeverity::WARNING,
        ..Default::default()
    };

    if let Some(dir) = resources_dir {
        settings.resources_dir_path = CefString::from(path_to_str(dir));
    }
    if let Some(dir) = locales_dir {
        settings.locales_dir_path = CefString::from(path_to_str(dir));
    }
    if let Some(dir) = cache_dir {
        settings.cache_path = CefString::from(path_to_str(dir));
    }

    settings
}

/// The window information of a windowless browser.
#[cfg(feature = "cef-runtime")]
pub fn window_info(width: i32, height: i32) -> WindowInfo {
    WindowInfo {
        windowless_rendering_enabled: 1,
        bounds: Rect {
            x: 0,
            y: 0,
            width,
            height,
        },
        runtime_style: RuntimeStyle::ALLOY,
        ..Default::default()
    }
}

/// Build the CEF application and the browser client.
#[cfg(feature = "cef-runtime")]
pub fn build_engine(
    config: Arc<OvConfig>,
    bridge: Arc<dyn BrowserBridge>,
    sink: Arc<dyn FrameSink>,
    slot: Arc<BrowserSlot>,
    pending: PendingActions,
    collector: Arc<SnapshotCollector>,
) -> (App, Client) {
    let verifications = Arc::new(Mutex::new(std::collections::HashMap::new()));
    let fields = Arc::new(Mutex::new(Vec::new()));

    let render_handler = handlers::OvRenderHandler::new(sink, collector.clone());
    let browser_load_handler =
        handlers::OvBrowserLoadHandler::new(bridge.clone(), config.clone(), collector.clone());
    let resource_request_handler = handlers::OvResourceRequestHandler::new(config.clone());
    let request_handler = handlers::OvRequestHandler::new(config.clone(), resource_request_handler);
    let life_span_handler = handlers::OvLifeSpanHandler::new(slot);

    let client = OvClient::new(
        bridge,
        render_handler,
        browser_load_handler,
        request_handler,
        life_span_handler,
        pending,
        verifications,
        collector,
    );

    let focused = Arc::new(Mutex::new(None));
    let render_process_handler = OvRenderProcessHandler::new(fields, focused);
    let app = OvApp::new(config, render_process_handler);

    (app, client)
}

/// Append the Trap switches to a command line.
#[cfg(feature = "cef-runtime")]
pub fn apply_command_line(command_line: &mut CommandLine, config: &OvConfig) {
    command_line.append_switch_with_value(
        Some(&CefString::from("proxy-server")),
        Some(&CefString::from(config.proxy_server().as_str())),
    );
    command_line.append_switch_with_value(
        Some(&CefString::from("host-resolver-rules")),
        Some(&CefString::from(config.host_resolver_rules())),
    );
    command_line.append_switch(Some(&CefString::from("disable-webrtc")));
    command_line.append_switch(Some(&CefString::from("disable-gpu-shader-disk-cache")));
    command_line.append_switch(Some(&CefString::from("incognito")));
    command_line.append_switch_with_value(
        Some(&CefString::from("disable-features")),
        Some(&CefString::from("NetworkPrediction")),
    );
    command_line.append_switch(Some(&CefString::from("disable-http2")));
    command_line.append_switch(Some(&CefString::from("disable-background-networking")));
    command_line.append_switch(Some(&CefString::from("no-first-run")));
}

/// Convert a path into the string representation that CEF expects.
#[cfg(feature = "cef-runtime")]
fn path_to_str(path: &Path) -> &str {
    path.to_str().unwrap_or("")
}
