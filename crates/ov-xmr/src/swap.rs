//! Zero-JavaScript BTC -> XMR swap quote aggregator routed over Tor (`OrangeFren .onion`).

use anyhow::{anyhow, bail, Context, Result};
use base64::{engine::general_purpose::STANDARD as BASE64_STD, Engine as _};
use ov_tor::is_valid_v3_onion_host;
use serde::{Deserialize, Serialize};
use url::Url;

use crate::transport::TorSocksTransport;

/// Verified OrangeFren v3 `.onion` zero-JavaScript aggregator endpoint.
pub const ORANGEFREN_ONION_BASE: &str =
    "http://rnwis2whetqcj4oknksnc5l24jbh33nflunifff3xtjjonnoxu3ld6id.onion";

/// Single parsed zero-JS BTC -> XMR swap quote matching the `OvNative` JSON contract.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BtcSwapQuoteItem {
    pub id: String,
    pub provider: String,
    pub estimated_xmr: String,
    pub rate_xmr_per_btc: String,
    pub spread_tag: String,
    pub never_kyc: bool,
    pub kyc_warning: String,
    pub is_onion_url: bool,
    pub launch_url: String,
}

#[derive(Deserialize)]
struct MonolithDataJson {
    #[serde(default)]
    filter_attributes: FilterAttributes,
}

#[derive(Default, Deserialize)]
struct FilterAttributes {
    #[serde(default)]
    nojs: bool,
    #[serde(default)]
    never_kyc: bool,
}

/// Validate and normalize a positive BTC decimal string (e.g. `"0.01"`).
pub fn normalize_btc_amount_decimal(raw: &str) -> Result<String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        bail!("BTC amount must not be empty");
    }
    if trimmed.starts_with('-') || trimmed.starts_with('+') {
        bail!("BTC amount must be positive");
    }
    let (whole, frac) = match trimmed.split_once('.') {
        Some((w, f)) => {
            if f.contains('.') {
                bail!("invalid BTC amount {trimmed:?}");
            }
            (w, f)
        }
        None => (trimmed, ""),
    };
    if (whole.is_empty() && frac.is_empty())
        || !whole.bytes().all(|b| b.is_ascii_digit())
        || !frac.bytes().all(|b| b.is_ascii_digit())
    {
        bail!("invalid BTC decimal amount {trimmed:?}");
    }
    if frac.len() > 8 {
        bail!("BTC amount exceeds 8 satoshi decimal places");
    }
    let whole_num: u64 = if whole.is_empty() {
        0
    } else {
        whole
            .parse::<u64>()
            .map_err(|_| anyhow!("BTC amount overflows u64"))?
    };
    let mut sats_padded = String::with_capacity(8);
    sats_padded.push_str(frac);
    while sats_padded.len() < 8 {
        sats_padded.push('0');
    }
    let frac_sats: u64 = sats_padded.parse::<u64>().unwrap_or(0);
    let total_sats = whole_num
        .checked_mul(100_000_000)
        .and_then(|w| w.checked_add(frac_sats))
        .ok_or_else(|| anyhow!("BTC amount overflows satoshis"))?;
    if total_sats == 0 {
        bail!("BTC amount must be greater than zero");
    }

    if frac_sats == 0 {
        Ok(whole_num.to_string())
    } else {
        let trimmed_frac = sats_padded.trim_end_matches('0');
        Ok(format!("{whole_num}.{trimmed_frac}"))
    }
}

/// Fetch live zero-JS BTC -> XMR swap quotes from OrangeFren `.onion` over Tor.
pub async fn fetch_btc_xmr_quotes(
    transport: &TorSocksTransport,
    btc_amount_decimal: &str,
    hidden_service_only: bool,
) -> Result<(String, Vec<BtcSwapQuoteItem>)> {
    fetch_btc_xmr_quotes_from_base(
        transport,
        ORANGEFREN_ONION_BASE,
        btc_amount_decimal,
        hidden_service_only,
    )
    .await
}

/// Fetch live zero-JS BTC -> XMR swap quotes from `base_url` (used by tests with a local
/// loopback SOCKS5h mock server as well as live `ORANGEFREN_ONION_BASE`).
pub async fn fetch_btc_xmr_quotes_from_base(
    transport: &TorSocksTransport,
    base_url: &str,
    btc_amount_decimal: &str,
    hidden_service_only: bool,
) -> Result<(String, Vec<BtcSwapQuoteItem>)> {
    let clean_btc = normalize_btc_amount_decimal(btc_amount_decimal)?;
    let url = format!(
        "{}/results/{clean_btc}/BTC@BTC/XMR@XMR/?filters%5Bnojs%5D=1",
        base_url.trim_end_matches('/')
    );
    let resp = transport
        .get(&url, Some(4 * 1024 * 1024))
        .await
        .context("failed to fetch BTC->XMR quotes over Tor")?;
    if resp.status != 200 {
        bail!(
            "quote aggregator returned HTTP {} for BTC->XMR query",
            resp.status
        );
    }
    let html = String::from_utf8_lossy(&resp.body);
    let quotes = parse_orangefren_quotes_html(&html, &clean_btc, hidden_service_only)?;
    Ok((clean_btc, quotes))
}

/// Parse `<monolith class="rate "` cards from OrangeFren server-rendered HTML.
pub fn parse_orangefren_quotes_html(
    html: &str,
    btc_amount: &str,
    _hidden_service_only: bool,
) -> Result<Vec<BtcSwapQuoteItem>> {
    let clean_btc = normalize_btc_amount_decimal(btc_amount)?;
    let mut quotes = Vec::new();
    let mut cursor = 0usize;

    while let Some(rel_start) = html[cursor..].find("<monolith") {
        let start = cursor + rel_start;
        let Some(tag_end_rel) = html[start..].find('>') else {
            break;
        };
        let open_tag = &html[start..=start + tag_end_rel];
        let body_start = start + tag_end_rel + 1;
        let Some(close_rel) = html[body_start..].find("</monolith>") else {
            break;
        };
        let card_inner = &html[body_start..body_start + close_rel];
        cursor = body_start + close_rel + "</monolith>".len();

        let Some(class_val) = extract_attr(open_tag, "class") else {
            continue;
        };
        if !class_val
            .split_whitespace()
            .any(|tok| tok.eq_ignore_ascii_case("rate"))
        {
            continue;
        }

        let Some(data_b64) = extract_attr(open_tag, "data-json") else {
            continue;
        };
        let Ok(json_bytes) = BASE64_STD.decode(decode_entities(&data_b64).trim()) else {
            continue;
        };
        let Ok(meta) = serde_json::from_slice::<MonolithDataJson>(&json_bytes) else {
            continue;
        };
        if !meta.filter_attributes.nojs {
            continue;
        }

        let Some(provider_raw) = extract_element_inner_by_class(card_inner, "a", "name_a") else {
            continue;
        };
        let provider = clean_text(&provider_raw);
        if provider.is_empty() {
            continue;
        }

        let Some(estimated_raw) = extract_to_amount_value(card_inner) else {
            continue;
        };
        let estimated_xmr = clean_numeric_decimal(&estimated_raw);
        if estimated_xmr.is_empty() {
            continue;
        }

        let summary_text = extract_element_inner_by_class(card_inner, "div", "summary")
            .map(|s| clean_text(&s))
            .unwrap_or_default();
        let rate_xmr_per_btc = extract_rate_from_summary(&summary_text)
            .or_else(|| compute_scaled_rate(&estimated_xmr, &clean_btc))
            .unwrap_or_default();

        let spread_tag = extract_element_inner_by_class(card_inner, "div", "comparative_tag")
            .map(|s| clean_text(&s))
            .unwrap_or_default();

        let kyc_warning = extract_element_inner_by_class(card_inner, "div", "warning")
            .map(|s| clean_text(&s))
            .unwrap_or_default();

        let Some(raw_href) = extract_anchor_href_by_class(card_inner, "offer_button") else {
            continue;
        };
        let decoded_href = decode_entities(&raw_href);
        let Some((launch_url, is_onion_url)) =
            normalize_launch_url(decoded_href.trim(), &clean_btc)
        else {
            continue;
        };

        let slug: String = provider
            .chars()
            .map(|c| {
                if c.is_ascii_alphanumeric() {
                    c.to_ascii_lowercase()
                } else {
                    '-'
                }
            })
            .collect::<String>()
            .trim_matches('-')
            .to_string();
        let id = format!(
            "{}-{}",
            if slug.is_empty() { "quote" } else { &slug },
            quotes.len()
        );

        quotes.push(BtcSwapQuoteItem {
            id,
            provider,
            estimated_xmr,
            rate_xmr_per_btc,
            spread_tag,
            never_kyc: meta.filter_attributes.never_kyc,
            kyc_warning,
            is_onion_url,
            launch_url,
        });
    }

    // Stable sort placing `.onion` launch URLs first in all routing modes while
    // preserving best-rate order within the `.onion` tier and clearnet tier.
    quotes.sort_by_key(|q| !q.is_onion_url);

    Ok(quotes)
}

fn normalize_launch_url(raw_href: &str, btc_amount: &str) -> Option<(String, bool)> {
    let mut parsed = if raw_href.starts_with('/') {
        Url::parse(ORANGEFREN_ONION_BASE)
            .ok()?
            .join(raw_href)
            .ok()?
    } else {
        Url::parse(raw_href).ok()?
    };

    if !matches!(parsed.scheme(), "http" | "https") {
        return None;
    }
    let host = parsed.host_str()?.to_ascii_lowercase();
    let is_onion = is_valid_v3_onion_host(&host);

    // Fix WizardSwap's empty `?from=&to=` query parameters so BTC -> XMR pre-selects cleanly.
    let pairs: Vec<(String, String)> = parsed
        .query_pairs()
        .map(|(k, v)| (k.into_owned(), v.into_owned()))
        .collect();
    let has_empty_from_to = pairs.iter().any(|(k, v)| k == "from" && v.is_empty())
        && pairs.iter().any(|(k, v)| k == "to" && v.is_empty());

    if host.contains("wizardsw") || has_empty_from_to {
        let mut saw_from = false;
        let mut saw_to = false;
        let mut saw_amount = false;
        let mut updated = Vec::with_capacity(pairs.len() + 3);
        for (k, v) in pairs {
            match k.as_str() {
                "from" => {
                    saw_from = true;
                    updated.push((
                        k,
                        if v.trim().is_empty() {
                            "BTC".to_string()
                        } else {
                            v
                        },
                    ));
                }
                "to" => {
                    saw_to = true;
                    updated.push((
                        k,
                        if v.trim().is_empty() {
                            "XMR".to_string()
                        } else {
                            v
                        },
                    ));
                }
                "amount" => {
                    saw_amount = true;
                    updated.push((
                        k,
                        if v.trim().is_empty() {
                            btc_amount.to_string()
                        } else {
                            v
                        },
                    ));
                }
                _ => updated.push((k, v)),
            }
        }
        if !saw_from {
            updated.insert(0, ("from".to_string(), "BTC".to_string()));
        }
        if !saw_to {
            updated.insert(1, ("to".to_string(), "XMR".to_string()));
        }
        if !saw_amount {
            updated.push(("amount".to_string(), btc_amount.to_string()));
        }
        {
            let mut qp = parsed.query_pairs_mut();
            qp.clear();
            for (k, v) in &updated {
                qp.append_pair(k, v);
            }
        }
    }

    Some((parsed.to_string(), is_onion))
}

fn extract_to_amount_value(card_html: &str) -> Option<String> {
    let scope = find_tag_start_with_class(card_html, "div", "to_amount")
        .map(|idx| &card_html[idx..])
        .unwrap_or(card_html);
    extract_element_inner_by_class(scope, "div", "amount")
}

fn extract_rate_from_summary(summary: &str) -> Option<String> {
    // Matches `1 BTC ≈ 123.90779123 XMR` or `1 BTC = 123.90779123 XMR`
    for marker in ["1 BTC ≈", "1 BTC ~", "1 BTC ="] {
        if let Some(pos) = summary.find(marker) {
            let after = summary[pos + marker.len()..].trim_start();
            let num: String = after
                .chars()
                .take_while(|c| c.is_ascii_digit() || *c == '.')
                .collect();
            let clean = clean_numeric_decimal(&num);
            if !clean.is_empty() {
                return Some(clean);
            }
        }
    }
    None
}

fn compute_scaled_rate(estimated_xmr: &str, btc_amount: &str) -> Option<String> {
    let xmr_piconero = u128::from(crate::uri::parse_xmr_to_atomic(estimated_xmr).ok()?);
    let btc_piconero = u128::from(crate::uri::parse_xmr_to_atomic(btc_amount).ok()?);
    if btc_piconero == 0 {
        return None;
    }
    // rate in 10^8 scale: (xmr_piconero * 10^8) / btc_piconero
    let scaled_1e8 = xmr_piconero.checked_mul(100_000_000)? / btc_piconero;
    let whole = scaled_1e8 / 100_000_000;
    let frac = scaled_1e8 % 100_000_000;
    if frac == 0 {
        Some(whole.to_string())
    } else {
        let fp = format!("{frac:08}");
        Some(format!("{whole}.{}", fp.trim_end_matches('0')))
    }
}

fn find_tag_start_with_class(html: &str, tag: &str, required_class: &str) -> Option<usize> {
    let needle = format!("<{tag}");
    let mut pos = 0usize;
    while let Some(rel) = html[pos..].find(&needle) {
        let start = pos + rel;
        let after_name = start + needle.len();
        if let Some(next_ch) = html[after_name..].chars().next() {
            if !next_ch.is_whitespace() && next_ch != '>' {
                pos = after_name;
                continue;
            }
        }
        let tag_end = html[start..].find('>')? + start;
        let open_tag = &html[start..=tag_end];
        if let Some(cls) = extract_attr(open_tag, "class") {
            if cls
                .split_whitespace()
                .any(|c| c.eq_ignore_ascii_case(required_class))
            {
                return Some(start);
            }
        }
        pos = tag_end + 1;
    }
    None
}

fn extract_element_inner_by_class(html: &str, tag: &str, required_class: &str) -> Option<String> {
    let start = find_tag_start_with_class(html, tag, required_class)?;
    let tag_end = html[start..].find('>')? + start;
    let content_start = tag_end + 1;
    let close_tag = format!("</{tag}>");
    let open_prefix = format!("<{tag}");

    let mut depth = 1usize;
    let mut scan = content_start;
    while scan < html.len() {
        let next_open = html[scan..].find(&open_prefix).map(|r| scan + r);
        let next_close = html[scan..].find(&close_tag).map(|r| scan + r)?;
        if let Some(open_idx) = next_open {
            if open_idx < next_close {
                let after = open_idx + open_prefix.len();
                let valid_open = html[after..]
                    .chars()
                    .next()
                    .is_some_and(|c| c.is_whitespace() || c == '>');
                if valid_open {
                    depth += 1;
                }
                scan = after;
                continue;
            }
        }
        depth -= 1;
        if depth == 0 {
            return Some(html[content_start..next_close].to_string());
        }
        scan = next_close + close_tag.len();
    }
    None
}

fn extract_anchor_href_by_class(html: &str, required_class: &str) -> Option<String> {
    let start = find_tag_start_with_class(html, "a", required_class)?;
    let tag_end = html[start..].find('>')? + start;
    extract_attr(&html[start..=tag_end], "href")
}

fn extract_attr(open_tag: &str, attr_name: &str) -> Option<String> {
    let lower = open_tag.to_ascii_lowercase();
    let mut search_from = 0usize;
    while let Some(rel) = lower[search_from..].find(attr_name) {
        let idx = search_from + rel;
        let before_ok = idx > 0 && lower.as_bytes()[idx - 1].is_ascii_whitespace();
        let after_idx = idx + attr_name.len();
        if !before_ok {
            search_from = after_idx;
            continue;
        }
        let rest = open_tag[after_idx..].trim_start();
        let Some(after_eq) = rest.strip_prefix('=') else {
            search_from = after_idx;
            continue;
        };
        let after_eq = after_eq.trim_start();
        if let Some(stripped) = after_eq.strip_prefix('"') {
            let end = stripped.find('"')?;
            return Some(stripped[..end].to_string());
        }
        if let Some(stripped) = after_eq.strip_prefix('\'') {
            let end = stripped.find('\'')?;
            return Some(stripped[..end].to_string());
        }
        let end = after_eq
            .find(|c: char| c.is_whitespace() || c == '>')
            .unwrap_or(after_eq.len());
        return Some(after_eq[..end].to_string());
    }
    None
}

fn clean_numeric_decimal(s: &str) -> String {
    let cleaned: String = strip_tags(s)
        .chars()
        .filter(|c| c.is_ascii_digit() || *c == '.')
        .collect();
    if cleaned.is_empty()
        || cleaned == "."
        || cleaned.matches('.').count() > 1
        || !cleaned.chars().any(|c| c.is_ascii_digit() && c != '0')
    {
        return String::new();
    }
    cleaned
}

fn clean_text(raw_html: &str) -> String {
    let stripped = strip_tags(raw_html);
    let decoded = decode_entities(&stripped);
    decoded.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn strip_tags(html: &str) -> String {
    let mut out = String::with_capacity(html.len());
    let mut in_tag = false;
    let mut tag_buf = String::new();
    for ch in html.chars() {
        if ch == '<' {
            in_tag = true;
            tag_buf.clear();
        } else if ch == '>' && in_tag {
            in_tag = false;
            let t = tag_buf.trim().to_ascii_lowercase();
            if t == "br" || t == "br/" || t == "br /" {
                out.push(' ');
            }
        } else if in_tag {
            tag_buf.push(ch);
        } else {
            out.push(ch);
        }
    }
    out
}

fn decode_entities(input: &str) -> String {
    if !input.contains('&') {
        return input.to_string();
    }
    input
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace("&#8776;", "≈")
        .replace("&asymp;", "≈")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_real_orangefren_onion_html_fixture_and_normalizes_wizardswap() {
        let html = include_str!("../tests/fixtures/orangefren_btcxmr_001.html");
        let quotes = parse_orangefren_quotes_html(html, "0.01", true).unwrap();
        assert_eq!(quotes.len(), 5);

        // With `hidden_service_only = true`, the 2 `.onion` services sort to the top:
        assert!(quotes[0].is_onion_url);
        assert_eq!(quotes[0].provider, "Pablo Cash");
        assert_eq!(quotes[0].estimated_xmr, "1.23907791");
        assert_eq!(quotes[0].rate_xmr_per_btc, "123.90779123");
        assert_eq!(quotes[0].spread_tag, "-0.93%");
        assert!(quotes[0].never_kyc);

        assert!(quotes[1].is_onion_url);
        assert_eq!(quotes[1].provider, "WizardSwap");
        assert_eq!(quotes[1].estimated_xmr, "1.1825666");
        assert!(quotes[1].launch_url.contains("from=BTC"));
        assert!(quotes[1].launch_url.contains("to=XMR"));
        assert!(quotes[1].launch_url.contains("amount=0.01"));
        assert!(quotes[1].launch_url.contains("ref=820391743d"));

        // Clearnet offers follow with `isOnionUrl == false`:
        assert!(!quotes[2].is_onion_url);
        assert_eq!(quotes[2].provider, "Xchange");
        assert!(quotes[2].kyc_warning.contains("Tainted coins"));

        // Clearnet-consent mode (`hidden_service_only = false`) also prioritizes `.onion` offers first:
        let quotes_consent = parse_orangefren_quotes_html(html, "0.01", false).unwrap();
        assert!(quotes_consent[0].is_onion_url);
        assert!(quotes_consent[1].is_onion_url);
        assert!(!quotes_consent[2].is_onion_url);
    }
}
