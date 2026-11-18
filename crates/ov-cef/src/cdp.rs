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
    let mut out = Vec::new();
    walk_cdp(&root, 0, false, &hint_resolver, &mut out);
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

fn walk_cdp<F>(
    node: &CdpNode,
    depth: usize,
    inside_pgp_block: bool,
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

    let mut child_inside = inside_pgp_block;

    if node.is_element() && node.node_id > 0 {
        if let Some((kind, default_label, payload_text, locks_children)) =
            classify_cdp_element(node, &tag, inside_pgp_block)
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
                fingerprint_hint: fp_hint.unwrap_or_default(),
                is_search_like,
            });
            if locks_children {
                child_inside = true;
            }
        }
    }

    // Do not walk inside `<textarea>` or `<input>` children/UA shadow roots for
    // separate nodes; the control itself owns its value.
    if tag == "textarea" || tag == "input" {
        return;
    }

    for child in &node.children {
        walk_cdp(child, depth + 1, child_inside, hint_resolver, out);
    }

    // Traverse open/author shadow roots (never Blink UA `#inner-editor` shadow
    // roots, which belong to form controls handled above).
    for shadow in &node.shadow_roots {
        let is_ua = shadow
            .shadow_root_type
            .as_deref()
            .is_some_and(|t| t.eq_ignore_ascii_case("user-agent"));
        if !is_ua {
            walk_cdp(shadow, depth + 1, child_inside, hint_resolver, out);
        }
    }

    if let Some(doc) = node.content_document.as_deref() {
        walk_cdp(doc, depth + 1, child_inside, hint_resolver, out);
    }
}

fn classify_cdp_element(
    node: &CdpNode,
    tag: &str,
    inside_pgp_block: bool,
) -> Option<(&'static str, &'static str, String, bool)> {
    let disabled = node.has_attr("disabled");
    let readonly = node.has_attr("readonly");

    if tag == "textarea" {
        let live_value = live_form_control_value(node, tag);
        if !inside_pgp_block {
            if let Some(armored) = extract_armored_block(&live_value, FieldKind::PgpMessage) {
                return Some(("pgp_message", "Decrypt Block", armored, true));
            }
            if (disabled || readonly)
                && extract_armored_block(&live_value, FieldKind::PgpPublicKey).is_some()
            {
                let armored = extract_armored_block(&live_value, FieldKind::PgpPublicKey)?;
                return Some(("pgp_public_key", "Import Key", armored, true));
            }
        }
        if !disabled && !readonly {
            if is_pubkey_upload_field(node, &live_value) {
                return Some(("pgp_public_key", "Insert My PubKey", live_value, true));
            }
            return Some(("pgp_textarea", "Encrypt Field", live_value, true));
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
        if !inside_pgp_block {
            if let Some(armored) = extract_armored_block(&live_value, FieldKind::PgpMessage) {
                return Some(("pgp_message", "Decrypt Block", armored, true));
            }
        }
        if !disabled && !readonly {
            if is_pubkey_upload_field(node, &live_value) {
                return Some(("pgp_public_key", "Insert My PubKey", live_value, true));
            }
            return Some(("pgp_textarea", "Encrypt Field", live_value, true));
        }
        return None;
    }

    if is_contenteditable(node) && !disabled && !readonly {
        let live_value = collect_cdp_rendered_text(node);
        if !inside_pgp_block {
            if let Some(armored) = extract_armored_block(&live_value, FieldKind::PgpMessage) {
                return Some(("pgp_message", "Decrypt Block", armored, true));
            }
        }
        return Some(("pgp_textarea", "Encrypt Field", live_value, true));
    }

    if !inside_pgp_block && is_pgp_container(tag) && !any_child_element_has_pgp_block(node) {
        let rendered = collect_cdp_rendered_text(node);
        if let Some(armored) = extract_armored_block(&rendered, FieldKind::PgpMessage) {
            return Some(("pgp_message", "Decrypt Block", armored, true));
        }
        if let Some(armored) = extract_armored_block(&rendered, FieldKind::PgpPublicKey) {
            return Some(("pgp_public_key", "Import Key", armored, true));
        }
    }

    None
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
}
