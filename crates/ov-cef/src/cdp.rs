//! Pure-Rust Chrome DevTools Protocol (`DOM.getDocument`) PGP node classifier.
//!
//! Parses the JSON tree returned by `DOM.getDocument({"depth": -1, "pierce": true})`
//! in Chromium / Cefpack without executing JavaScript and without linking
//! `libcefpack_content.so`.
//!
//! Specifically handles Blink's User-Agent Shadow Root (`shadowRootType:
//! "user-agent"` -> `<div id="inner-editor">` -> `#text`) so user-typed and
//! `Input.insertText`-committed values inside `<input>` and `<textarea>` are
//! read accurately while `#placeholder` shadow nodes are ignored.

use std::collections::HashMap;

use serde::{Deserialize, Deserializer, Serialize};

use crate::{
    crypto_addr::{
        clean_positive_decimal, parse_bitcoin_uri, validate_btc_mainnet_address,
        validate_monero_uri_str,
    },
    dom_model::{
        detect_pgp_block_kind, extract_armored_block, is_ignored_subtree, is_pgp_container,
    },
    ipc::FieldKind,
};

/// Maximum CDP DOM traversal depth.
const MAX_CDP_DEPTH: usize = 96;

/// Classification emitted for a single actionable CDP DOM element.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CdpClassifiedNode {
    /// Session-scoped CDP `nodeId` (`DOM.getBoxModel`, `DOM.focus`,
    /// `DOM.setAttributeValue`).
    pub node_id: i64,
    /// Stable Blink `backendNodeId`.
    pub backend_node_id: i64,
    /// Lowercase element tag name (`textarea`, `input`, `pre`, `div`, ...).
    pub tag: String,
    /// `"pgp_textarea"`, `"pgp_message"`, or `"pgp_public_key"`.
    pub kind: String,
    /// Action chip label (`"Encrypt Field"`, `"Decrypt Block"`, `"Import Key"`,
    /// or `"Insert My PubKey"`).
    pub action_label: String,
    /// Live control value (for `pgp_textarea`) or cleanly extracted armored
    /// OpenPGP block (for `pgp_message` / `pgp_public_key`).
    pub text: String,
    /// Real cryptographic fingerprint / KeyID hint extracted from OpenPGP
    /// packets when available (`""` when none is present).
    pub fingerprint_hint: String,
    /// `true` when this element is a search box (`type="search"`,
    /// `role="searchbox"`, or single-line `<input>` with `name`/`id` in
    /// `["q", "query", "search", "s"]`), allowing UI chrome to exclude search
    /// boxes from passive editable PGP compose fields unless explicitly targeted.
    #[serde(default)]
    pub is_search_like: bool,
}

/// Classification emitted for arbitrary selected or pasted text.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClassifiedText {
    /// `"pgp_message"`, `"pgp_public_key"`, or `"plain"`.
    pub kind: String,
    /// `"Decrypt Block"`, `"Import Key"`, or `""`.
    pub action_label: String,
    /// Extracted OpenPGP ASCII-armored block (`""` when `kind == "plain"`).
    pub armored: String,
    /// Real cryptographic fingerprint / KeyID hint when available (`""` otherwise).
    pub fingerprint_hint: String,
}

/// Raw CDP `DOM.Node` representation accepting `DOM.getDocument` output.
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CdpNode {
    #[serde(default, alias = "node_id")]
    pub node_id: i64,
    #[serde(default, alias = "backend_node_id")]
    pub backend_node_id: i64,
    #[serde(default, alias = "node_type")]
    pub node_type: u8,
    #[serde(default, alias = "node_name")]
    pub node_name: String,
    #[serde(default, alias = "local_name")]
    pub local_name: String,
    #[serde(default, alias = "node_value")]
    pub node_value: String,
    #[serde(default, deserialize_with = "deserialize_cdp_attributes")]
    pub attributes: Vec<(String, String)>,
    #[serde(default)]
    pub children: Vec<CdpNode>,
    #[serde(default, alias = "shadow_roots")]
    pub shadow_roots: Vec<CdpNode>,
    #[serde(default, alias = "shadow_root_type")]
    pub shadow_root_type: Option<String>,
    #[serde(default, alias = "content_document")]
    pub content_document: Option<Box<CdpNode>>,
}

#[derive(Deserialize)]
#[serde(untagged)]
enum RawAttributes {
    Flat(Vec<String>),
    Map(HashMap<String, String>),
}

fn deserialize_cdp_attributes<'de, D>(deserializer: D) -> Result<Vec<(String, String)>, D::Error>
where
    D: Deserializer<'de>,
{
    let opt = Option::<RawAttributes>::deserialize(deserializer)?;
    let Some(raw) = opt else {
        return Ok(Vec::new());
    };
    match raw {
        RawAttributes::Flat(items) => {
            let mut pairs = Vec::with_capacity(items.len() / 2);
            let mut iter = items.into_iter();
            while let Some(key) = iter.next() {
                let val = iter.next().unwrap_or_default();
                pairs.push((key.to_ascii_lowercase(), val));
            }
            Ok(pairs)
        }
        RawAttributes::Map(map) => {
            let mut pairs: Vec<(String, String)> = map
                .into_iter()
                .map(|(k, v)| (k.to_ascii_lowercase(), v))
                .collect();
            pairs.sort_by(|a, b| a.0.cmp(&b.0));
            Ok(pairs)
        }
    }
}

#[derive(Deserialize)]
struct CdpEnvelope {
    root: Option<CdpNode>,
    result: Option<CdpResultInner>,
}

#[derive(Deserialize)]
struct CdpResultInner {
    root: Option<CdpNode>,
}

impl CdpNode {
    /// Effective lowercase tag name for element nodes (`nodeType == 1` or when
    /// `nodeType` is omitted on element fixtures).
    pub fn tag_lower(&self) -> String {
        if self.node_type == 3
            || self.node_type == 8
            || self.node_type == 9
            || self.node_type == 10
            || self.node_type == 11
            || self.node_name.starts_with('#')
        {
            return String::new();
        }
        if !self.local_name.is_empty() {
            self.local_name.to_ascii_lowercase()
        } else {
            self.node_name.to_ascii_lowercase()
        }
    }

    /// `true` when this CDP node represents an HTML/DOM element.
    pub fn is_element(&self) -> bool {
        (self.node_type == 1 || (self.node_type == 0 && !self.node_name.starts_with('#')))
            && !self.tag_lower().is_empty()
    }

    /// `true` when this CDP node is a `#text` node (`nodeType == 3`).
    pub fn is_text(&self) -> bool {
        self.node_type == 3 || self.node_name.eq_ignore_ascii_case("#text")
    }

    /// Lookup an attribute case-insensitively.
    pub fn attr(&self, name: &str) -> Option<&str> {
        self.attributes
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.as_str())
    }

    /// `true` when the attribute exists on the element.
    pub fn has_attr(&self, name: &str) -> bool {
        self.attributes
            .iter()
            .any(|(k, _)| k.eq_ignore_ascii_case(name))
    }
}

/// Parse a CDP `DOM.getDocument({"depth": -1, "pierce": true})` JSON payload
/// and return all actionable PGP fields and armored blocks.
pub fn classify_cdp_dom_json(cdp_dom_json: &str) -> Result<Vec<CdpClassifiedNode>, String> {
    classify_cdp_dom_with_hints(cdp_dom_json, |_kind, _text| (None, None))
}

/// Parse and classify a CDP `DOM.getDocument` JSON payload, invoking
/// `hint_resolver(kind, text) -> (fingerprint_hint, action_label_override)` on
/// each matched node so callers (`ov-core`) can attach real Sequoia OpenPGP
/// packet fingerprints and own-key labels.
pub fn classify_cdp_dom_with_hints<F>(
    cdp_dom_json: &str,
    hint_resolver: F,
) -> Result<Vec<CdpClassifiedNode>, String>
where
    F: Fn(&str, &str) -> (Option<String>, Option<String>),
{
    let root = parse_cdp_root(cdp_dom_json)?;
    let doc_ctx = collect_doc_swap_context(&root);
    let mut out = Vec::new();
    walk_cdp(
        &root,
        0,
        WalkContext::default(),
        &doc_ctx,
        &hint_resolver,
        &mut out,
    );
    Ok(out)
}

/// Classify an arbitrary string (e.g. selected text or clipboard snippet) for
/// PGP message or public-key armor.
pub fn classify_text(text: &str) -> ClassifiedText {
    classify_text_with_hints(text, |_kind, _armored| (None, None))
}

/// Classify text with an optional cryptographic fingerprint/label resolver.
pub fn classify_text_with_hints<F>(text: &str, hint_resolver: F) -> ClassifiedText
where
    F: Fn(&str, &str) -> (Option<String>, Option<String>),
{
    if let Some(armored) = extract_armored_block(text, FieldKind::PgpMessage) {
        let (fp, label) = hint_resolver("pgp_message", &armored);
        return ClassifiedText {
            kind: "pgp_message".to_string(),
            action_label: label.unwrap_or_else(|| "Decrypt Block".to_string()),
            armored,
            fingerprint_hint: fp.unwrap_or_default(),
        };
    }
    if let Some(armored) = extract_armored_block(text, FieldKind::PgpPublicKey) {
        let (fp, label) = hint_resolver("pgp_public_key", &armored);
        return ClassifiedText {
            kind: "pgp_public_key".to_string(),
            action_label: label.unwrap_or_else(|| "Import Key".to_string()),
            armored,
            fingerprint_hint: fp.unwrap_or_default(),
        };
    }
    ClassifiedText {
        kind: "plain".to_string(),
        action_label: String::new(),
        armored: String::new(),
        fingerprint_hint: String::new(),
    }
}

/// Parse `DOM.getDocument` JSON in any of the three standard forms:
/// `{"root": {...}}`, `{"result": {"root": {...}}}`, or direct `DOM.Node`.
pub fn parse_cdp_root(cdp_dom_json: &str) -> Result<CdpNode, String> {
    let trimmed = cdp_dom_json.trim();
    if trimmed.is_empty() {
        return Err("empty CDP DOM JSON payload".to_string());
    }
    if let Ok(env) = serde_json::from_str::<CdpEnvelope>(trimmed) {
        if let Some(root) = env.root {
            return Ok(root);
        }
        if let Some(inner) = env.result.and_then(|r| r.root) {
            return Ok(inner);
        }
    }
    serde_json::from_str::<CdpNode>(trimmed).map_err(|err| format!("invalid CDP DOM JSON: {err}"))
}

#[derive(Clone, Default)]
struct DocSwapContext {
    labels_by_for: HashMap<String, String>,
    page_has_xmr_swap_context: bool,
    btc_deposit_amount: String,
}

#[derive(Clone, Default)]
struct WalkContext {
    inside_pgp_block: bool,
    inside_locked_crypto: bool,
    inside_refund_or_source: bool,
    ancestor_data_net: Option<String>,
}

enum CryptoInputMatch {
    XmrPayout,
    SuppressNonPgp,
    NotCrypto,
}

fn collect_doc_swap_context(root: &CdpNode) -> DocSwapContext {
    let mut ctx = DocSwapContext::default();
    let mut mentions_xmr = false;
    let mut mentions_swap = false;
    scan_doc_context_node(
        root,
        0,
        false,
        &mut ctx,
        &mut mentions_xmr,
        &mut mentions_swap,
    );
    if mentions_xmr && mentions_swap {
        ctx.page_has_xmr_swap_context = true;
    }
    ctx
}

fn scan_doc_context_node(
    node: &CdpNode,
    depth: usize,
    in_deposit_box: bool,
    ctx: &mut DocSwapContext,
    mentions_xmr: &mut bool,
    mentions_swap: &mut bool,
) {
    if depth > MAX_CDP_DEPTH {
        return;
    }
    let tag = node.tag_lower();
    if !tag.is_empty() && (is_ignored_subtree(&tag) || is_hidden_element(node)) {
        return;
    }

    if node.is_text() {
        let lower = node.node_value.to_ascii_lowercase();
        if lower.contains("monero") || lower.contains("xmr") {
            *mentions_xmr = true;
        }
        if lower.contains("swap")
            || lower.contains("exchange")
            || lower.contains("payout")
            || lower.contains("recipient")
            || lower.contains("destination")
        {
            *mentions_swap = true;
        }
        return;
    }

    let mut next_in_deposit = in_deposit_box;
    if node.is_element() {
        if tag == "label" {
            if let Some(for_id) = node.attr("for") {
                let key = for_id.trim().to_ascii_lowercase();
                if !key.is_empty() {
                    let text = collect_cdp_rendered_text(node).to_ascii_lowercase();
                    ctx.labels_by_for.insert(key, text);
                }
            }
        }

        // Hidden or select indicators of XMR payout (`recv_coin=asset:XMR`, `<option selected value="XMR">`, `data-net="xmr"`):
        if tag == "input" {
            let nm = node.attr("name").unwrap_or("").to_ascii_lowercase();
            let val = node.attr("value").unwrap_or("").to_ascii_lowercase();
            if (nm.contains("recv") || nm.contains("to") || nm == "crypto_b")
                && (val.contains("xmr") || val.contains("monero"))
            {
                ctx.page_has_xmr_swap_context = true;
            }
        } else if tag == "option" && node.has_attr("selected") {
            let val = node.attr("value").unwrap_or("").to_ascii_lowercase();
            if val == "xmr" || val == "monero" {
                *mentions_xmr = true;
            }
        }
        if let Some(dnet) = node.attr("data-net").or_else(|| node.attr("data-coin")) {
            let dl = dnet.trim().to_ascii_lowercase();
            if dl == "xmr" || dl == "monero" {
                ctx.page_has_xmr_swap_context = true;
            }
        }
        if let Some(src) = node.attr("src") {
            let sl = src.to_ascii_lowercase();
            if sl.contains("xmr") || sl.contains("monero") {
                *mentions_xmr = true;
            }
        }
        if node.has_attr("data-swap") {
            *mentions_swap = true;
        }

        // Deposit amount extraction (`<span id="amount_a">0.01</span>`, `.deposit-amount`, `send-value`)
        let id_low = node.attr("id").unwrap_or("").trim().to_ascii_lowercase();
        let cls_low = node.attr("class").unwrap_or("").to_ascii_lowercase();
        if id_low.contains("deposit")
            || cls_low.contains("deposit-amount")
            || cls_low.contains("send-value")
        {
            next_in_deposit = true;
        }

        if ctx.btc_deposit_amount.is_empty() {
            if tag == "span" && id_low == "amount_a" {
                let raw = collect_cdp_rendered_text(node);
                if let Some(clean) = clean_positive_decimal(&raw) {
                    ctx.btc_deposit_amount = clean;
                }
            } else if next_in_deposit {
                if let Some(copy_val) = node.attr("data-copy") {
                    if let Some(clean) = clean_positive_decimal(copy_val) {
                        ctx.btc_deposit_amount = clean;
                    }
                } else if (tag == "span" || tag == "strong")
                    && cls_low.split_whitespace().any(|c| c == "amount")
                {
                    let raw = collect_cdp_rendered_text(node);
                    if let Some(clean) = clean_positive_decimal(&raw) {
                        ctx.btc_deposit_amount = clean;
                    }
                }
            }
        }
    }

    for child in &node.children {
        scan_doc_context_node(
            child,
            depth + 1,
            next_in_deposit,
            ctx,
            mentions_xmr,
            mentions_swap,
        );
    }
    if let Some(doc) = node.content_document.as_deref() {
        scan_doc_context_node(
            doc,
            depth + 1,
            next_in_deposit,
            ctx,
            mentions_xmr,
            mentions_swap,
        );
    }
}

fn walk_cdp<F>(
    node: &CdpNode,
    depth: usize,
    ctx: WalkContext,
    doc_ctx: &DocSwapContext,
    hint_resolver: &F,
    out: &mut Vec<CdpClassifiedNode>,
) where
    F: Fn(&str, &str) -> (Option<String>, Option<String>),
{
    if depth > MAX_CDP_DEPTH {
        return;
    }

    let tag = node.tag_lower();
    if !tag.is_empty() && (is_ignored_subtree(&tag) || is_hidden_element(node)) {
        return;
    }

    let mut child_ctx = ctx.clone();
    if node.is_element() {
        if let Some(dnet) = node.attr("data-net").or_else(|| node.attr("data-coin")) {
            let trimmed = dnet.trim().to_ascii_lowercase();
            if !trimmed.is_empty() {
                child_ctx.ancestor_data_net = Some(trimmed);
            }
        }
        if node.has_attr("data-refund-group")
            || node.has_attr("data-source-group")
            || node.attr("class").is_some_and(|c| {
                let cl = c.to_ascii_lowercase();
                cl.contains("refund") || cl.contains("source-address")
            })
            || node.attr("id").is_some_and(|id| {
                let il = id.to_ascii_lowercase();
                il.contains("refund") || il.contains("source")
            })
        {
            child_ctx.inside_refund_or_source = true;
        }
    }

    if node.is_element() && node.node_id > 0 {
        if let Some((kind, default_label, payload_text, default_hint, locks_children)) =
            classify_cdp_element(node, &tag, &child_ctx, doc_ctx)
        {
            let is_search_like = kind == "pgp_textarea" && is_search_like_element(node, &tag);
            let (fp_hint, label_override) = hint_resolver(kind, &payload_text);
            out.push(CdpClassifiedNode {
                node_id: node.node_id,
                backend_node_id: node.backend_node_id,
                tag: tag.clone(),
                kind: kind.to_string(),
                action_label: label_override.unwrap_or_else(|| default_label.to_string()),
                text: payload_text,
                fingerprint_hint: fp_hint.or(default_hint).unwrap_or_default(),
                is_search_like,
            });
            if locks_children {
                if matches!(kind, "pgp_message" | "pgp_public_key" | "pgp_textarea") {
                    child_ctx.inside_pgp_block = true;
                } else {
                    child_ctx.inside_locked_crypto = true;
                }
            }
        }
    }

    // Do not walk inside `<textarea>` or `<input>` children/UA shadow roots for
    // separate nodes; the control itself owns its value.
    if tag == "textarea" || tag == "input" {
        return;
    }

    for child in &node.children {
        walk_cdp(
            child,
            depth + 1,
            child_ctx.clone(),
            doc_ctx,
            hint_resolver,
            out,
        );
    }

    // Traverse open/author shadow roots (never Blink UA `#inner-editor` shadow
    // roots, which belong to form controls handled above).
    for shadow in &node.shadow_roots {
        let is_ua = shadow
            .shadow_root_type
            .as_deref()
            .is_some_and(|t| t.eq_ignore_ascii_case("user-agent"));
        if !is_ua {
            walk_cdp(
                shadow,
                depth + 1,
                child_ctx.clone(),
                doc_ctx,
                hint_resolver,
                out,
            );
        }
    }

    if let Some(doc) = node.content_document.as_deref() {
        walk_cdp(doc, depth + 1, child_ctx, doc_ctx, hint_resolver, out);
    }
}

fn classify_cdp_element(
    node: &CdpNode,
    tag: &str,
    walk_ctx: &WalkContext,
    doc_ctx: &DocSwapContext,
) -> Option<(&'static str, &'static str, String, Option<String>, bool)> {
    let disabled = node.has_attr("disabled");
    let readonly = node.has_attr("readonly");

    if tag == "a" && !walk_ctx.inside_locked_crypto {
        if let Some(href) = node.attr("href") {
            if let Some((btc_addr, uri_amt)) = parse_bitcoin_uri(href) {
                let hint = uri_amt.or_else(|| non_empty_opt(&doc_ctx.btc_deposit_amount));
                return Some((
                    "btc_deposit_address",
                    "Copy BTC Deposit",
                    btc_addr,
                    hint,
                    true,
                ));
            }
            if let Some((xmr_uri, uri_amt)) = validate_monero_uri_str(href) {
                return Some(("monero_uri", "Pay XMR", xmr_uri, uri_amt, true));
            }
        }
    }

    if tag == "textarea" {
        let live_value = live_form_control_value(node, tag);
        if !walk_ctx.inside_pgp_block {
            if let Some(armored) = extract_armored_block(&live_value, FieldKind::PgpMessage) {
                return Some(("pgp_message", "Decrypt Block", armored, None, true));
            }
            if (disabled || readonly)
                && extract_armored_block(&live_value, FieldKind::PgpPublicKey).is_some()
            {
                let armored = extract_armored_block(&live_value, FieldKind::PgpPublicKey)?;
                return Some(("pgp_public_key", "Import Key", armored, None, true));
            }
        }
        if !disabled && !readonly {
            if is_pubkey_upload_field(node, &live_value) {
                return Some(("pgp_public_key", "Insert My PubKey", live_value, None, true));
            }
            match classify_editable_crypto_control(node, walk_ctx, doc_ctx) {
                CryptoInputMatch::XmrPayout => {
                    return Some((
                        "xmr_address_input",
                        "Insert XMR Subaddress",
                        live_value,
                        None,
                        true,
                    ));
                }
                CryptoInputMatch::SuppressNonPgp => return None,
                CryptoInputMatch::NotCrypto => {
                    return Some(("pgp_textarea", "Encrypt Field", live_value, None, true));
                }
            }
        }
        return None;
    }

    if tag == "input" {
        let input_type = node.attr("type").unwrap_or("").trim().to_ascii_lowercase();
        let text_like = matches!(
            input_type.as_str(),
            "" | "text" | "search" | "email" | "url" | "tel"
        );
        if !text_like {
            return None;
        }
        let live_value = live_form_control_value(node, tag);
        if !walk_ctx.inside_pgp_block {
            if let Some(armored) = extract_armored_block(&live_value, FieldKind::PgpMessage) {
                return Some(("pgp_message", "Decrypt Block", armored, None, true));
            }
        }
        if readonly || disabled {
            let label_for_text = lookup_label_for(node, doc_ctx);
            if !walk_ctx.inside_refund_or_source
                && !has_refund_or_source_marker(node, label_for_text)
            {
                if let Some(btc_addr) = validate_btc_mainnet_address(&live_value) {
                    return Some((
                        "btc_deposit_address",
                        "Copy BTC Deposit",
                        btc_addr,
                        non_empty_opt(&doc_ctx.btc_deposit_amount),
                        true,
                    ));
                }
            }
            return None;
        }

        if is_pubkey_upload_field(node, &live_value) {
            return Some(("pgp_public_key", "Insert My PubKey", live_value, None, true));
        }
        match classify_editable_crypto_control(node, walk_ctx, doc_ctx) {
            CryptoInputMatch::XmrPayout => {
                return Some((
                    "xmr_address_input",
                    "Insert XMR Subaddress",
                    live_value,
                    None,
                    true,
                ));
            }
            CryptoInputMatch::SuppressNonPgp => return None,
            CryptoInputMatch::NotCrypto => {
                return Some(("pgp_textarea", "Encrypt Field", live_value, None, true));
            }
        }
    }

    if is_contenteditable(node) && !disabled && !readonly {
        let live_value = collect_cdp_rendered_text(node);
        if !walk_ctx.inside_pgp_block {
            if let Some(armored) = extract_armored_block(&live_value, FieldKind::PgpMessage) {
                return Some(("pgp_message", "Decrypt Block", armored, None, true));
            }
        }
        return Some(("pgp_textarea", "Encrypt Field", live_value, None, true));
    }

    if !walk_ctx.inside_pgp_block && is_pgp_container(tag) && !any_child_element_has_pgp_block(node)
    {
        let rendered = collect_cdp_rendered_text(node);
        if let Some(armored) = extract_armored_block(&rendered, FieldKind::PgpMessage) {
            return Some(("pgp_message", "Decrypt Block", armored, None, true));
        }
        if let Some(armored) = extract_armored_block(&rendered, FieldKind::PgpPublicKey) {
            return Some(("pgp_public_key", "Import Key", armored, None, true));
        }
    }

    if !walk_ctx.inside_locked_crypto && is_leaf_text_container(node, tag) {
        let rendered = collect_cdp_rendered_text(node);
        let trimmed = rendered.trim();
        if let Some((xmr_uri, uri_amt)) = validate_monero_uri_str(trimmed) {
            return Some(("monero_uri", "Pay XMR", xmr_uri, uri_amt, true));
        }
        if !walk_ctx.inside_refund_or_source && !has_refund_or_source_marker(node, "") {
            if let Some(btc_addr) = validate_btc_mainnet_address(trimmed) {
                return Some((
                    "btc_deposit_address",
                    "Copy BTC Deposit",
                    btc_addr,
                    non_empty_opt(&doc_ctx.btc_deposit_amount),
                    true,
                ));
            }
        }
    }

    None
}

fn non_empty_opt(s: &str) -> Option<String> {
    let t = s.trim();
    (!t.is_empty()).then(|| t.to_string())
}

fn lookup_label_for<'a>(node: &CdpNode, doc_ctx: &'a DocSwapContext) -> &'a str {
    node.attr("id")
        .map(|id| id.trim().to_ascii_lowercase())
        .and_then(|id| doc_ctx.labels_by_for.get(&id).map(String::as_str))
        .unwrap_or("")
}

fn has_refund_or_source_marker(node: &CdpNode, label_text: &str) -> bool {
    if node.has_attr("data-refund-address")
        || node.has_attr("data-source-address")
        || node.has_attr("data-refund-group")
        || node.has_attr("data-source-group")
    {
        return true;
    }
    for attr_name in ["name", "id", "placeholder", "aria-label"] {
        if let Some(val) = node.attr(attr_name) {
            let low = val.to_ascii_lowercase();
            if low.contains("refund")
                || low.contains("sourceaddress")
                || low.contains("source_address")
                || low.contains("source-address")
                || low == "pair-source"
                || low.contains("wallet you send from")
                || low.contains("sending address")
            {
                return true;
            }
        }
    }
    label_text.contains("refund")
        || label_text.contains("sending address")
        || label_text.contains("wallet you send from")
}

fn classify_editable_crypto_control(
    node: &CdpNode,
    walk_ctx: &WalkContext,
    doc_ctx: &DocSwapContext,
) -> CryptoInputMatch {
    let label_text = lookup_label_for(node, doc_ctx);

    // 1. Strictly suppress PGP pills on refund / source / sending wallet controls:
    if walk_ctx.inside_refund_or_source || has_refund_or_source_marker(node, label_text) {
        return CryptoInputMatch::SuppressNonPgp;
    }

    let name_low = node.attr("name").unwrap_or("").trim().to_ascii_lowercase();
    let id_low = node.attr("id").unwrap_or("").trim().to_ascii_lowercase();
    let ph_low = node
        .attr("placeholder")
        .unwrap_or("")
        .trim()
        .to_ascii_lowercase();
    let aria_low = node
        .attr("aria-label")
        .unwrap_or("")
        .trim()
        .to_ascii_lowercase();
    let combined = format!("{name_low} {id_low} {ph_low} {aria_low} {label_text}");

    // 2. Suppress auxiliary non-PGP swap inputs (BTC destination, deposit TXID, swap qty, memo, seal_code):
    let ancestor_is_btc = walk_ctx
        .ancestor_data_net
        .as_deref()
        .is_some_and(|n| n == "btc" || n == "bitcoin");
    let mentions_btc =
        ancestor_is_btc || combined.contains("bitcoin") || contains_word_token(&combined, "btc");

    if mentions_btc {
        return CryptoInputMatch::SuppressNonPgp;
    }
    if matches!(
        name_low.as_str(),
        "capture_txid" | "send_qty" | "seal_code" | "extraid" | "refundextraid" | "amount_a"
    ) || ph_low.contains("deposit txid")
        || node.has_attr("data-extra-input")
    {
        return CryptoInputMatch::SuppressNonPgp;
    }

    // 3. Identify Monero / XMR payout & destination wallet controls:
    let ancestor_is_xmr = walk_ctx
        .ancestor_data_net
        .as_deref()
        .is_some_and(|n| n == "xmr" || n == "monero");
    let explicit_xmr =
        ancestor_is_xmr || combined.contains("monero") || contains_word_token(&combined, "xmr");

    let has_wallet_or_address_cue = node.has_attr("data-address")
        || name_low.contains("address")
        || name_low.contains("payout")
        || name_low.contains("wallet")
        || name_low.contains("recipient")
        || id_low.contains("address")
        || id_low.contains("payout")
        || ph_low.contains("address")
        || ph_low.contains("wallet")
        || ph_low.contains("subaddress")
        || label_text.contains("address")
        || label_text.contains("wallet")
        || label_text.contains("recipient");

    if explicit_xmr && has_wallet_or_address_cue {
        return CryptoInputMatch::XmrPayout;
    }

    // Explicit payout/destination wallet controls on a page with XMR swap context (e.g. Pablo Cash `input#payoutAddress[name="payout_addr"]`):
    let is_explicit_swap_payout_field = node.has_attr("data-address")
        || matches!(
            name_low.as_str(),
            "payout_addr"
                | "payout_address"
                | "payoutaddress"
                | "destination_address"
                | "xmr_address"
        )
        || matches!(id_low.as_str(), "payoutaddress" | "pair-address")
        || ph_low.contains("payout address")
        || label_text.contains("recipient wallet")
        || label_text.contains("destination address")
        || label_text.contains("payout address");

    if doc_ctx.page_has_xmr_swap_context && is_explicit_swap_payout_field {
        return CryptoInputMatch::XmrPayout;
    }

    CryptoInputMatch::NotCrypto
}

fn contains_word_token(haystack: &str, token: &str) -> bool {
    haystack
        .split(|c: char| !c.is_ascii_alphanumeric())
        .any(|w| w == token)
}

fn is_leaf_text_container(node: &CdpNode, tag: &str) -> bool {
    if !matches!(
        tag,
        "p" | "code" | "span" | "div" | "strong" | "b" | "pre" | "dd" | "td"
    ) {
        return false;
    }
    !node.children.iter().any(|child| {
        if !child.is_element() {
            return false;
        }
        let ctag = child.tag_lower();
        !matches!(ctag.as_str(), "wbr" | "br")
    })
}

/// Extract the live value of `<textarea>` or `<input>` from Blink's pierced
/// User-Agent shadow root (`<div id="inner-editor">` or Blink's plain editor
/// `<div>` inside `#shadow-root (user-agent)`), ignoring `#placeholder`
/// shadow nodes and falling back to author children / `value` attribute when
/// no UA shadow root is present.
pub fn live_form_control_value(node: &CdpNode, tag: &str) -> String {
    for shadow in &node.shadow_roots {
        if let Some(inner_editor) = find_inner_editor(shadow) {
            return collect_inner_editor_text(inner_editor);
        }
    }
    if !node.shadow_roots.is_empty() {
        let mut shadow_text = String::new();
        for shadow in &node.shadow_roots {
            collect_non_placeholder_shadow_text(shadow, &mut shadow_text);
        }
        return shadow_text;
    }
    if tag == "input" {
        node.attr("value").unwrap_or("").to_string()
    } else {
        collect_cdp_rendered_text(node)
    }
}

fn is_placeholder_element(node: &CdpNode) -> bool {
    node.is_element()
        && (node
            .attr("id")
            .is_some_and(|id| id.eq_ignore_ascii_case("placeholder"))
            || node
                .attr("pseudo")
                .is_some_and(|p| p.to_ascii_lowercase().contains("placeholder")))
}

fn find_inner_editor(node: &CdpNode) -> Option<&CdpNode> {
    if node.is_element() {
        if node
            .attr("id")
            .is_some_and(|id| id.eq_ignore_ascii_case("inner-editor"))
        {
            return Some(node);
        }
        // Never descend into Blink's `#placeholder` / `-webkit-input-placeholder`.
        if is_placeholder_element(node) {
            return None;
        }
    }
    for child in &node.children {
        if let Some(found) = find_inner_editor(child) {
            return Some(found);
        }
    }
    None
}

fn collect_non_placeholder_shadow_text(node: &CdpNode, out: &mut String) {
    if is_placeholder_element(node) {
        return;
    }
    if node.is_text() {
        out.push_str(&node.node_value);
        return;
    }
    let tag = node.tag_lower();
    if !tag.is_empty() && is_ignored_subtree(&tag) {
        return;
    }
    let len = node.children.len();
    for (idx, child) in node.children.iter().enumerate() {
        if is_placeholder_element(child) {
            continue;
        }
        if child.is_text() {
            out.push_str(&child.node_value);
        } else if child.tag_lower() == "br" {
            let is_trailing_caret_br = idx + 1 == len && (out.is_empty() || out.ends_with('\n'));
            if !is_trailing_caret_br {
                out.push('\n');
            }
        } else {
            collect_non_placeholder_shadow_text(child, out);
        }
    }
}

fn collect_inner_editor_text(inner_editor: &CdpNode) -> String {
    let mut out = String::new();
    let len = inner_editor.children.len();
    for (idx, child) in inner_editor.children.iter().enumerate() {
        if child.is_text() {
            out.push_str(&child.node_value);
        } else if child.tag_lower() == "br" {
            // Blink appends a trailing placeholder `<br>` at the end of
            // `#inner-editor` when the preceding text already ended with `\n`
            // or when `#inner-editor` holds a single empty-line `<br>`.
            let is_trailing_caret_br = idx + 1 == len && (out.is_empty() || out.ends_with('\n'));
            if !is_trailing_caret_br {
                out.push('\n');
            }
        } else {
            collect_cdp_text_into(child, &mut out);
        }
    }
    out
}

fn collect_cdp_rendered_text(node: &CdpNode) -> String {
    let mut out = String::new();
    collect_cdp_text_into(node, &mut out);
    out
}

fn collect_cdp_text_into(node: &CdpNode, out: &mut String) {
    if node.is_text() {
        out.push_str(&node.node_value);
        return;
    }
    let tag = node.tag_lower();
    if tag == "br" {
        out.push('\n');
        return;
    }
    if !tag.is_empty() && is_ignored_subtree(&tag) {
        return;
    }
    for child in &node.children {
        collect_cdp_text_into(child, out);
    }
}

fn any_child_element_has_pgp_block(node: &CdpNode) -> bool {
    for child in &node.children {
        let tag = child.tag_lower();
        if !tag.is_empty() && is_ignored_subtree(&tag) {
            continue;
        }
        if child.is_element() && detect_pgp_block_kind(&collect_cdp_rendered_text(child)).is_some()
        {
            return true;
        }
    }
    false
}

fn is_contenteditable(node: &CdpNode) -> bool {
    match node.attr("contenteditable") {
        Some(val) => {
            let v = val.trim().to_ascii_lowercase();
            v.is_empty() || v == "true" || v == "plaintext-only"
        }
        None => false,
    }
}

fn is_hidden_element(node: &CdpNode) -> bool {
    if node.has_attr("hidden") {
        return true;
    }
    if node.tag_lower() == "dialog" && !node.has_attr("open") {
        return true;
    }
    if let Some(cls) = node.attr("class") {
        if cls
            .split_whitespace()
            .any(|tok| tok.eq_ignore_ascii_case("hidden") || tok.eq_ignore_ascii_case("d-none"))
        {
            return true;
        }
    }
    if let Some(style) = node.attr("style") {
        let compact: String = style
            .chars()
            .filter(|c| !c.is_whitespace())
            .collect::<String>()
            .to_ascii_lowercase();
        if compact.contains("display:none") || compact.contains("visibility:hidden") {
            return true;
        }
    }
    false
}

fn is_pubkey_upload_field(node: &CdpNode, live_value: &str) -> bool {
    if extract_armored_block(live_value, FieldKind::PgpPublicKey).is_some() {
        return true;
    }
    for attr_name in ["name", "id", "placeholder", "aria-label"] {
        if let Some(raw) = node.attr(attr_name) {
            let lower = raw.to_ascii_lowercase();
            if lower.contains("pubkey")
                || lower.contains("public_key")
                || lower.contains("public-key")
                || lower.contains("public key")
                || ((lower.contains("pgp") || lower.contains("gpg")) && lower.contains("key"))
            {
                return true;
            }
        }
    }
    false
}

/// `true` when `node` is a search box (`type="search"`, `role="searchbox"`, or
/// single-line `<input>` with `name`/`id` in `["q", "query", "search", "s"]`).
pub fn is_search_like_element(node: &CdpNode, tag: &str) -> bool {
    if node
        .attr("role")
        .is_some_and(|r| r.trim().eq_ignore_ascii_case("searchbox"))
    {
        return true;
    }
    if tag == "input" {
        if node
            .attr("type")
            .is_some_and(|t| t.trim().eq_ignore_ascii_case("search"))
        {
            return true;
        }
        for attr_name in ["name", "id"] {
            if let Some(raw) = node.attr(attr_name) {
                let normalized = raw.trim().to_ascii_lowercase();
                if matches!(normalized.as_str(), "q" | "query" | "search" | "s") {
                    return true;
                }
            }
        }
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_blink_pierced_ua_shadow_root_and_ignores_placeholder() {
        let cdp_json = r##"{
          "root": {
            "nodeId": 1,
            "backendNodeId": 1,
            "nodeType": 9,
            "nodeName": "#document",
            "children": [
              {
                "nodeId": 2,
                "backendNodeId": 2,
                "nodeType": 1,
                "nodeName": "HTML",
                "localName": "html",
                "children": [
                  {
                    "nodeId": 3,
                    "backendNodeId": 3,
                    "nodeType": 1,
                    "nodeName": "BODY",
                    "localName": "body",
                    "children": [
                      {
                        "nodeId": 10,
                        "backendNodeId": 100,
                        "nodeType": 1,
                        "nodeName": "TEXTAREA",
                        "localName": "textarea",
                        "attributes": ["id", "msg", "placeholder", "Do not encrypt this placeholder"],
                        "shadowRoots": [
                          {
                            "nodeId": 11,
                            "backendNodeId": 101,
                            "nodeType": 11,
                            "nodeName": "#document-fragment",
                            "shadowRootType": "user-agent",
                            "children": [
                              {
                                "nodeId": 12,
                                "backendNodeId": 102,
                                "nodeType": 1,
                                "nodeName": "DIV",
                                "localName": "div",
                                "attributes": ["id", "placeholder", "pseudo", "-webkit-input-placeholder"],
                                "children": [
                                  { "nodeId": 13, "nodeType": 3, "nodeName": "#text", "nodeValue": "Do not encrypt this placeholder" }
                                ]
                              },
                              {
                                "nodeId": 14,
                                "backendNodeId": 104,
                                "nodeType": 1,
                                "nodeName": "DIV",
                                "localName": "div",
                                "attributes": ["id", "inner-editor"],
                                "children": [
                                  { "nodeId": 15, "nodeType": 3, "nodeName": "#text", "nodeValue": "Live secret message typed by user" }
                                ]
                              }
                            ]
                          }
                        ]
                      },
                      {
                        "nodeId": 20,
                        "backendNodeId": 200,
                        "nodeType": 1,
                        "nodeName": "INPUT",
                        "localName": "input",
                        "attributes": ["type", "text", "value", "StaleAttrValue"],
                        "shadowRoots": [
                          {
                            "nodeId": 21,
                            "nodeType": 11,
                            "nodeName": "#document-fragment",
                            "shadowRootType": "user-agent",
                            "children": [
                              {
                                "nodeId": 22,
                                "nodeType": 1,
                                "nodeName": "DIV",
                                "localName": "div",
                                "attributes": ["id", "inner-editor"],
                                "children": []
                              }
                            ]
                          }
                        ]
                      },
                      {
                        "nodeId": 30,
                        "backendNodeId": 300,
                        "nodeType": 1,
                        "nodeName": "DIV",
                        "localName": "div",
                        "children": [
                          {
                            "nodeId": 31,
                            "backendNodeId": 301,
                            "nodeType": 1,
                            "nodeName": "PRE",
                            "localName": "pre",
                            "children": [
                              {
                                "nodeId": 32,
                                "nodeType": 3,
                                "nodeName": "#text",
                                "nodeValue": "-----BEGIN PGP MESSAGE-----\n\nhQEMAyEAD2G1UN2v==\n-----END PGP MESSAGE-----"
                              }
                            ]
                          }
                        ]
                      },
                      {
                        "nodeId": 40,
                        "backendNodeId": 400,
                        "nodeType": 1,
                        "nodeName": "PRE",
                        "localName": "pre",
                        "children": [
                          {
                            "nodeId": 41,
                            "nodeType": 3,
                            "nodeName": "#text",
                            "nodeValue": "Key below:\n-----BEGIN PGP PUBLIC KEY BLOCK-----\n\nmDMEY123456==\n-----END PGP PUBLIC KEY BLOCK-----\nEnd"
                          }
                        ]
                      }
                    ]
                  }
                ]
              }
            ]
          }
        }"##;

        let nodes = classify_cdp_dom_json(cdp_json).expect("valid CDP JSON");
        assert_eq!(nodes.len(), 4);

        assert_eq!(nodes[0].node_id, 10);
        assert_eq!(nodes[0].backend_node_id, 100);
        assert_eq!(nodes[0].tag, "textarea");
        assert_eq!(nodes[0].kind, "pgp_textarea");
        assert_eq!(nodes[0].action_label, "Encrypt Field");
        assert_eq!(nodes[0].text, "Live secret message typed by user");

        // Cleared input (`#inner-editor` has empty children despite stale HTML `value` attr):
        assert_eq!(nodes[1].node_id, 20);
        assert_eq!(nodes[1].kind, "pgp_textarea");
        assert_eq!(nodes[1].text, "");

        // Inner `<pre>` (nodeId 31) matched instead of outer `<div>` (nodeId 30):
        assert_eq!(nodes[2].node_id, 31);
        assert_eq!(nodes[2].kind, "pgp_message");
        assert_eq!(nodes[2].action_label, "Decrypt Block");
        assert!(nodes[2].text.starts_with("-----BEGIN PGP MESSAGE-----"));

        // Public key block stripped of surrounding prose:
        assert_eq!(nodes[3].node_id, 40);
        assert_eq!(nodes[3].kind, "pgp_public_key");
        assert_eq!(nodes[3].action_label, "Import Key");
        assert!(nodes[3]
            .text
            .starts_with("-----BEGIN PGP PUBLIC KEY BLOCK-----"));
        assert!(nodes[3]
            .text
            .ends_with("-----END PGP PUBLIC KEY BLOCK-----"));
    }

    #[test]
    fn classify_text_identifies_messages_keys_and_plain_text() {
        let msg = classify_text(
            "Selected:\n-----BEGIN PGP MESSAGE-----\n\nhQEMA==\n-----END PGP MESSAGE-----",
        );
        assert_eq!(msg.kind, "pgp_message");
        assert_eq!(msg.action_label, "Decrypt Block");

        let key = classify_text(
            "-----BEGIN PGP PUBLIC KEY BLOCK-----\n\nmDME==\n-----END PGP PUBLIC KEY BLOCK-----",
        );
        assert_eq!(key.kind, "pgp_public_key");
        assert_eq!(key.action_label, "Import Key");

        let plain = classify_text("just normal hidden service prose");
        assert_eq!(plain.kind, "plain");
        assert_eq!(plain.action_label, "");
        assert_eq!(plain.armored, "");
    }

    #[test]
    fn editable_pubkey_registration_textarea_emits_insert_my_pubkey() {
        let cdp_json = r##"{
          "root": {
            "nodeId": 1,
            "backendNodeId": 1,
            "nodeType": 9,
            "nodeName": "#document",
            "children": [{
              "nodeId": 7,
              "backendNodeId": 77,
              "nodeType": 1,
              "nodeName": "TEXTAREA",
              "localName": "textarea",
              "attributes": ["name", "pgp_public_key", "placeholder", "Paste your PGP public key"]
            }]
          }
        }"##;
        let nodes = classify_cdp_dom_json(cdp_json).expect("valid CDP JSON");
        assert_eq!(nodes.len(), 1);
        assert_eq!(nodes[0].kind, "pgp_public_key");
        assert_eq!(nodes[0].action_label, "Insert My PubKey");
    }

    #[test]
    fn parses_blink_textarea_ua_shadow_editor_without_inner_editor_id() {
        let cdp_json = r##"{
          "root": {
            "nodeId": 1,
            "backendNodeId": 1,
            "nodeType": 9,
            "nodeName": "#document",
            "children": [{
              "nodeId": 7,
              "backendNodeId": 77,
              "nodeType": 1,
              "nodeName": "TEXTAREA",
              "localName": "textarea",
              "attributes": ["id", "msg", "placeholder", "Write secret message"],
              "children": [
                { "nodeId": 8, "nodeType": 3, "nodeName": "#text", "nodeValue": "Initial stale text" }
              ],
              "shadowRoots": [{
                "nodeId": 9,
                "nodeType": 11,
                "nodeName": "#document-fragment",
                "shadowRootType": "user-agent",
                "children": [
                  {
                    "nodeId": 10,
                    "nodeType": 1,
                    "nodeName": "DIV",
                    "localName": "div",
                    "attributes": ["pseudo", "-webkit-input-placeholder", "id", "placeholder"],
                    "children": [
                      { "nodeId": 11, "nodeType": 3, "nodeName": "#text", "nodeValue": "Write secret message" }
                    ]
                  },
                  {
                    "nodeId": 12,
                    "nodeType": 1,
                    "nodeName": "DIV",
                    "localName": "div",
                    "children": [
                      { "nodeId": 13, "nodeType": 3, "nodeName": "#text", "nodeValue": "-----BEGIN PGP MESSAGE-----" },
                      { "nodeId": 14, "nodeType": 1, "nodeName": "BR", "localName": "br" },
                      { "nodeId": 15, "nodeType": 1, "nodeName": "BR", "localName": "br" },
                      { "nodeId": 16, "nodeType": 3, "nodeName": "#text", "nodeValue": "hQEMAyEAD2G1UN2v==" },
                      { "nodeId": 17, "nodeType": 1, "nodeName": "BR", "localName": "br" },
                      { "nodeId": 18, "nodeType": 3, "nodeName": "#text", "nodeValue": "-----END PGP MESSAGE-----" },
                      { "nodeId": 19, "nodeType": 1, "nodeName": "BR", "localName": "br" }
                    ]
                  }
                ]
              }]
            }]
          }
        }"##;
        let nodes = classify_cdp_dom_json(cdp_json).expect("valid CDP JSON");
        assert_eq!(nodes.len(), 1);
        assert_eq!(nodes[0].kind, "pgp_message");
        assert_eq!(nodes[0].action_label, "Decrypt Block");
        assert!(!nodes[0].text.contains("Write secret message"));
        assert!(!nodes[0].text.contains("Initial stale text"));
        assert!(nodes[0].text.starts_with("-----BEGIN PGP MESSAGE-----"));
        assert!(nodes[0].text.ends_with("-----END PGP MESSAGE-----"));
        assert!(!nodes[0].is_search_like);
    }

    #[test]
    fn marks_search_inputs_as_is_search_like_while_preserving_compose_textareas() {
        let cdp_json = r##"{
          "root": {
            "nodeId": 1,
            "backendNodeId": 1,
            "nodeType": 9,
            "nodeName": "#document",
            "children": [{
              "nodeId": 2,
              "backendNodeId": 2,
              "nodeType": 1,
              "nodeName": "BODY",
              "localName": "body",
              "children": [
                {
                  "nodeId": 10,
                  "backendNodeId": 100,
                  "nodeType": 1,
                  "nodeName": "INPUT",
                  "localName": "input",
                  "attributes": ["type", "text", "name", "q", "placeholder", "Search DuckDuckGo"]
                },
                {
                  "nodeId": 11,
                  "backendNodeId": 101,
                  "nodeType": 1,
                  "nodeName": "INPUT",
                  "localName": "input",
                  "attributes": ["type", "search", "name", "site_filter"]
                },
                {
                  "nodeId": 12,
                  "backendNodeId": 102,
                  "nodeType": 1,
                  "nodeName": "INPUT",
                  "localName": "input",
                  "attributes": ["type", "text", "id", "query"]
                },
                {
                  "nodeId": 13,
                  "backendNodeId": 103,
                  "nodeType": 1,
                  "nodeName": "DIV",
                  "localName": "div",
                  "attributes": ["contenteditable", "true", "role", "searchbox"]
                },
                {
                  "nodeId": 20,
                  "backendNodeId": 200,
                  "nodeType": 1,
                  "nodeName": "INPUT",
                  "localName": "input",
                  "attributes": ["type", "text", "name", "subject"]
                },
                {
                  "nodeId": 21,
                  "backendNodeId": 201,
                  "nodeType": 1,
                  "nodeName": "TEXTAREA",
                  "localName": "textarea",
                  "attributes": ["name", "q", "rows", "6"]
                },
                {
                  "nodeId": 22,
                  "backendNodeId": 202,
                  "nodeType": 1,
                  "nodeName": "TEXTAREA",
                  "localName": "textarea",
                  "attributes": ["id", "message", "name", "message"]
                }
              ]
            }]
          }
        }"##;

        let nodes = classify_cdp_dom_json(cdp_json).expect("valid CDP JSON");
        assert_eq!(nodes.len(), 7);

        // Search boxes (`name="q"`, `type="search"`, `id="query"`, `role="searchbox"`):
        for node in &nodes[..4] {
            assert!(
                node.is_search_like,
                "expected node {node:?} to be marked isSearchLike",
            );
        }

        // Non-search `<input name="subject">` and multi-line `<textarea>` controls:
        for node in &nodes[4..7] {
            assert!(
                !node.is_search_like,
                "expected node {node:?} NOT to be marked isSearchLike",
            );
        }

        // Verify camelCase JSON serialization emits `"isSearchLike":true`:
        let serialized = serde_json::to_string(&nodes[0]).expect("serialize node");
        assert!(serialized.contains("\"isSearchLike\":true"));
    }

    #[test]
    fn classifies_wizardswap_pablocash_and_hiddenswap_xmr_inputs_while_suppressing_btc_refund_and_source(
    ) {
        // 1. WizardSwap `.onion` BTC -> XMR swap form
        let wizardswap_cdp = r##"{
          "root": {
            "nodeId": 1, "nodeType": 9, "nodeName": "#document",
            "children": [{
              "nodeId": 2, "nodeType": 1, "nodeName": "FORM", "localName": "form",
              "attributes": ["id", "wizardswap", "method", "POST"],
              "children": [
                {
                  "nodeId": 10, "backendNodeId": 110, "nodeType": 1, "nodeName": "INPUT", "localName": "input",
                  "attributes": ["type", "text", "id", "refund_address", "name", "refund_address", "placeholder", "Enter a Bitcoin refund address"]
                },
                {
                  "nodeId": 11, "backendNodeId": 111, "nodeType": 1, "nodeName": "INPUT", "localName": "input",
                  "attributes": ["type", "text", "id", "address", "name", "address", "placeholder", "Enter the recipient's Monero address"]
                }
              ]
            }]
          }
        }"##;
        let ws_nodes = classify_cdp_dom_json(wizardswap_cdp).unwrap();
        assert_eq!(ws_nodes.len(), 1);
        assert_eq!(ws_nodes[0].node_id, 11);
        assert_eq!(ws_nodes[0].kind, "xmr_address_input");
        assert_eq!(ws_nodes[0].action_label, "Insert XMR Subaddress");

        // 2. Pablo Cash `.onion` BTC -> XMR swap form
        let pablocash_cdp = r##"{
          "root": {
            "nodeId": 1, "nodeType": 9, "nodeName": "#document",
            "children": [{
              "nodeId": 2, "nodeType": 1, "nodeName": "FORM", "localName": "form",
              "attributes": ["id", "deskForm"],
              "children": [
                {
                  "nodeId": 10, "nodeType": 1, "nodeName": "INPUT", "localName": "input",
                  "attributes": ["type", "hidden", "name", "recv_coin", "value", "asset:XMR"]
                },
                {
                  "nodeId": 11, "nodeType": 1, "nodeName": "INPUT", "localName": "input",
                  "attributes": ["type", "text", "id", "originValue", "name", "send_qty", "value", "0.01"]
                },
                {
                  "nodeId": 12, "nodeType": 1, "nodeName": "LABEL", "localName": "label",
                  "attributes": ["for", "payoutAddress"],
                  "children": [{ "nodeId": 13, "nodeType": 3, "nodeName": "#text", "nodeValue": "Recipient wallet *" }]
                },
                {
                  "nodeId": 14, "backendNodeId": 114, "nodeType": 1, "nodeName": "INPUT", "localName": "input",
                  "attributes": ["type", "text", "id", "payoutAddress", "name", "payout_addr", "placeholder", "Paste the payout address"]
                },
                {
                  "nodeId": 15, "nodeType": 1, "nodeName": "INPUT", "localName": "input",
                  "attributes": ["type", "text", "id", "proofAnswer", "name", "seal_code"]
                }
              ]
            }]
          }
        }"##;
        let pc_nodes = classify_cdp_dom_json(pablocash_cdp).unwrap();
        assert_eq!(pc_nodes.len(), 1);
        assert_eq!(pc_nodes[0].node_id, 14);
        assert_eq!(pc_nodes[0].kind, "xmr_address_input");
        assert_eq!(pc_nodes[0].action_label, "Insert XMR Subaddress");

        // 3. HiddenSwap BTC -> XMR `<textarea rows="1">` swap form (plus closed `<dialog>`)
        let hiddenswap_cdp = r##"{
          "root": {
            "nodeId": 1, "nodeType": 9, "nodeName": "#document",
            "children": [{
              "nodeId": 2, "nodeType": 1, "nodeName": "FORM", "localName": "form",
              "attributes": ["class", "swap", "data-swap", ""],
              "children": [
                {
                  "nodeId": 10, "nodeType": 1, "nodeName": "LABEL", "localName": "label",
                  "attributes": ["for", "pair-address"],
                  "children": [{ "nodeId": 11, "nodeType": 3, "nodeName": "#text", "nodeValue": "Destination address XMR Monero" }]
                },
                {
                  "nodeId": 12, "nodeType": 1, "nodeName": "DIV", "localName": "div",
                  "attributes": ["class", "input-group", "data-net", "xmr", "data-address-group", ""],
                  "children": [{
                    "nodeId": 13, "backendNodeId": 113, "nodeType": 1, "nodeName": "TEXTAREA", "localName": "textarea",
                    "attributes": ["id", "pair-address", "name", "address", "rows", "1", "placeholder", "Enter your wallet address", "data-address", ""]
                  }]
                },
                {
                  "nodeId": 20, "nodeType": 1, "nodeName": "DIV", "localName": "div",
                  "attributes": ["class", "input-group", "data-net", "btc", "data-source-group", ""],
                  "children": [{
                    "nodeId": 21, "backendNodeId": 121, "nodeType": 1, "nodeName": "TEXTAREA", "localName": "textarea",
                    "attributes": ["id", "pair-source", "name", "sourceAddress", "rows", "1", "placeholder", "The wallet you send from", "data-source-address", ""]
                  }]
                },
                {
                  "nodeId": 30, "nodeType": 1, "nodeName": "DIV", "localName": "div",
                  "attributes": ["class", "input-group", "data-net", "btc", "data-refund-group", ""],
                  "children": [{
                    "nodeId": 31, "backendNodeId": 131, "nodeType": 1, "nodeName": "TEXTAREA", "localName": "textarea",
                    "attributes": ["id", "pair-refund", "name", "refundAddress", "rows", "1", "placeholder", "Your wallet for a refund", "data-refund-address", ""]
                  }]
                },
                {
                  "nodeId": 40, "nodeType": 1, "nodeName": "DIALOG", "localName": "dialog",
                  "attributes": ["id", "pair-coin-dialog"],
                  "children": [{
                    "nodeId": 41, "nodeType": 1, "nodeName": "INPUT", "localName": "input",
                    "attributes": ["type", "search", "placeholder", "Name, ticker or network"]
                  }]
                }
              ]
            }]
          }
        }"##;
        let hs_nodes = classify_cdp_dom_json(hiddenswap_cdp).unwrap();
        assert_eq!(hs_nodes.len(), 1);
        assert_eq!(hs_nodes[0].node_id, 13);
        assert_eq!(hs_nodes[0].tag, "textarea");
        assert_eq!(hs_nodes[0].kind, "xmr_address_input");
        assert_eq!(hs_nodes[0].action_label, "Insert XMR Subaddress");
    }

    #[test]
    fn classifies_btc_deposit_addresses_and_monero_uris_across_order_pages() {
        let xmr_sub = "82ffYh7xRhFBvBHEHGV8TNRBoYucBFNoyYnwrJJjnMGpHKPWosZeB4BDr7pBG4DCux1WKepdAV6rRTimi1LqWMacF88hvQm";

        // 1. WizardSwap deposit order page (`span#amount_a` + `input#deposit_address[readonly]` + `input#receiver_address[readonly]`)
        let ws_order = format!(
            r##"{{
              "root": {{
                "nodeId": 1, "nodeType": 9, "nodeName": "#document",
                "children": [{{
                  "nodeId": 2, "nodeType": 1, "nodeName": "DIV", "localName": "div",
                  "children": [
                    {{
                      "nodeId": 10, "nodeType": 1, "nodeName": "SPAN", "localName": "span",
                      "attributes": ["id", "amount_a"],
                      "children": [{{ "nodeId": 11, "nodeType": 3, "nodeName": "#text", "nodeValue": "0.01" }}]
                    }},
                    {{
                      "nodeId": 12, "backendNodeId": 212, "nodeType": 1, "nodeName": "INPUT", "localName": "input",
                      "attributes": ["type", "text", "id", "deposit_address", "name", "deposit_address", "readonly", "readonly", "value", "bc1qwxcndrucjwn7f4u2vrq2lx0hv543sjz57e77q2"]
                    }},
                    {{
                      "nodeId": 13, "nodeType": 1, "nodeName": "INPUT", "localName": "input",
                      "attributes": ["type", "text", "id", "receiver_address", "readonly", "readonly", "value", "{xmr_sub}"]
                    }},
                    {{
                      "nodeId": 14, "nodeType": 1, "nodeName": "INPUT", "localName": "input",
                      "attributes": ["type", "text", "name", "capture_txid", "placeholder", "Deposit TXID"]
                    }}
                  ]
                }}]
              }}
            }}"##
        );
        let ws_nodes = classify_cdp_dom_json(&ws_order).unwrap();
        assert_eq!(ws_nodes.len(), 1);
        assert_eq!(ws_nodes[0].node_id, 12);
        assert_eq!(ws_nodes[0].kind, "btc_deposit_address");
        assert_eq!(ws_nodes[0].action_label, "Copy BTC Deposit");
        assert_eq!(
            ws_nodes[0].text,
            "bc1qwxcndrucjwn7f4u2vrq2lx0hv543sjz57e77q2"
        );
        assert_eq!(ws_nodes[0].fingerprint_hint, "0.01");

        // 2. HiddenSwap deposit order page + `monero:` link + `bitcoin:` link
        let hs_order = format!(
            r##"{{
              "root": {{
                "nodeId": 1, "nodeType": 9, "nodeName": "#document",
                "children": [{{
                  "nodeId": 2, "nodeType": 1, "nodeName": "DIV", "localName": "div",
                  "children": [
                    {{
                      "nodeId": 20, "nodeType": 1, "nodeName": "DIV", "localName": "div",
                      "attributes": ["class", "deposit-amount"],
                      "children": [{{
                        "nodeId": 21, "nodeType": 1, "nodeName": "SPAN", "localName": "span",
                        "attributes": ["class", "amount mono"],
                        "children": [{{ "nodeId": 22, "nodeType": 3, "nodeName": "#text", "nodeValue": "0.015" }}]
                      }}]
                    }},
                    {{
                      "nodeId": 25, "nodeType": 1, "nodeName": "DIV", "localName": "div",
                      "attributes": ["class", "deposit-address"],
                      "children": [{{
                        "nodeId": 26, "backendNodeId": 226, "nodeType": 1, "nodeName": "P", "localName": "p",
                        "attributes": ["class", "address mono"],
                        "children": [{{ "nodeId": 27, "nodeType": 3, "nodeName": "#text", "nodeValue": "3AQ2gsD1FG5vhFiGGzTZd7N57WwDJj4SSi" }}]
                      }}]
                    }},
                    {{
                      "nodeId": 30, "backendNodeId": 230, "nodeType": 1, "nodeName": "A", "localName": "a",
                      "attributes": ["href", "monero:{xmr_sub}?tx_amount=1.25"],
                      "children": [{{ "nodeId": 31, "nodeType": 3, "nodeName": "#text", "nodeValue": "Pay with Monero" }}]
                    }},
                    {{
                      "nodeId": 35, "backendNodeId": 235, "nodeType": 1, "nodeName": "CODE", "localName": "code",
                      "children": [{{ "nodeId": 36, "nodeType": 3, "nodeName": "#text", "nodeValue": "monero:{xmr_sub}?tx_amount=0.75" }}]
                    }}
                  ]
                }}]
              }}
            }}"##
        );
        let hs_nodes = classify_cdp_dom_json(&hs_order).unwrap();
        assert_eq!(hs_nodes.len(), 3);
        assert_eq!(hs_nodes[0].node_id, 26);
        assert_eq!(hs_nodes[0].kind, "btc_deposit_address");
        assert_eq!(hs_nodes[0].text, "3AQ2gsD1FG5vhFiGGzTZd7N57WwDJj4SSi");
        assert_eq!(hs_nodes[0].fingerprint_hint, "0.015");

        assert_eq!(hs_nodes[1].node_id, 30);
        assert_eq!(hs_nodes[1].kind, "monero_uri");
        assert_eq!(hs_nodes[1].action_label, "Pay XMR");
        assert_eq!(hs_nodes[1].fingerprint_hint, "1.25");

        assert_eq!(hs_nodes[2].node_id, 35);
        assert_eq!(hs_nodes[2].kind, "monero_uri");
        assert_eq!(hs_nodes[2].action_label, "Pay XMR");
        assert_eq!(hs_nodes[2].fingerprint_hint, "0.75");
    }
}
