//! Browser-process bridge traits and action state independent of `libcef.so`.

use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
};

use crate::{
    ipc::{
        DecryptRequest, DecryptResponse, DomScanReport, FieldAction, FieldValue, MutationResult,
        NavigationInfo,
    },
    snapshot::PageSnapshot,
};

/// The bridge between the DOM/browser engine and the rest of the application.
///
/// The browser process uses it to report detected DOM fields, request PGP
/// decryption/encryption, and report navigations and snapshots.
pub trait BrowserBridge: Send + Sync {
    /// New DOM fields were detected in the current document.
    fn on_dom_scan(&self, report: DomScanReport);
    /// Request decryption of an armored PGP message.
    ///
    /// When plaintext must stay out of the page DOM (the default in Trap), the
    /// implementation displays it in a native sheet and returns `None`.
    fn on_decrypt_request(&self, request: DecryptRequest) -> Option<DecryptResponse>;
    /// Perform a requested field action (`Encrypt` or `InsertPublicKey`) on a
    /// node's current value and return the replacement text.
    fn on_field_value(
        &self,
        value: FieldValue,
        action: FieldAction,
        recipients: &[String],
    ) -> Option<String>;
    /// Report the outcome of a DOM mutation.
    fn on_mutation_result(&self, result: MutationResult);
    /// A page was left and its snapshot is complete.
    fn on_snapshot(&self, snapshot: PageSnapshot);
    /// The browser navigated to a new document.
    fn on_navigation(&self, info: NavigationInfo);
}

/// A bridge that does nothing; used in headless tests or when running without
/// a native UI attached.
pub struct NullBridge;

impl BrowserBridge for NullBridge {
    fn on_dom_scan(&self, _report: DomScanReport) {}
    fn on_decrypt_request(&self, _request: DecryptRequest) -> Option<DecryptResponse> {
        None
    }
    fn on_field_value(
        &self,
        _value: FieldValue,
        _action: FieldAction,
        _recipients: &[String],
    ) -> Option<String> {
        None
    }
    fn on_mutation_result(&self, _result: MutationResult) {}
    fn on_snapshot(&self, _snapshot: PageSnapshot) {}
    fn on_navigation(&self, _info: NavigationInfo) {}
}

/// Registry of pending field actions requested by the native UI, keyed by
/// synthetic `node_id`.
pub type PendingActions = Arc<Mutex<HashMap<String, (FieldAction, Vec<String>)>>>;

/// Create an empty registry of pending field actions.
pub fn pending_actions() -> PendingActions {
    Arc::new(Mutex::new(HashMap::new()))
}
