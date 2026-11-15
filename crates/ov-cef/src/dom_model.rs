//! Standalone HTML parser, PGP field/block classifier, and deterministic
//! CSS selector / synthetic node-ID generator.
//!
//! This module has **zero `libcef.so` dependency** and never executes
//! JavaScript.  It powers both:
//!
//!   1. **Browser-process DOM anchoring** via Chromium DevTools `DOM.*`
//!      (`DOM.getOuterHTML` -> [`scan_html`] / [`analyze_html`] ->
//!      `DOM.querySelector` + `DOM.getBoxModel` + `DOM.setAttributeValue`),
//!      and
//!   2. **Headless verification & render-process classification** across host
//!      and Android builds.
//!
//! # The styles that are written into the page
//!
//! These are the colours of the interface **inside the page**: they are
//! written as an inline style on the node, so that they survive the CSS of
//! the hidden service.
//!
//! | what it is | the style |
//! |---|---|
//! | a field that can be encrypted | a cyan outline |
//! | the focused field | a green outline and an inset shadow |
//! | a PGP message that was found | a dashed amber outline |
//! | a PGP public key that was found | a dotted violet outline |
//! | a field that Trap has changed | a green outline and a green wash |

use std::collections::HashMap;

use crate::ipc::{DomFieldMetadata, DomScanReport, FieldKind, NodeRect};

/// Attribute that carries the synthetic node id.
pub const NODE_ID_ATTRIBUTE: &str = "data-ov-node-id";

/// Attribute that marks the focused node.
pub const FOCUSED_ATTRIBUTE: &str = "data-ov-focused";

/// Opening marker of an armored encrypted or inline-signed PGP message.
pub const PGP_MESSAGE_BEGIN: &str = "-----BEGIN PGP MESSAGE-----";

/// Closing marker of an armored PGP message.
pub const PGP_MESSAGE_END: &str = "-----END PGP MESSAGE-----";

/// Opening marker of a cleartext-signed PGP message.
pub const PGP_SIGNED_MESSAGE_BEGIN: &str = "-----BEGIN PGP SIGNED MESSAGE-----";

/// Opening marker of a detached or cleartext PGP signature section.
pub const PGP_SIGNATURE_BEGIN: &str = "-----BEGIN PGP SIGNATURE-----";

/// Closing marker of a detached or cleartext PGP signature section.
pub const PGP_SIGNATURE_END: &str = "-----END PGP SIGNATURE-----";

/// Opening marker of an armored PGP public key block.
pub const PGP_PUBLIC_KEY_BEGIN: &str = "-----BEGIN PGP PUBLIC KEY BLOCK-----";

/// Closing marker of an armored PGP public key block.
pub const PGP_PUBLIC_KEY_END: &str = "-----END PGP PUBLIC KEY BLOCK-----";

/// Maximum number of Unicode scalar values kept in `value_preview`.
pub const PREVIEW_CHARS: usize = 120;

/// Style of an encryptable field (text area or text input).
pub const STYLE_ENCRYPTABLE: &str = "outline: 2px solid #00E5FF !important; \
outline-offset: -2px !important; background-color: rgba(0, 229, 255, 0.06) !important;";

/// Style of the focused, active field.
pub const STYLE_FOCUSED: &str = "outline: 3px solid #00FF66 !important; \
box-shadow: inset 0 0 0 2px #00FF66 !important;";

/// Style of a detected PGP ciphertext or signed message.
pub const STYLE_PGP_MESSAGE: &str = "outline: 2px dashed #FFB300 !important; \
background-color: rgba(255, 179, 0, 0.08) !important;";

/// Style of a detected PGP public key.
pub const STYLE_PGP_PUBLIC_KEY: &str = "outline: 2px dotted #CE93D8 !important;";

/// Style of a field whose content was encrypted or decrypted by Trap.
pub const STYLE_VERIFIED: &str = "outline: 2px solid #00FF66 !important; \
outline-offset: -2px !important; background-color: rgba(0, 255, 102, 0.06) !important;";

/// Maximum DOM traversal depth; deeper subtrees are ignored to keep scans
/// bounded on pathological markup.
const MAX_DEPTH: usize = 64;

/// A node of a parsed HTML document.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct HtmlNode {
    /// Lowercase tag name (empty for text nodes and the synthetic document root).
    pub tag: String,
    /// Decoded `(lowercase_name, value)` attributes in source order.
    pub attributes: Vec<(String, String)>,
    /// Decoded text payload (non-empty only for text nodes).
    pub text: String,
    /// Child nodes (elements and text nodes) in source order.
    pub children: Vec<HtmlNode>,
}

impl HtmlNode {
    /// Construct a text node.
    pub fn text_node(text: &str) -> Self {
        Self {
            text: text.to_string(),
            ..Self::default()
        }
    }

    /// `true` when the node is a text node.
    pub fn is_text(&self) -> bool {
        self.tag.is_empty() && !self.text.is_empty()
    }

    /// `true` when the node is an element node.
    pub fn is_element(&self) -> bool {
        !self.tag.is_empty()
    }

    /// Value of an attribute (empty string when absent).
    pub fn attribute(&self, name: &str) -> String {
        self.attribute_opt(name)
            .map(str::to_string)
            .unwrap_or_default()
    }

    /// Borrowed value of an attribute (`None` when absent).
    pub fn attribute_opt(&self, name: &str) -> Option<&str> {
        self.attributes
            .iter()
            .find(|(key, _)| key.eq_ignore_ascii_case(name))
            .map(|(_, value)| value.as_str())
    }

    /// `true` when the attribute is present.
    pub fn has_attribute(&self, name: &str) -> bool {
        self.attributes
            .iter()
            .any(|(key, _)| key.eq_ignore_ascii_case(name))
    }

    /// Text of direct text children, preserving direct `<br>` elements as `\n`.
    pub fn direct_text(&self) -> String {
        let mut out = String::new();
        for child in &self.children {
            if child.is_text() {
                out.push_str(&child.text);
            } else if child.tag.eq_ignore_ascii_case("br") {
                out.push('\n');
            }
        }
        out
    }

    /// Rendered text of the node and all of its descendants (`<br>` becomes `\n`).
    pub fn text_content(&self) -> String {
        let mut text = String::new();
        collect_text(self, &mut text);
        text
    }

    /// Direct text children of the node.
    pub fn text_children(&self) -> Vec<&HtmlNode> {
        self.children
            .iter()
            .filter(|child| child.is_text())
            .collect()
    }

    /// Direct element children of the node.
    pub fn elements(&self) -> Vec<&HtmlNode> {
        self.children
            .iter()
            .filter(|child| child.is_element())
            .collect()
    }
}

/// Collect the text of a node and its descendants, mapping `<br>` to `\n`.
fn collect_text(node: &HtmlNode, out: &mut String) {
    if node.is_text() {
        out.push_str(&node.text);
        return;
    }
    if node.tag.eq_ignore_ascii_case("br") {
        out.push('\n');
        return;
    }
    for child in &node.children {
        collect_text(child, out);
    }
}

/// Classify the PGP roles of an `HtmlNode` within a parsed document tree.
///
/// A node can carry more than one role (for example, an editable `<textarea>`
/// that already holds an armored PGP message is both `Textarea` and
/// `PgpMessage`).  Container elements (`<pre>`, `<div>`, `<p>`, `<code>`, ...)
/// match a PGP block only when no descendant element independently contains a
/// complete PGP block, ensuring that only the innermost block element is
/// anchored.
pub fn classify(node: &HtmlNode, inside_match: bool) -> Vec<FieldKind> {
    if is_ignored_subtree(&node.tag) {
        return Vec::new();
    }

    let tag_upper = node.tag.to_ascii_uppercase();
    let probe = if tag_upper == "INPUT" {
        node.attribute("value")
    } else if tag_upper == "TEXTAREA"
        || (is_pgp_container(&tag_upper) && !any_descendant_element_has_pgp_block(node))
    {
        node.text_content()
    } else {
        String::new()
    };

    classify_with_text(node, &probe, inside_match)
}

/// Classify a node when the caller supplies the probe text explicitly (used by
/// both [`classify`] and the CEF render-process visitor).
pub fn classify_with_text(node: &HtmlNode, probe: &str, inside_match: bool) -> Vec<FieldKind> {
    if is_ignored_subtree(&node.tag) {
        return Vec::new();
    }

    let mut kinds = Vec::new();
    let tag = node.tag.to_ascii_uppercase();
    let disabled = node.has_attribute("disabled");
    let readonly = node.has_attribute("readonly");

    if tag == "TEXTAREA" {
        if !disabled && !readonly {
            kinds.push(FieldKind::Textarea);
        }
    } else if tag == "INPUT" {
        let input_type = node.attribute("type").trim().to_ascii_lowercase();
        if (input_type.is_empty() || input_type == "text" || input_type == "search")
            && !disabled
            && !readonly
        {
            kinds.push(FieldKind::TextInput);
        }
    }

    // A node nested inside an already detected PGP block must not be reported
    // a second time as another PGP block.
    if !inside_match {
        if let Some(pgp_kind) = detect_pgp_block_kind(probe) {
            kinds.push(pgp_kind);
        }
    }

    kinds
}

/// Detect whether `text` contains a complete OpenPGP message (encrypted or
/// cleartext-signed) or public key block.
pub fn detect_pgp_block_kind(text: &str) -> Option<FieldKind> {
    if extract_armored_block(text, FieldKind::PgpMessage).is_some() {
        Some(FieldKind::PgpMessage)
    } else if extract_armored_block(text, FieldKind::PgpPublicKey).is_some() {
        Some(FieldKind::PgpPublicKey)
    } else {
        None
    }
}

/// Extract a clean, self-contained OpenPGP ASCII-armored block of `kind` from
/// `text`, stripping any surrounding HTML page prose while preserving internal
/// headers, blank lines, body lines, and armor checksum.
pub fn extract_armored_block(text: &str, kind: FieldKind) -> Option<String> {
    match kind {
        FieldKind::PgpMessage => extract_between_markers(text, PGP_MESSAGE_BEGIN, PGP_MESSAGE_END)
            .or_else(|| {
                let candidate =
                    extract_between_markers(text, PGP_SIGNED_MESSAGE_BEGIN, PGP_SIGNATURE_END)?;
                if candidate.contains(PGP_SIGNATURE_BEGIN) {
                    Some(candidate)
                } else {
                    None
                }
            }),
        FieldKind::PgpPublicKey => {
            extract_between_markers(text, PGP_PUBLIC_KEY_BEGIN, PGP_PUBLIC_KEY_END)
        }
        FieldKind::Textarea | FieldKind::TextInput => None,
    }
}

/// Extract the armored block when `kinds` includes `PgpMessage` or
/// `PgpPublicKey`.
pub fn extract_armored_for_kinds(text: &str, kinds: &[FieldKind]) -> Option<String> {
    if kinds.contains(&FieldKind::PgpMessage) {
        extract_armored_block(text, FieldKind::PgpMessage)
    } else if kinds.contains(&FieldKind::PgpPublicKey) {
        extract_armored_block(text, FieldKind::PgpPublicKey)
    } else {
        None
    }
}

fn extract_between_markers(text: &str, begin_marker: &str, end_marker: &str) -> Option<String> {
    let start = text.find(begin_marker)?;
    let after_begin = start + begin_marker.len();
    let rel_end = text[after_begin..].find(end_marker)?;
    let end = after_begin + rel_end + end_marker.len();
    let raw_slice = &text[start..end];
    let normalized = raw_slice.replace("\r\n", "\n").replace('\r', "\n");
    // Require non-empty body between BEGIN and END markers.
    let body = normalized
        .strip_prefix(begin_marker)?
        .strip_suffix(end_marker)?
        .trim();
    if body.is_empty() {
        return None;
    }
    Some(normalized)
}

fn any_descendant_element_has_pgp_block(node: &HtmlNode) -> bool {
    for child in &node.children {
        if !child.is_element() || is_ignored_subtree(&child.tag) {
            continue;
        }
        if detect_pgp_block_kind(&child.text_content()).is_some() {
            return true;
        }
    }
    false
}

/// `true` for subtrees whose text is metadata, CSS, or unexecuted script rather
/// than visible page content.
pub fn is_ignored_subtree(tag: &str) -> bool {
    matches!(
        tag.to_ascii_lowercase().as_str(),
        "script" | "style" | "noscript" | "template" | "head" | "title" | "svg"
    )
}

/// The inline style of a node.
///
/// The style is set as an inline attribute, so that the page cannot undo it
/// with its own stylesheet and so that no script is needed.
pub fn inline_style(kinds: &[FieldKind], focused: bool) -> &'static str {
    if focused {
        STYLE_FOCUSED
    } else if kinds.contains(&FieldKind::PgpMessage) {
        STYLE_PGP_MESSAGE
    } else if kinds.contains(&FieldKind::PgpPublicKey) {
        STYLE_PGP_PUBLIC_KEY
    } else {
        STYLE_ENCRYPTABLE
    }
}

/// The style of a node that was changed by Trap.
pub fn verified_style() -> &'static str {
    STYLE_VERIFIED
}

/// Merge Trap's inline highlight `ov_style` onto an element's existing `style`
/// attribute idempotently (preserving page styles, ensuring a `;` separator,
/// and replacing any prior Trap outline/wash rather than duplicating it).
pub fn merge_inline_style(existing: &str, ov_style: &str) -> String {
    let mut cleaned = existing.to_string();
    for known in [
        STYLE_ENCRYPTABLE,
        STYLE_FOCUSED,
        STYLE_PGP_MESSAGE,
        STYLE_PGP_PUBLIC_KEY,
        STYLE_VERIFIED,
    ] {
        cleaned = cleaned.replace(known, "");
    }
    let trimmed = cleaned.trim().trim_end_matches(';').trim();
    if trimmed.is_empty() {
        ov_style.to_string()
    } else {
        format!("{trimmed}; {ov_style}")
    }
}

/// Shorten a value for UI preview without splitting multi-byte UTF-8 scalars.
pub fn preview_value(value: &str) -> String {
    let mut chars = value.chars();
    let head: String = chars.by_ref().take(PREVIEW_CHARS).collect();
    if chars.next().is_some() {
        format!("{head}…")
    } else {
        head
    }
}

/// A deterministic synthetic id for a node.
///
/// The id is derived from the position of the node in the document, so that
/// the same node keeps its id across rescans and the native UI can anchor
/// its chips on a stable identifier.
pub fn synthetic_id(path: &[usize], tag: &str) -> String {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    let mut mix = |byte: u64| {
        hash ^= byte;
        hash = hash.wrapping_mul(0x0100_0000_01b3);
    };
    for index in path {
        mix(*index as u64);
    }
    mix(0xff);
    for byte in tag.to_ascii_lowercase().as_bytes() {
        mix(*byte as u64);
    }
    format!("ov-{hash:016x}")
}

/// Escape an arbitrary identifier per CSSOM `CSS.escape` rules so `#<ident>`
/// is always a valid CSS selector in `DOM.querySelector`.
pub fn css_escape_ident(ident: &str) -> String {
    let mut out = String::with_capacity(ident.len() + 4);
    let chars: Vec<char> = ident.chars().collect();
    for (idx, &ch) in chars.iter().enumerate() {
        if ch == '\0' {
            out.push('\u{FFFD}');
        } else if ('\x01'..='\x1f').contains(&ch)
            || ch == '\x7f'
            || (idx == 0 && ch.is_ascii_digit())
            || (idx == 1 && ch.is_ascii_digit() && chars[0] == '-')
        {
            out.push_str(&format!("\\{:x} ", ch as u32));
        } else if idx == 0 && ch == '-' && chars.len() == 1 {
            out.push_str("\\-");
        } else if ch >= '\u{0080}' || ch.is_ascii_alphanumeric() || ch == '-' || ch == '_' {
            out.push(ch);
        } else {
            out.push('\\');
            out.push(ch);
        }
    }
    out
}

/// How a value is written into a node.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WriteTarget {
    /// The `value` attribute of a form control (`<input>` or `<textarea>`).
    ValueAttribute,
    /// The text node of a block element.
    TextNode,
    /// The input path of the browser: the control has to be focused.
    InputPath,
}

/// Decide how a value is written into a node.
pub fn write_target(node: &HtmlNode) -> WriteTarget {
    let tag = node.tag.to_ascii_uppercase();
    if tag == "TEXTAREA" || tag == "INPUT" {
        return WriteTarget::ValueAttribute;
    }
    if node.is_text() {
        return WriteTarget::TextNode;
    }
    if !node.text_children().is_empty() {
        return WriteTarget::TextNode;
    }
    WriteTarget::InputPath
}

/// Read the logical value of a node (`value` attribute on `<input>`, rendered
/// text content on `<textarea>` and PGP blocks).
pub fn node_value(node: &HtmlNode) -> String {
    if node.tag.eq_ignore_ascii_case("input") {
        node.attribute("value")
    } else {
        node.text_content()
    }
}

/// Primary role chosen when a node has multiple roles:
/// editable form controls (`Textarea` / `TextInput`) that already hold an
/// armored PGP message/public key prioritize the actionable PGP role when
/// read-only, or retain `kinds[0]` while exposing all roles in `kinds`.
fn primary_kind(kinds: &[FieldKind]) -> FieldKind {
    kinds[0]
}

/// A node detected by the standalone DOM PGP classifier.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DetectedField {
    /// Deterministic synthetic id (`ov-<16hex>`, or existing `data-ov-node-id`).
    pub node_id: String,
    /// Primary role of the node.
    pub kind: FieldKind,
    /// All roles of the node (e.g. `[Textarea, PgpMessage]` when an editable
    /// `<textarea>` already holds an armored PGP message).
    pub kinds: Vec<FieldKind>,
    /// Lowercase HTML tag name.
    pub tag: String,
    /// Preferred deterministic CSS selector for `DOM.querySelector` (`#<id>`
    /// when `id` is unique in the document, otherwise `structural_selector`).
    pub css_selector: String,
    /// Exact root-to-element `:nth-of-type(k)` child-combinator CSS selector.
    pub structural_selector: String,
    /// HTML `id` attribute when non-empty.
    pub id_attr: Option<String>,
    /// HTML `name` attribute when non-empty.
    pub name_attr: Option<String>,
    /// Inline CSS highlight style to apply to the element.
    pub style: &'static str,
    /// Strategy for writing a replacement value into the node.
    pub write_target: WriteTarget,
    /// Full current value / text of the node.
    pub value: String,
    /// Truncated preview (`PREVIEW_CHARS` max) of `value`.
    pub value_preview: String,
    /// Cleanly extracted OpenPGP ASCII-armored block when `kinds` includes
    /// `PgpMessage` or `PgpPublicKey`.
    pub armored: Option<String>,
    /// Child-index path from the parsed document root.
    pub path: Vec<usize>,
}

impl DetectedField {
    /// Convert this detected node into [`DomFieldMetadata`] using `rect`
    /// (for example, from `DOM.getBoxModel` in the browser process).
    pub fn to_metadata(&self, rect: NodeRect) -> DomFieldMetadata {
        // When an editable textarea/input already contains a decryptable PGP
        // message or importable key, surface that PGP block role if `armored`
        // is present and the caller has not overridden `kind`.
        DomFieldMetadata {
            node_id: self.node_id.clone(),
            kind: self.kind,
            tag: self.tag.clone(),
            rect,
            value_preview: self.value_preview.clone(),
            armored: self.armored.clone(),
            css_selector: Some(self.css_selector.clone()),
            id_attr: self.id_attr.clone(),
            name_attr: self.name_attr.clone(),
        }
    }
}

/// Parse `html` and return all detected PGP form fields and armored blocks.
///
/// Requires zero `libcef.so` linkage and executes zero JavaScript.
pub fn scan_html(html: &str) -> Vec<DetectedField> {
    let document = parse_html(html);
    scan(&document)
}

/// Parse `html` (e.g. from `DOM.getOuterHTML`) and build a complete
/// [`DomScanReport`] with document `<title>`, deterministic node IDs, unique
/// CSS selectors, previews, and extracted armored OpenPGP blocks.
pub fn analyze_html(html: &str) -> DomScanReport {
    let document = parse_html(html);
    analyze_document(&document, html)
}

/// Build a [`DomScanReport`] from an already-parsed [`HtmlNode`] tree.
pub fn analyze_document(root: &HtmlNode, markup: &str) -> DomScanReport {
    let detected = scan(root);
    let fields = detected
        .iter()
        .map(|field| field.to_metadata(NodeRect::default()))
        .collect();
    DomScanReport {
        title: document_title(root),
        fields,
        markup: markup.to_string(),
    }
}

/// Extract the trimmed `<title>` text of a parsed HTML document.
pub fn document_title(root: &HtmlNode) -> String {
    find_by_tag(root, "title")
        .map(|node| node.text_content().trim().to_string())
        .unwrap_or_default()
}

/// Scan a parsed document and report every PGP-relevant control and block.
pub fn scan(root: &HtmlNode) -> Vec<DetectedField> {
    let mut id_counts = HashMap::new();
    count_ids(root, &mut id_counts);

    let mut found = Vec::new();
    let mut path = Vec::new();
    let mut selector_steps = Vec::new();
    walk(
        root,
        &id_counts,
        &mut path,
        &mut selector_steps,
        0,
        false,
        &mut found,
    );
    found
}

fn count_ids(node: &HtmlNode, counts: &mut HashMap<String, usize>) {
    if node.is_element() {
        if let Some(id) = node.attribute_opt("id") {
            let trimmed = id.trim();
            if !trimmed.is_empty() {
                *counts.entry(trimmed.to_string()).or_insert(0) += 1;
            }
        }
    }
    for child in &node.children {
        count_ids(child, counts);
    }
}

/// Recursive walk of the standalone DOM scan.
fn walk(
    node: &HtmlNode,
    id_counts: &HashMap<String, usize>,
    path: &mut Vec<usize>,
    selector_steps: &mut Vec<String>,
    depth: usize,
    inside_match: bool,
    found: &mut Vec<DetectedField>,
) {
    if depth > MAX_DEPTH {
        return;
    }
    if is_ignored_subtree(&node.tag) {
        return;
    }

    let mut child_inside = inside_match;
    if node.is_element() {
        let kinds = classify(node, inside_match);
        if !kinds.is_empty() {
            let existing_id = node.attribute(NODE_ID_ATTRIBUTE);
            let node_id = if existing_id.starts_with("ov-") {
                existing_id
            } else {
                synthetic_id(path, &node.tag)
            };

            let id_attr = node
                .attribute_opt("id")
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_string);
            let name_attr = node
                .attribute_opt("name")
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_string);

            let structural_selector = selector_steps.join(" > ");
            let css_selector = match id_attr.as_deref() {
                Some(id) if id_counts.get(id).copied() == Some(1) => {
                    format!("#{}", css_escape_ident(id))
                }
                _ => structural_selector.clone(),
            };

            let value = node_value(node);
            let value_preview = preview_value(&value);
            let armored = extract_armored_for_kinds(&value, &kinds);

            found.push(DetectedField {
                node_id,
                kind: primary_kind(&kinds),
                kinds: kinds.clone(),
                tag: node.tag.clone(),
                css_selector,
                structural_selector,
                id_attr,
                name_attr,
                style: inline_style(&kinds, node.has_attribute(FOCUSED_ATTRIBUTE)),
                write_target: write_target(node),
                value,
                value_preview,
                armored,
                path: path.clone(),
            });
        }
        child_inside = inside_match
            || kinds.contains(&FieldKind::PgpMessage)
            || kinds.contains(&FieldKind::PgpPublicKey);
    }

    // Track 1-based `:nth-of-type(k)` ordinal among element siblings.
    let mut tag_ordinals: HashMap<&str, usize> = HashMap::new();
    for (index, child) in node.children.iter().enumerate() {
        let pushed_selector = if child.is_element() {
            let ord = tag_ordinals
                .entry(child.tag.as_str())
                .and_modify(|n| *n += 1)
                .or_insert(1);
            let step = if depth == 0 && child.tag == "html" && *ord == 1 {
                "html".to_string()
            } else {
                format!("{}:nth-of-type({})", child.tag, *ord)
            };
            selector_steps.push(step);
            true
        } else {
            false
        };

        path.push(index);
        walk(
            child,
            id_counts,
            path,
            selector_steps,
            depth + 1,
            child_inside,
            found,
        );
        path.pop();

        if pushed_selector {
            selector_steps.pop();
        }
    }
}

/// `true` when the tag can carry an armored PGP block.
pub fn is_pgp_container(tag: &str) -> bool {
    matches!(
        tag.to_ascii_uppercase().as_str(),
        "PRE"
            | "CODE"
            | "TEXTAREA"
            | "P"
            | "BLOCKQUOTE"
            | "DIV"
            | "TD"
            | "ARTICLE"
            | "SECTION"
            | "DD"
            | "LI"
            | "MAIN"
            | "SPAN"
    )
}

/// Find the first element with the given tag in depth-first order.
pub fn find_by_tag<'a>(node: &'a HtmlNode, tag: &str) -> Option<&'a HtmlNode> {
    if node.tag.eq_ignore_ascii_case(tag) {
        return Some(node);
    }
    for child in &node.children {
        if let Some(found) = find_by_tag(child, tag) {
            return Some(found);
        }
    }
    None
}

/// Resolve a subset of CSS selectors produced by [`scan`] (`#id` or
/// `tag > child:nth-of-type(k)`) back to the exact [`HtmlNode`] in `root`.
///
/// Useful for verifying round-trip selector uniqueness in pure Rust tests
/// without requiring Blink.
pub fn query_selector<'a>(root: &'a HtmlNode, selector: &str) -> Option<&'a HtmlNode> {
    let trimmed = selector.trim();
    if trimmed.is_empty() {
        return None;
    }
    if let Some(escaped_id) = trimmed.strip_prefix('#') {
        if !escaped_id.contains('>') {
            let unescaped = css_unescape_ident(escaped_id);
            return find_by_id(root, &unescaped);
        }
    }

    let steps: Vec<&str> = trimmed.split('>').map(str::trim).collect();
    let mut current = root;
    for step in steps {
        let (tag, nth) = if let Some((tag_part, rest)) = step.split_once(":nth-of-type(") {
            let n_str = rest.strip_suffix(')')?;
            let n: usize = n_str.parse().ok()?;
            (tag_part.trim(), n)
        } else {
            (step, 1usize)
        };
        if nth == 0 {
            return None;
        }
        let mut seen = 0usize;
        let mut matched = None;
        for child in &current.children {
            if child.is_element() && child.tag.eq_ignore_ascii_case(tag) {
                seen += 1;
                if seen == nth {
                    matched = Some(child);
                    break;
                }
            }
        }
        current = matched?;
    }
    Some(current)
}

fn find_by_id<'a>(node: &'a HtmlNode, target_id: &str) -> Option<&'a HtmlNode> {
    if node.is_element() && node.attribute_opt("id").map(str::trim) == Some(target_id) {
        return Some(node);
    }
    for child in &node.children {
        if let Some(hit) = find_by_id(child, target_id) {
            return Some(hit);
        }
    }
    None
}

fn css_unescape_ident(escaped: &str) -> String {
    let mut out = String::with_capacity(escaped.len());
    let mut chars = escaped.chars().peekable();
    while let Some(ch) = chars.next() {
        if ch == '\\' {
            let mut hex = String::new();
            while hex.len() < 6 {
                if let Some(&next) = chars.peek() {
                    if next.is_ascii_hexdigit() {
                        hex.push(next);
                        chars.next();
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
            if !hex.is_empty() {
                if chars.peek() == Some(&' ') {
                    chars.next();
                }
                if let Ok(code) = u32::from_str_radix(&hex, 16) {
                    if let Some(decoded) = char::from_u32(code) {
                        out.push(decoded);
                        continue;
                    }
                }
            } else if let Some(escaped_ch) = chars.next() {
                out.push(escaped_ch);
                continue;
            }
        }
        out.push(ch);
    }
    out
}

/// Parse the HTML of a page (or `DOM.getOuterHTML` output) into an [`HtmlNode`]
/// tree.
///
/// Handles comments, `<!DOCTYPE ...>`, CDATA, quote-protected `>` inside
/// attributes, self-closing void tags (`<br/>`, `<input .../>`), raw text
/// elements (`<script>`, `<style>`), escapable raw text elements (`<textarea>`,
/// `<title>`), and HTML character entity decoding.
pub fn parse_html(html: &str) -> HtmlNode {
    let mut root = HtmlNode::default();
    let chars: Vec<char> = html.chars().collect();
    let mut index = 0usize;
    let mut stack: Vec<HtmlNode> = Vec::new();

    while index < chars.len() {
        // Inside raw-text (`script`, `style`) or RCDATA (`textarea`, `title`),
        // consume everything up to the matching closing tag without treating
        // `<` as child HTML elements.
        if let Some(open_tag) = stack.last().map(|n| n.tag.as_str()) {
            if is_raw_text_element(open_tag) || is_rcdata_element(open_tag) {
                let is_rcdata = is_rcdata_element(open_tag);
                let close_pos = find_closing_tag(&chars, index, open_tag);
                let text_end = close_pos.unwrap_or(chars.len());
                if text_end > index {
                    let raw_text: String = chars[index..text_end].iter().collect();
                    let decoded = if is_rcdata {
                        decode_html_entities(&raw_text)
                    } else {
                        raw_text
                    };
                    if !decoded.is_empty() {
                        stack
                            .last_mut()
                            .unwrap()
                            .children
                            .push(HtmlNode::text_node(&decoded));
                    }
                }
                if let Some(close_idx) = close_pos {
                    let after_close =
                        find_char(&chars, close_idx, '>').map_or(chars.len(), |pos| pos + 1);
                    let closed = stack.pop().unwrap();
                    push_node(&mut root, &mut stack, closed);
                    index = after_close;
                } else {
                    index = chars.len();
                }
                continue;
            }
        }

        if chars[index] == '<' {
            if html_at_ci(&chars, index, "<!--") {
                index = find_at(&chars, index + 4, "-->").map_or(chars.len(), |end| end + 3);
                continue;
            }
            if html_at_ci(&chars, index, "<![CDATA[") {
                let start = index + 9;
                if let Some(end) = find_at(&chars, start, "]]>") {
                    let cdata: String = chars[start..end].iter().collect();
                    if !cdata.is_empty() {
                        push_node(&mut root, &mut stack, HtmlNode::text_node(&cdata));
                    }
                    index = end + 3;
                } else {
                    let cdata: String = chars[start..].iter().collect();
                    if !cdata.is_empty() {
                        push_node(&mut root, &mut stack, HtmlNode::text_node(&cdata));
                    }
                    index = chars.len();
                }
                continue;
            }
            if html_at_ci(&chars, index, "<!") || html_at_ci(&chars, index, "<?") {
                index = find_char(&chars, index + 2, '>').map_or(chars.len(), |end| end + 1);
                continue;
            }
            if html_at_ci(&chars, index, "</") {
                let end = find_char(&chars, index + 2, '>').unwrap_or(chars.len());
                let name: String = chars[index + 2..end.min(chars.len())]
                    .iter()
                    .collect::<String>()
                    .trim()
                    .trim_end_matches('/')
                    .trim()
                    .to_ascii_lowercase();
                if !name.is_empty() {
                    if let Some(position) = stack.iter().rposition(|node| node.tag == name) {
                        while stack.len() > position {
                            let node = stack.pop().unwrap();
                            push_node(&mut root, &mut stack, node);
                        }
                    }
                }
                index = (end + 1).min(chars.len());
                continue;
            }

            // Opening tag (only if followed by an ASCII letter; stray `<` in
            // unescaped text is treated as literal text rather than eating content).
            if index + 1 < chars.len() && chars[index + 1].is_ascii_alphabetic() {
                let end = find_tag_end(&chars, index + 1).unwrap_or(chars.len());
                let raw: String = chars[index + 1..end.min(chars.len())].iter().collect();
                let (tag, attributes, self_closing) = parse_tag(&raw);
                let node = HtmlNode {
                    tag,
                    attributes,
                    ..HtmlNode::default()
                };
                if is_void_element(&node.tag) || (self_closing && !is_html_non_void(&node.tag)) {
                    push_node(&mut root, &mut stack, node);
                } else {
                    stack.push(node);
                }
                index = (end + 1).min(chars.len());
                continue;
            }

            // Stray `<` not starting a valid tag: emit `<` into text up to next `<`.
            let next_lt = find_char(&chars, index + 1, '<').unwrap_or(chars.len());
            let raw_text: String = chars[index..next_lt].iter().collect();
            let decoded = decode_html_entities(&raw_text);
            if should_keep_text(&decoded, &stack) {
                push_node(&mut root, &mut stack, HtmlNode::text_node(&decoded));
            }
            index = next_lt;
            continue;
        }

        // Regular text node.
        let end = find_char(&chars, index, '<').unwrap_or(chars.len());
        let raw_text: String = chars[index..end].iter().collect();
        let decoded = decode_html_entities(&raw_text);
        if should_keep_text(&decoded, &stack) {
            push_node(&mut root, &mut stack, HtmlNode::text_node(&decoded));
        }
        index = end;
    }

    while let Some(node) = stack.pop() {
        push_node(&mut root, &mut stack, node);
    }

    root
}

fn push_node(root: &mut HtmlNode, stack: &mut [HtmlNode], node: HtmlNode) {
    if let Some(parent) = stack.last_mut() {
        parent.children.push(node);
    } else {
        root.children.push(node);
    }
}

fn should_keep_text(decoded: &str, stack: &[HtmlNode]) -> bool {
    if decoded.is_empty() {
        return false;
    }
    if !decoded.trim().is_empty() {
        return true;
    }
    // Preserve whitespace/newlines inside `<pre>` and `<code>` blocks where
    // blank lines between armor headers and body are significant.
    stack
        .iter()
        .any(|n| matches!(n.tag.as_str(), "pre" | "code"))
}

fn is_raw_text_element(tag: &str) -> bool {
    matches!(tag, "script" | "style")
}

fn is_rcdata_element(tag: &str) -> bool {
    matches!(tag, "textarea" | "title")
}

fn is_html_non_void(tag: &str) -> bool {
    matches!(
        tag,
        "textarea" | "div" | "span" | "p" | "pre" | "code" | "form" | "blockquote" | "a"
    )
}

/// Find a case-insensitive `</tag` followed by whitespace, `/`, or `>`.
fn find_closing_tag(chars: &[char], from: usize, tag: &str) -> Option<usize> {
    let tag_chars: Vec<char> = tag.chars().collect();
    let needed = 2 + tag_chars.len();
    if chars.len() < needed || from + needed > chars.len() {
        return None;
    }
    for i in from..=(chars.len() - needed) {
        if chars[i] == '<' && chars[i + 1] == '/' {
            let matches_tag = chars[i + 2..i + 2 + tag_chars.len()]
                .iter()
                .zip(tag_chars.iter())
                .all(|(a, b)| a.eq_ignore_ascii_case(b));
            if matches_tag {
                let next = chars.get(i + 2 + tag_chars.len()).copied();
                if matches!(next, None | Some('>') | Some('/'))
                    || next.is_some_and(char::is_whitespace)
                {
                    return Some(i);
                }
            }
        }
    }
    None
}

/// Find the closing `>` of an opening tag, ignoring `>` inside `'...'` or `"..."`.
fn find_tag_end(chars: &[char], from: usize) -> Option<usize> {
    let mut quote: Option<char> = None;
    for (idx, &ch) in chars.iter().enumerate().skip(from) {
        match quote {
            Some(q) if ch == q => quote = None,
            Some(_) => {}
            None if ch == '"' || ch == '\'' => quote = Some(ch),
            None if ch == '>' => return Some(idx),
            None => {}
        }
    }
    None
}

fn html_at_ci(chars: &[char], index: usize, needle: &str) -> bool {
    let needle_chars: Vec<char> = needle.chars().collect();
    if index + needle_chars.len() > chars.len() {
        return false;
    }
    chars[index..index + needle_chars.len()]
        .iter()
        .zip(needle_chars.iter())
        .all(|(a, b)| a.eq_ignore_ascii_case(b))
}

fn find_at(chars: &[char], from: usize, needle: &str) -> Option<usize> {
    let needle: Vec<char> = needle.chars().collect();
    if needle.is_empty() || chars.len() < needle.len() || from > chars.len() - needle.len() {
        return None;
    }
    (from..=chars.len() - needle.len())
        .find(|index| chars[*index..*index + needle.len()] == needle[..])
}

fn find_char(chars: &[char], from: usize, needle: char) -> Option<usize> {
    (from..chars.len()).find(|index| chars[*index] == needle)
}

/// Parse the tag name, decoded attributes, and whether the tag ends with `/>`.
fn parse_tag(raw: &str) -> (String, Vec<(String, String)>, bool) {
    let raw = raw.trim();
    let self_closing = raw.ends_with('/');
    let mut chars = raw.chars().peekable();
    let mut name = String::new();
    while let Some(&character) = chars.peek() {
        if character.is_whitespace() || character == '/' {
            break;
        }
        name.push(character);
        chars.next();
    }

    let mut attributes = Vec::new();
    let rest: String = chars.collect();
    let mut remaining = rest.as_str();

    while !remaining.is_empty() {
        let trimmed = remaining.trim_start();
        if trimmed.is_empty() || trimmed == "/" {
            break;
        }
        if let Some(after_slash) = trimmed.strip_prefix('/') {
            remaining = after_slash;
            continue;
        }
        let end = trimmed
            .find(|c: char| c.is_whitespace() || c == '=' || c == '/')
            .unwrap_or(trimmed.len());
        let key = trimmed[..end].to_ascii_lowercase();
        if key.is_empty() {
            break;
        }
        let after = trimmed[end..].trim_start();
        if let Some(after_eq) = after.strip_prefix('=') {
            let after_eq = after_eq.trim_start();
            if let Some(stripped) = after_eq.strip_prefix('"') {
                let close = stripped.find('"').unwrap_or(stripped.len());
                let val = decode_html_entities(&stripped[..close]);
                attributes.push((key, val));
                remaining = &stripped[(close + 1).min(stripped.len())..];
                continue;
            }
            if let Some(stripped) = after_eq.strip_prefix('\'') {
                let close = stripped.find('\'').unwrap_or(stripped.len());
                let val = decode_html_entities(&stripped[..close]);
                attributes.push((key, val));
                remaining = &stripped[(close + 1).min(stripped.len())..];
                continue;
            }
            let close = after_eq
                .find(|c: char| c.is_whitespace() || c == '/')
                .unwrap_or(after_eq.len());
            let val = decode_html_entities(&after_eq[..close]);
            attributes.push((key, val));
            remaining = &after_eq[close..];
            continue;
        }
        attributes.push((key.clone(), key));
        remaining = after;
    }

    (name.to_ascii_lowercase(), attributes, self_closing)
}

/// Decode standard named and numeric (`&#NNN;` / `&#xHHH;`) HTML entities.
pub fn decode_html_entities(input: &str) -> String {
    if !input.contains('&') {
        return input.to_string();
    }
    let mut out = String::with_capacity(input.len());
    let mut rest = input;
    while let Some(amp) = rest.find('&') {
        out.push_str(&rest[..amp]);
        let after_amp = &rest[amp + 1..];
        let semi_limit = after_amp.len().min(16);
        if let Some(semi) = after_amp[..semi_limit].find(';') {
            let entity = &after_amp[..semi];
            if let Some(decoded) = decode_single_entity(entity) {
                out.push(decoded);
                rest = &after_amp[semi + 1..];
                continue;
            }
        }
        out.push('&');
        rest = after_amp;
    }
    out.push_str(rest);
    out
}

fn decode_single_entity(entity: &str) -> Option<char> {
    match entity {
        "amp" | "AMP" => Some('&'),
        "lt" | "LT" => Some('<'),
        "gt" | "GT" => Some('>'),
        "quot" | "QUOT" => Some('"'),
        "apos" | "#39" => Some('\''),
        "nbsp" | "NBSP" => Some('\u{00A0}'),
        _ if entity.starts_with("#x") || entity.starts_with("#X") => {
            let code = u32::from_str_radix(&entity[2..], 16).ok()?;
            if code == 0 {
                Some('\u{FFFD}')
            } else {
                char::from_u32(code)
            }
        }
        _ if entity.starts_with('#') => {
            let code: u32 = entity[1..].parse().ok()?;
            if code == 0 {
                Some('\u{FFFD}')
            } else {
                char::from_u32(code)
            }
        }
        _ => None,
    }
}

/// `true` when the HTML element is void and never has children.
fn is_void_element(tag: &str) -> bool {
    matches!(
        tag,
        "area"
            | "base"
            | "br"
            | "col"
            | "embed"
            | "hr"
            | "img"
            | "input"
            | "link"
            | "meta"
            | "param"
            | "source"
            | "track"
            | "wbr"
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_text_area_is_detected() {
        let html =
            "<html><body><form><textarea name=\"body\">hello</textarea></form></body></html>";
        let root = parse_html(html);
        let area = find_by_tag(&root, "textarea").expect("the text area was not parsed");
        let kinds = classify(area, false);
        assert_eq!(kinds, vec![FieldKind::Textarea]);
    }

    #[test]
    fn a_pgp_block_is_detected() {
        let html = "<pre>-----BEGIN PGP MESSAGE-----\n\nhQEMA\n-----END PGP MESSAGE-----</pre>";
        let root = parse_html(html);
        let block = find_by_tag(&root, "pre").expect("the block was not parsed");
        let kinds = classify(block, false);
        assert_eq!(kinds, vec![FieldKind::PgpMessage]);
    }

    #[test]
    fn the_write_target_of_a_control_is_its_value() {
        let root = parse_html("<input type=\"text\" value=\"x\">");
        let input = find_by_tag(&root, "input").expect("the input was not parsed");
        assert_eq!(write_target(input), WriteTarget::ValueAttribute);
    }

    #[test]
    fn br_separated_and_entity_escaped_pgp_blocks_extract_cleanly() {
        let html = r#"<!doctype html>
<html>
<head><title>Mirror &amp; Canary</title></head>
<body>
  <div class="wrapper">
    <div class="post">
      Server notice:<br/>
      -----BEGIN PGP SIGNED MESSAGE-----<br/>
      Hash: SHA512<br/>
      <br/>
      Contact &lt;ops@example.onion&gt; &amp; verify.<br/>
      -----BEGIN PGP SIGNATURE-----<br/>
      <br/>
      iHUEARYKAB0WIQ==<br/>
      -----END PGP SIGNATURE-----<br/>
      End of notice.
    </div>
  </div>
</body>
</html>"#;

        let report = analyze_html(html);
        assert_eq!(report.title, "Mirror & Canary");
        assert_eq!(report.fields.len(), 1);
        let field = &report.fields[0];
        assert_eq!(field.kind, FieldKind::PgpMessage);
        assert_eq!(field.tag, "div");
        assert_eq!(
            field.css_selector.as_deref(),
            Some("html > body:nth-of-type(1) > div:nth-of-type(1) > div:nth-of-type(1)")
        );
        let armored = field.armored.as_deref().expect("armored block extracted");
        assert!(armored.starts_with(PGP_SIGNED_MESSAGE_BEGIN));
        assert!(armored.ends_with(PGP_SIGNATURE_END));
        assert!(armored.contains("Contact <ops@example.onion> & verify."));
        assert!(!armored.contains("Server notice:"));
        assert!(!armored.contains("End of notice."));
    }

    #[test]
    fn script_and_style_pgp_literals_are_never_classified() {
        let html = r#"<html><head>
<style>.box::after { content: "-----BEGIN PGP PUBLIC KEY BLOCK----- fake -----END PGP PUBLIC KEY BLOCK-----"; }</style>
<script>
  if (1 < 2 && 3 > 1) {
    const fake = `-----BEGIN PGP MESSAGE-----\n\nabc\n-----END PGP MESSAGE-----`;
  }
</script>
</head><body><p>Plain page</p></body></html>"#;

        let fields = scan_html(html);
        assert!(fields.is_empty(), "unexpected fields: {fields:?}");
    }

    #[test]
    fn readonly_textarea_with_pubkey_is_importable_not_encryptable() {
        let html = r#"<html><body>
<textarea id="1pub-key" readonly>-----BEGIN PGP PUBLIC KEY BLOCK-----

mDMEY1234567890==
-----END PGP PUBLIC KEY BLOCK-----</textarea>
<input type="text" placeholder="Paste > key" value="Alice &lt;a@onion&gt;"/>
</body></html>"#;

        let root = parse_html(html);
        let fields = scan(&root);
        assert_eq!(fields.len(), 2);

        let key_box = &fields[0];
        assert_eq!(key_box.kind, FieldKind::PgpPublicKey);
        assert_eq!(key_box.kinds, vec![FieldKind::PgpPublicKey]);
        assert_eq!(key_box.css_selector, r"#\31 pub-key");
        assert_eq!(
            query_selector(&root, &key_box.css_selector).unwrap().tag,
            "textarea"
        );

        let input = &fields[1];
        assert_eq!(input.kind, FieldKind::TextInput);
        assert_eq!(input.value, "Alice <a@onion>");
        assert_eq!(
            query_selector(&root, &input.css_selector).unwrap().tag,
            "input"
        );
    }

    #[test]
    fn merge_inline_style_is_idempotent_and_preserves_semicolon() {
        let once = merge_inline_style("width: 100%", STYLE_ENCRYPTABLE);
        assert_eq!(once, format!("width: 100%; {STYLE_ENCRYPTABLE}"));
        let twice = merge_inline_style(&once, STYLE_FOCUSED);
        assert_eq!(twice, format!("width: 100%; {STYLE_FOCUSED}"));
    }
}
