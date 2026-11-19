//! Fail-closed Tor SOCKS5h (`ATYP = 0x03`, zero OS DNS) + HTTP/1.1 + `rustls` transport.
//!
//! Every network request in `ov-xmr` routes exclusively through Trap's loopback
//! Arti SOCKS5h proxy (`127.0.0.1:<port>`). Any call when `socks_addr` is unset,
//! non-loopback, or when Tor is not yet bootstrapped fails closed immediately
//! before opening a socket.

use std::{
    future::Future,
    io::Read,
    net::SocketAddr,
    sync::{Arc, OnceLock},
    time::Duration,
};

use anyhow::{anyhow, bail, Context, Result};
use flate2::read::{GzDecoder, ZlibDecoder};
use monero_daemon_rpc::HttpTransport;
use monero_wallet::interface::InterfaceError;
use ov_tor::is_valid_v3_onion_host;
use rustls_pki_types::ServerName;
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt},
    net::TcpStream,
};
use tokio_rustls::TlsConnector;
use url::Url;

const SOCKS_VERSION: u8 = 0x05;
const METHOD_NONE: u8 = 0x00;
const METHOD_USERPASS: u8 = 0x02;
const USERPASS_VERSION: u8 = 0x01;
const CMD_CONNECT: u8 = 0x01;
const ATYP_IPV4: u8 = 0x01;
const ATYP_DOMAIN: u8 = 0x03;
const ATYP_IPV6: u8 = 0x04;
const REP_SUCCESS: u8 = 0x00;

/// Default upper bound on HTTP response payloads (100 MiB, matching `monerod` limit).
const DEFAULT_MAX_RESPONSE_BYTES: usize = 100 * 1024 * 1024;
const MAX_HEADER_BYTES: usize = 64 * 1024;
const MAX_REDIRECTS: usize = 5;

/// Raw HTTP response returned by [`TorSocksTransport`].
#[derive(Clone, Debug)]
pub struct HttpResponse {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl HttpResponse {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.as_str())
    }
}

/// Fail-closed HTTP/1.1 client strictly bound to Trap's loopback Arti SOCKS5h endpoint.
#[derive(Clone, Debug)]
pub struct TorSocksTransport {
    socks_addr: Option<SocketAddr>,
    tor_ready: bool,
    hidden_service_only: bool,
    isolation_user: Option<String>,
    request_timeout: Duration,
}

impl TorSocksTransport {
    /// Construct a fail-closed transport over Trap's loopback SOCKS5h listener.
    pub fn new(
        socks_addr: Option<SocketAddr>,
        tor_ready: bool,
        hidden_service_only: bool,
        isolation_user: Option<String>,
    ) -> Self {
        Self {
            socks_addr,
            tor_ready,
            hidden_service_only,
            isolation_user,
            request_timeout: Duration::from_secs(45),
        }
    }

    /// Override per-request timeout.
    pub fn with_timeout(mut self, timeout: Duration) -> Self {
        self.request_timeout = timeout;
        self
    }

    fn verify_ready_loopback(&self) -> Result<SocketAddr> {
        let addr = self
            .socks_addr
            .ok_or_else(|| anyhow!("Tor SOCKS5 proxy is not initialized; refusing network call"))?;
        if !addr.ip().is_loopback() || addr.port() == 0 {
            bail!("refusing non-loopback SOCKS5 proxy address {addr}");
        }
        if !self.tor_ready {
            bail!("Tor is still bootstrapping; refusing network call until Tor is ready");
        }
        Ok(addr)
    }

    fn verify_url_policy(&self, url: &Url) -> Result<(String, u16, bool)> {
        let scheme = url.scheme();
        let is_tls = match scheme {
            "http" => false,
            "https" => true,
            other => bail!("unsupported URL scheme {other:?}; expected http or https"),
        };
        let host = url
            .host_str()
            .ok_or_else(|| anyhow!("URL {url} is missing host"))?
            .trim()
            .trim_start_matches('[')
            .trim_end_matches(']')
            .to_ascii_lowercase();
        if host.is_empty() || host.len() > 255 {
            bail!("invalid target host length in {url}");
        }
        if self.hidden_service_only && !is_valid_v3_onion_host(&host) {
            bail!("Hidden-service-only mode is active: refusing non-.onion host {host}");
        }
        let port = url
            .port_or_known_default()
            .ok_or_else(|| anyhow!("URL {url} is missing port"))?;
        Ok((host, port, is_tls))
    }

    /// Perform an HTTP `GET` over SOCKS5h, following up to 5 HTTP redirects (validating
    /// `hidden_service_only` on every hop).
    pub async fn get(&self, raw_url: &str, max_bytes: Option<usize>) -> Result<HttpResponse> {
        let mut current =
            Url::parse(raw_url.trim()).with_context(|| format!("invalid URL {raw_url:?}"))?;
        for _hop in 0..=MAX_REDIRECTS {
            let resp = self
                .request_once(&current, "GET", None, None, true, max_bytes)
                .await?;
            if matches!(resp.status, 301 | 302 | 303 | 307 | 308) {
                if let Some(loc) = resp.header("location") {
                    current = current
                        .join(loc.trim())
                        .with_context(|| format!("invalid redirect Location {loc:?}"))?;
                    continue;
                }
            }
            return Ok(resp);
        }
        bail!("exceeded {MAX_REDIRECTS} HTTP redirects fetching {raw_url}")
    }

    /// Perform an HTTP `POST` over SOCKS5h.
    pub async fn post(
        &self,
        raw_url: &str,
        content_type: &str,
        body: &[u8],
        allow_gzip: bool,
        max_bytes: Option<usize>,
    ) -> Result<HttpResponse> {
        let parsed =
            Url::parse(raw_url.trim()).with_context(|| format!("invalid URL {raw_url:?}"))?;
        self.request_once(
            &parsed,
            "POST",
            Some((content_type, body)),
            None,
            allow_gzip,
            max_bytes,
        )
        .await
    }

    async fn request_once(
        &self,
        url: &Url,
        method: &str,
        body_with_ct: Option<(&str, &[u8])>,
        extra_headers: Option<&[(&str, &str)]>,
        allow_gzip: bool,
        max_bytes: Option<usize>,
    ) -> Result<HttpResponse> {
        let socks_addr = self.verify_ready_loopback()?;
        let (host, port, is_tls) = self.verify_url_policy(url)?;
        let limit = max_bytes
            .unwrap_or(DEFAULT_MAX_RESPONSE_BYTES)
            .min(DEFAULT_MAX_RESPONSE_BYTES);

        tokio::time::timeout(self.request_timeout, async {
            let tcp =
                connect_socks5h_domain(socks_addr, &host, port, self.isolation_user.as_deref())
                    .await?;

            let req_bytes = build_http11_request(
                url,
                &host,
                port,
                is_tls,
                method,
                body_with_ct,
                extra_headers,
                allow_gzip,
            );

            if is_tls {
                let connector = tls_connector()?;
                let server_name = ServerName::try_from(host.clone())
                    .map_err(|_| anyhow!("invalid TLS SNI server name {host:?}"))?;
                let mut tls_stream = connector
                    .connect(server_name, tcp)
                    .await
                    .with_context(|| format!("TLS handshake over Tor failed for {host}:{port}"))?;
                tls_stream.write_all(&req_bytes).await?;
                tls_stream.flush().await?;
                read_http11_response(&mut tls_stream, limit).await
            } else {
                let mut tcp = tcp;
                tcp.write_all(&req_bytes).await?;
                tcp.flush().await?;
                read_http11_response(&mut tcp, limit).await
            }
        })
        .await
        .map_err(|_| {
            anyhow!(
                "Tor HTTP request to {} timed out",
                url.host_str().unwrap_or("")
            )
        })?
    }
}

/// `monero_daemon_rpc::HttpTransport` adapter backed by [`TorSocksTransport`].
#[derive(Clone, Debug)]
pub struct MoneroRpcTransport {
    base_url: String,
    inner: TorSocksTransport,
}

impl MoneroRpcTransport {
    pub fn new(base_url: impl Into<String>, inner: TorSocksTransport) -> Self {
        Self {
            base_url: base_url.into(),
            inner,
        }
    }

    pub fn base_url(&self) -> &str {
        &self.base_url
    }
}

impl HttpTransport for MoneroRpcTransport {
    fn post(
        &self,
        route: &str,
        body: Vec<u8>,
        response_size_limit: Option<usize>,
    ) -> impl Send + Future<Output = Result<Vec<u8>, InterfaceError>> {
        let base = self.base_url.trim_end_matches('/');
        let path = route.trim_start_matches('/');
        let full_url = format!("{base}/{path}");
        let is_binary = path.ends_with(".bin");
        let content_type = if is_binary {
            "application/octet-stream"
        } else {
            "application/json"
        };
        let client = self.inner.clone();
        async move {
            let resp = client
                .post(&full_url, content_type, &body, false, response_size_limit)
                .await
                .map_err(|e| InterfaceError::InterfaceError(e.to_string()))?;
            if resp.status != 200 {
                return Err(InterfaceError::InvalidInterface(format!(
                    "monerod returned HTTP {} for /{path}",
                    resp.status
                )));
            }
            Ok(resp.body)
        }
    }
}

fn tls_connector() -> Result<TlsConnector> {
    static TLS_CFG: OnceLock<Arc<rustls::ClientConfig>> = OnceLock::new();
    ov_tor::install_crypto_provider();
    let cfg = TLS_CFG.get_or_init(|| {
        let mut root_store = rustls::RootCertStore::empty();
        root_store.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        let config = rustls::ClientConfig::builder()
            .with_root_certificates(root_store)
            .with_no_client_auth();
        Arc::new(config)
    });
    Ok(TlsConnector::from(cfg.clone()))
}

/// Perform RFC 1928 SOCKS5h (`ATYP = 0x03` domain name) handshake over loopback TCP.
async fn connect_socks5h_domain(
    socks_addr: SocketAddr,
    host: &str,
    port: u16,
    isolation_user: Option<&str>,
) -> Result<TcpStream> {
    let host_bytes = host.as_bytes();
    if host_bytes.is_empty() || host_bytes.len() > 255 {
        bail!("SOCKS5h domain length out of range ({})", host_bytes.len());
    }

    let mut stream = TcpStream::connect(socks_addr)
        .await
        .with_context(|| format!("cannot connect to loopback Tor SOCKS5 proxy {socks_addr}"))?;
    let _ = stream.set_nodelay(true);

    let user = isolation_user.unwrap_or("").trim();
    if !user.is_empty() && user.len() <= 255 {
        stream
            .write_all(&[SOCKS_VERSION, 2, METHOD_USERPASS, METHOD_NONE])
            .await?;
    } else {
        stream.write_all(&[SOCKS_VERSION, 1, METHOD_NONE]).await?;
    }

    let mut method_reply = [0u8; 2];
    stream.read_exact(&mut method_reply).await?;
    if method_reply[0] != SOCKS_VERSION {
        bail!("invalid SOCKS5 greeting version {}", method_reply[0]);
    }
    match method_reply[1] {
        METHOD_NONE => {}
        METHOD_USERPASS => {
            let ubytes = user.as_bytes();
            let mut auth_pkt = Vec::with_capacity(3 + ubytes.len() + 1);
            auth_pkt.push(USERPASS_VERSION);
            auth_pkt.push(ubytes.len() as u8);
            auth_pkt.extend_from_slice(ubytes);
            auth_pkt.push(1);
            auth_pkt.push(b'0');
            stream.write_all(&auth_pkt).await?;
            let mut auth_reply = [0u8; 2];
            stream.read_exact(&mut auth_reply).await?;
            if auth_reply[1] != 0x00 {
                bail!("SOCKS5 RFC 1929 isolation auth failed ({})", auth_reply[1]);
            }
        }
        other => bail!("SOCKS5 proxy rejected authentication methods ({other:#x})"),
    }

    // Strictly `ATYP_DOMAIN = 0x03` so zero DNS resolution ever occurs on the OS!
    let mut req = Vec::with_capacity(5 + host_bytes.len() + 2);
    req.extend_from_slice(&[SOCKS_VERSION, CMD_CONNECT, 0x00, ATYP_DOMAIN]);
    req.push(host_bytes.len() as u8);
    req.extend_from_slice(host_bytes);
    req.extend_from_slice(&port.to_be_bytes());
    stream.write_all(&req).await?;
    stream.flush().await?;

    let mut resp_head = [0u8; 4];
    stream
        .read_exact(&mut resp_head)
        .await
        .with_context(|| format!("SOCKS5 CONNECT to {host}:{port} closed before reply"))?;
    if resp_head[0] != SOCKS_VERSION {
        bail!("invalid SOCKS5 reply version {}", resp_head[0]);
    }
    if resp_head[1] != REP_SUCCESS {
        bail!(
            "Tor SOCKS5 proxy refused or failed connection to {host}:{port} (REP={:#04x})",
            resp_head[1]
        );
    }

    match resp_head[3] {
        ATYP_IPV4 => {
            let mut rest = [0u8; 4 + 2];
            stream.read_exact(&mut rest).await?;
        }
        ATYP_IPV6 => {
            let mut rest = [0u8; 16 + 2];
            stream.read_exact(&mut rest).await?;
        }
        ATYP_DOMAIN => {
            let mut dlen = [0u8; 1];
            stream.read_exact(&mut dlen).await?;
            let mut rest = vec![0u8; usize::from(dlen[0]) + 2];
            stream.read_exact(&mut rest).await?;
        }
        other => bail!("unsupported SOCKS5 bound ATYP {other:#x}"),
    }

    Ok(stream)
}

#[allow(clippy::too_many_arguments)]
fn build_http11_request(
    url: &Url,
    host: &str,
    port: u16,
    is_tls: bool,
    method: &str,
    body_with_ct: Option<(&str, &[u8])>,
    extra_headers: Option<&[(&str, &str)]>,
    allow_gzip: bool,
) -> Vec<u8> {
    let mut path_and_query = url.path().to_string();
    if path_and_query.is_empty() {
        path_and_query.push('/');
    }
    if let Some(q) = url.query() {
        path_and_query.push('?');
        path_and_query.push_str(q);
    }

    let default_port = if is_tls { 443 } else { 80 };
    let host_header = if port == default_port {
        host.to_string()
    } else {
        format!("{host}:{port}")
    };

    let accept_encoding = if allow_gzip {
        "gzip, deflate, identity"
    } else {
        "identity"
    };

    let mut head = String::with_capacity(512);
    head.push_str(&format!("{method} {path_and_query} HTTP/1.1\r\n"));
    head.push_str(&format!("Host: {host_header}\r\n"));
    head.push_str(
        "User-Agent: Mozilla/5.0 (Android 15; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0\r\n",
    );
    head.push_str("Accept: */*\r\n");
    head.push_str(&format!("Accept-Encoding: {accept_encoding}\r\n"));
    head.push_str("Connection: close\r\n");

    if let Some(hdrs) = extra_headers {
        for (k, v) in hdrs {
            head.push_str(&format!("{k}: {v}\r\n"));
        }
    }

    if let Some((ct, body)) = body_with_ct {
        head.push_str(&format!("Content-Type: {ct}\r\n"));
        head.push_str(&format!("Content-Length: {}\r\n", body.len()));
        head.push_str("\r\n");
        let mut out = head.into_bytes();
        out.extend_from_slice(body);
        out
    } else {
        head.push_str("\r\n");
        head.into_bytes()
    }
}

async fn read_http11_response<S>(stream: &mut S, max_body_bytes: usize) -> Result<HttpResponse>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let mut buf = Vec::with_capacity(4096);
    let mut scratch = [0u8; 4096];
    let header_end = loop {
        if let Some(pos) = find_double_crlf(&buf) {
            break pos;
        }
        if buf.len() > MAX_HEADER_BYTES {
            bail!("HTTP response header exceeded {MAX_HEADER_BYTES} bytes");
        }
        let n = stream.read(&mut scratch).await?;
        if n == 0 {
            bail!("unexpected EOF while reading HTTP response headers");
        }
        buf.extend_from_slice(&scratch[..n]);
    };

    let header_text = std::str::from_utf8(&buf[..header_end])
        .context("HTTP response headers are not valid UTF-8")?;
    let mut lines = header_text.split("\r\n");
    let status_line = lines
        .next()
        .ok_or_else(|| anyhow!("missing HTTP status line"))?;
    let status = parse_status_code(status_line)?;

    let mut headers = Vec::new();
    for line in lines {
        if line.is_empty() {
            continue;
        }
        if let Some((k, v)) = line.split_once(':') {
            headers.push((k.trim().to_ascii_lowercase(), v.trim().to_string()));
        }
    }

    let mut remainder = buf.split_off(header_end + 4);
    let is_chunked = headers
        .iter()
        .any(|(k, v)| k == "transfer-encoding" && v.to_ascii_lowercase().contains("chunked"));
    let content_length = headers
        .iter()
        .find(|(k, _)| k == "content-length")
        .and_then(|(_, v)| v.parse::<usize>().ok());

    let raw_body = if is_chunked {
        read_chunked_body(stream, &mut remainder, max_body_bytes).await?
    } else if let Some(expected_len) = content_length {
        if expected_len > max_body_bytes {
            bail!("HTTP Content-Length ({expected_len}) exceeds limit ({max_body_bytes})");
        }
        let mut out = remainder;
        out.reserve(expected_len.saturating_sub(out.len()).min(65_536));
        while out.len() < expected_len {
            let to_read = (expected_len - out.len()).min(scratch.len());
            let n = stream.read(&mut scratch[..to_read]).await?;
            if n == 0 {
                bail!(
                    "unexpected EOF reading HTTP body ({} / {expected_len} bytes)",
                    out.len()
                );
            }
            out.extend_from_slice(&scratch[..n]);
        }
        out.truncate(expected_len);
        out
    } else {
        let mut out = remainder;
        loop {
            let n = stream.read(&mut scratch).await?;
            if n == 0 {
                break;
            }
            if out.len().saturating_add(n) > max_body_bytes {
                bail!("HTTP response body exceeded {max_body_bytes} bytes");
            }
            out.extend_from_slice(&scratch[..n]);
        }
        out
    };

    let encoding = headers
        .iter()
        .find(|(k, _)| k == "content-encoding")
        .map(|(_, v)| v.to_ascii_lowercase())
        .unwrap_or_default();

    let decoded_body = if encoding.contains("gzip") {
        decompress_bounded(GzDecoder::new(raw_body.as_slice()), max_body_bytes)?
    } else if encoding.contains("deflate") {
        decompress_bounded(ZlibDecoder::new(raw_body.as_slice()), max_body_bytes)?
    } else {
        raw_body
    };

    Ok(HttpResponse {
        status,
        headers,
        body: decoded_body,
    })
}

fn decompress_bounded<R: Read>(reader: R, max_bytes: usize) -> Result<Vec<u8>> {
    let mut limited = reader.take((max_bytes as u64).saturating_add(1));
    let mut out = Vec::new();
    limited
        .read_to_end(&mut out)
        .context("failed to decompress HTTP response body")?;
    if out.len() > max_bytes {
        bail!("decompressed HTTP body exceeded {max_bytes} bytes");
    }
    Ok(out)
}

async fn read_chunked_body<S>(
    stream: &mut S,
    buf: &mut Vec<u8>,
    max_body_bytes: usize,
) -> Result<Vec<u8>>
where
    S: AsyncRead + Unpin,
{
    let mut out = Vec::new();
    let mut scratch = [0u8; 4096];

    loop {
        let line_end = loop {
            if let Some(pos) = find_crlf(buf) {
                break pos;
            }
            if buf.len() > 4096 {
                bail!("malformed HTTP chunk size line");
            }
            let n = stream.read(&mut scratch).await?;
            if n == 0 {
                bail!("unexpected EOF reading HTTP chunk size");
            }
            buf.extend_from_slice(&scratch[..n]);
        };

        let size_line = std::str::from_utf8(&buf[..line_end])
            .context("invalid UTF-8 in HTTP chunk size")?
            .split(';')
            .next()
            .unwrap_or("")
            .trim();
        let chunk_size = usize::from_str_radix(size_line, 16)
            .with_context(|| format!("invalid hex chunk size {size_line:?}"))?;
        buf.drain(..line_end + 2);

        if chunk_size == 0 {
            break;
        }
        if out.len().saturating_add(chunk_size) > max_body_bytes {
            bail!("chunked HTTP body exceeded {max_body_bytes} bytes");
        }

        let needed = chunk_size + 2;
        while buf.len() < needed {
            let n = stream.read(&mut scratch).await?;
            if n == 0 {
                bail!("unexpected EOF inside HTTP chunk payload");
            }
            buf.extend_from_slice(&scratch[..n]);
        }
        if &buf[chunk_size..chunk_size + 2] != b"\r\n" {
            bail!("missing CRLF after HTTP chunk payload");
        }
        out.extend_from_slice(&buf[..chunk_size]);
        buf.drain(..needed);
    }

    Ok(out)
}

fn find_double_crlf(buf: &[u8]) -> Option<usize> {
    buf.windows(4).position(|w| w == b"\r\n\r\n")
}

fn find_crlf(buf: &[u8]) -> Option<usize> {
    buf.windows(2).position(|w| w == b"\r\n")
}

fn parse_status_code(status_line: &str) -> Result<u16> {
    let mut parts = status_line.split_whitespace();
    let proto = parts
        .next()
        .ok_or_else(|| anyhow!("empty HTTP status line"))?;
    if !proto.starts_with("HTTP/1.") {
        bail!("unsupported HTTP version {proto:?}");
    }
    let code_str = parts
        .next()
        .ok_or_else(|| anyhow!("missing HTTP status code in {status_line:?}"))?;
    code_str
        .parse::<u16>()
        .with_context(|| format!("invalid HTTP status code {code_str:?}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn refuses_when_socks_unset_non_loopback_or_tor_not_ready() {
        let unset = TorSocksTransport::new(None, true, true, None);
        assert!(unset
            .get(
                "http://rnwis2whetqcj4oknksnc5l24jbh33nflunifff3xtjjonnoxu3ld6id.onion/",
                None
            )
            .await
            .is_err());

        let unready =
            TorSocksTransport::new(Some("127.0.0.1:9050".parse().unwrap()), false, true, None);
        assert!(unready
            .get(
                "http://rnwis2whetqcj4oknksnc5l24jbh33nflunifff3xtjjonnoxu3ld6id.onion/",
                None
            )
            .await
            .is_err());

        let public_proxy =
            TorSocksTransport::new(Some("8.8.8.8:9050".parse().unwrap()), true, false, None);
        assert!(public_proxy
            .get(
                "http://rnwis2whetqcj4oknksnc5l24jbh33nflunifff3xtjjonnoxu3ld6id.onion/",
                None
            )
            .await
            .is_err());

        let clearnet_blocked =
            TorSocksTransport::new(Some("127.0.0.1:9050".parse().unwrap()), true, true, None);
        assert!(clearnet_blocked
            .get("https://example.com/", None)
            .await
            .is_err());
    }
}
