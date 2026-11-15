//! Browser-process bridge implementing [`ov_cef::BrowserBridge`].

use std::sync::Arc;

use ov_cef::{
    BrowserBridge, DecryptRequest, DecryptResponse, DomScanReport, FieldAction, FieldValue,
    MutationResult, NavigationInfo, PageSnapshot,
};

use crate::{native::NativeArg, state::EngineState};

/// Pure-Rust implementation of [`BrowserBridge`] backed by [`EngineState`].
pub struct OvBridge {
    state: Arc<EngineState>,
}

impl OvBridge {
    /// Construct a new bridge over `state`.
    pub fn new(state: Arc<EngineState>) -> Self {
        Self { state }
    }
}

impl BrowserBridge for OvBridge {
    fn on_dom_scan(&self, report: DomScanReport) {
        if let Ok(payload) = serde_json::to_string(&report) {
            crate::native::call_native("onDomScan", &[NativeArg::String(payload)]);
        }
    }

    fn on_decrypt_request(&self, request: DecryptRequest) -> Option<DecryptResponse> {
        let result = match self.state.keyring.decrypt_armored(&request.armored) {
            Ok(res) => res,
            Err(err) => {
                log::warn!(
                    "cannot decrypt PGP request for {}: {err:#}",
                    request.node_id
                );
                return None;
            }
        };

        let verified = result.signature_status() == ov_pgp::SignatureStatus::Good;
        let signer_fp = result.signer_fingerprint_hex();
        let signer_uids = result
            .primary_signature()
            .map(|s| s.user_ids.clone())
            .unwrap_or_default();

        Some(DecryptResponse {
            node_id: request.node_id,
            plaintext: result.plaintext,
            signature_verified: verified,
            signer: (!signer_fp.is_empty()).then_some(signer_fp),
            signer_user_ids: signer_uids,
        })
    }

    fn on_field_value(
        &self,
        value: FieldValue,
        action: FieldAction,
        recipients: &[String],
    ) -> Option<String> {
        match action {
            FieldAction::Encrypt => {
                let fingerprints = if recipients.is_empty() {
                    self.state.own_fingerprints()
                } else {
                    recipients
                        .iter()
                        .filter_map(|r| ov_pgp::parse_fingerprint(r).ok())
                        .collect()
                };
                if fingerprints.is_empty() {
                    return None;
                }
                self.state
                    .keyring
                    .encrypt_with_options(&value.value, &fingerprints, None, None, false)
                    .ok()
                    .map(|out| out.ciphertext)
            }
            FieldAction::InsertPublicKey => self.state.keyring.export_public_key_by_query("").ok(),
        }
    }

    fn on_mutation_result(&self, result: MutationResult) {
        if let Ok(payload) = serde_json::to_string(&result) {
            crate::native::call_native("onMutationResult", &[NativeArg::String(payload)]);
        }
    }

    fn on_snapshot(&self, snapshot: PageSnapshot) {
        crate::native::call_native("onPageSnapshot", &[NativeArg::String(snapshot.url)]);
    }

    fn on_navigation(&self, info: NavigationInfo) {
        if let Ok(payload) = serde_json::to_string(&info) {
            crate::native::call_native("onNavigation", &[NativeArg::String(payload)]);
        }
    }
}
