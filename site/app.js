/* ==========================================================================
   Trap Android Homescreen — Live Compose @Preview Callout & SVG Sync Engine
   ========================================================================== */

const REPO_BLOB_BASE = "https://github.com/maceip/trap/blob/main/";

/**
 * Rich architectural & interaction documentation for each of the 7 homescreen
 * components, paired 1:1 by `id` with the live Compose Semantics geometry
 * exported by `HomescreenPagesSyncTest.kt` into `generated/homescreen-sync.json`.
 */
const COMPONENT_DOCS = {
  "start-zine-viewport": {
    title: "Full-Bleed Edge-to-Edge Viewport & Start Zine",
    role: "Zero-JS Chromium 156 Surface + Street-Zine Start Canvas",
    summary:
      "Draws from y = 0dp onto the Android status bar with zero top app bar, zero bottom bar, and zero top notification snackbars. Hosts the raw TRAP street-zine collage on about:blank and the live off-the-record Cefpack Chromium 156 SurfaceView on active .onion / Tor tabs.",
    gestures: [
      { key: "Passthrough", desc: "100% of open canvas between floating pods passes touch & scroll straight to Blink." },
      { key: "Scroll Down", desc: "Retracts pods in 110ms (CRT +96dp, left cluster 0.78×, right pods +50..58dp) for unobstructed reading." },
      { key: "PiP Mode", desc: "Strips all pods on frame 1, scales page width proportionally, applies 0.88 translucency & dark #0D0F12 fallback." }
    ],
    securityNote:
      "Enforces WebPreferences::javascript_enabled = false, V8 --jitless,--no-expose-wasm, and CDP Emulation.setScriptExecutionDisabled(true) on every tab.",
    previewActionLabel: "Resting Start @Preview"
  },
  "perimeter-steel-rails": {
    title: "Perimeter Brushed-Steel Rails",
    role: "12dp Tactile Frame & Zero-Touch Load Progress Bar",
    summary:
      "Left and right 12dp vertical rails (8dp brushed-steel bar + 4dp outer overspray margin) running y = 0dp to bottom edge. Replaces traditional horizontal URL progress bars without stealing a single vertical pixel or intercepting touch.",
    gestures: [
      { key: "0 Listeners", desc: "Attaches zero pointerInput / clickable modifiers — 100% hit-test passthrough." },
      { key: "Page Load", desc: "Animates a high-visibility phosphor pulse along the steel seam proportional to loadProgress." },
      { key: "PiP Entry", desc: "Automatically hidden during Picture-in-Picture transition and active PiP." }
    ],
    securityNote:
      "Visualizes live SOCKS5h circuit & document load progress without exposing URL bar chrome during screen-sensitive workflows.",
    previewActionLabel: "Inspect Load Pulse →"
  },
  "kpt-back-pod": {
    title: "KPT Cast-Pewter Back / Forward Switch",
    role: "62×40dp Non-Round Horizontal Navigation Keycap",
    summary:
      "Strictly rectangular 1.55:1 chamfered cast-pewter '◀' tactile switch positioned above the Tor orb on the left thumb arc, keeping TorKnurledOrbPod as the sole round sphere in the interface.",
    gestures: [
      { key: "1 Tap", desc: "Navigates back in tab history (or returns cleanly to StartPage); restores retracted pods first if tucked." },
      { key: "Hold 400ms", desc: "Navigates forward (onForward) with haptic tick when forward history exists." },
      { key: "Edge Swipe", desc: "Integrates with Android Predictive Back to preview PixelCopy cached snapshots before committing." }
    ],
    securityNote:
      "Predictive-back snapshots are held strictly in ephemeral RAM and never written to persistent disk.",
    previewActionLabel: "Resting Start @Preview"
  },
  "tor-orb-pod": {
    title: "3D Knurled Tor Bootstrap & Circuit Orb",
    role: "104dp Embedded Arti Tor Telemetry & Identity Control",
    summary:
      "The sole round sphere in the MetaTools shell. Driven by embedded Rust Arti (ov-tor), shifting from warm amber 6fps orbital beacon while bootstrapping to ultraviolet-purple with chrome collar once the loopback SOCKS5h listener is ready.",
    gestures: [
      { key: "1 Tap", desc: "Opens the Side Remote directly in Tor Circuit Mode (per-site iso-<host> token + .onion-Only Lock)." },
      { key: "Hold 400ms", desc: "Triggers Tor identity rotation, clearing isolated circuit groups & resetting ephemeral browser context." },
      { key: "Touch Down", desc: "2.5dp mechanical depress (75ms linear tween) with physical haptic click." }
    ],
    securityNote:
      "Fails closed: OS DNS is blocked (MAP * ~NOTFOUND , EXCLUDE 127.0.0.1) so every hostname traverses Arti SOCKS5h with per-origin circuit isolation.",
    previewActionLabel: "Inspect Tor Circuit →"
  },
  "crt-address-pod": {
    title: "Recessed 5:4 Pewter CRT Address & Directory TV",
    role: "156×135dp Phosphor URL Readout, Search & Tab Switcher",
    summary:
      "Chunky brushed-pewter CRT monitor floating lower-center with green phosphor readout ('search / .onion' on StartPage; tail-preserving 'duckduck…zczad.onion' on hidden services so v3 vanity/checksum tails remain visible).",
    gestures: [
      { key: "1 Tap", desc: "Expands to 95% width above IME alongside the 11-site SHA3-256 verified Trusted .onion Directory." },
      { key: "Swipe ◀ / ▶", desc: "Switches immediately to the previous or next open tab." },
      { key: "Pull Up", desc: "Opens SpatialTabSwitcherPod (tabs_sheet) for standard & private tabs." }
    ],
    securityNote:
      "Plain search terms route to DuckDuckGo v3 .onion HTML search; unapproved clearnet hosts are intercepted by the 3-day TTL ClearnetConsentHardwarePod.",
    previewActionLabel: "Inspect .onion Directory →"
  },
  "pgp-rocker-pod": {
    title: "+20° Translucent Red Switchblade OpenPGP Rocker",
    role: "88×114dp New-Tab Trigger, Keyring & Zero-JS PGP Latch",
    summary:
      "Clockwise-tilted (+20°) translucent red polycarbonate switchblade docked mid-right. Illuminates an internal emerald/amber LED whenever native C++ CDP DOM scanning detects armored OpenPGP blocks or editable fields on the page.",
    gestures: [
      { key: "1 Click", desc: "Opens a fresh ephemeral Tor tab immediately (onNewTab, 260ms click discriminator)." },
      { key: "2 Clicks", desc: "Opens KeyringHardwarePod (Sequoia Ed25519/Cv25519 keys + 1-tap page PGP actions)." },
      { key: "Drag Up 18dp", desc: "Spring-return latch triggers instant Quick Verify / Decrypt on page armor, selection, or clipboard." }
    ],
    securityNote:
      "All OpenPGP keys are wrapped with Android Keystore hardware-backed AES-256-GCM; plaintext and DOM mutations flow over in-process C++ DevToolsAgentHost with zero page JS.",
    previewActionLabel: "Inspect PGP Keyring →"
  },
  "side-remote-pod": {
    title: "+20° ZK \"TOR\" 2×5 LED Hardware Side Remote",
    role: "60×186dp Expandable Command, Monero & XMR402 Drawer",
    summary:
      "Bottom-right +20° hardware remote featuring a live 2×5 LED status matrix. Expands into Trap's multi-mode command deck without permanent toolbar clutter.",
    gestures: [
      { key: "1 Tap", desc: "Expands full drawer: Onion Sites, Monero Wallet, BTC⇄XMR Swap, Text Search, Tabs, Keyring, Zoom & Settings." },
      { key: "Text Search", desc: "Opens zero-JS FindInPageHardwarePod (CDP DOM.performSearch + DOM.getContentQuads highlight)." },
      { key: "Monero / 402", desc: "Launches pure-Rust ov-xmr Tor light wallet, .onion swap aggregator & HTTP 402 XMR402 challenge payer." }
    ],
    securityNote:
      "Wallet view/spend keys & SQLite state are encrypted via Android Keystore; daemon sync and swap quotes route exclusively through Arti Tor circuits.",
    previewActionLabel: "Inspect Remote Drawer →"
  }
};

const STATE_LABELS = {
  "resting": {
    tabLabel: "00 Resting Homescreen",
    captionTitle: "@Preview BrowserChromePreview()",
    captionDesc: "Live resting Android homescreen with all 7 Compose Semantics callout anchors active."
  },
  "crt-directory": {
    tabLabel: "05 CRT .onion Directory",
    captionTitle: "@Preview CrtOnionDirectoryPreview()",
    captionDesc: "Tapped crt_address_pod: 95%-width phosphor address editor + 11 SHA3-verified v3 .onion services."
  },
  "side-remote": {
    tabLabel: "07 ZK \"TOR\" Remote",
    captionTitle: "@Preview BrowserMenuPreview()",
    captionDesc: "Tapped side_remote_pod: expanded hardware drawer with Onion Sites, Monero Wallet, BTC⇄XMR Swap & Text Search."
  },
  "tor-circuit": {
    tabLabel: "04 Tor Circuit Inspector",
    captionTitle: "@Preview TorBootstrapPreview()",
    captionDesc: "Tapped tor_orb_pod: live Arti bootstrap progress, per-origin circuit isolation token & PerimeterSteelRails load pulse."
  },
  "pgp-keyring": {
    tabLabel: "06 OpenPGP Keyring",
    captionTitle: "@Preview NeonGraffitiKeyringPreview()",
    captionDesc: "Double-clicked pgp_rocker_pod: Sequoia OpenPGP Keystore keyring & zero-JS page field actions."
  },
  "monero-wallet": {
    tabLabel: "XMR Light Wallet",
    captionTitle: "@Preview MoneroWalletHomescreenPreview()",
    captionDesc: "Opened from Side Remote: pure-Rust ov-xmr Monero light wallet synced over .onion monerod RPC."
  }
};

const FALLBACK_MANIFEST = {
  schemaVersion: 1,
  appVersion: "0.1.43",
  gitCommit: "synced",
  previewEntrypoint: "dev.onionvault.ui.previews.BrowserChromePreview",
  viewport: {
    qualifiers: "w412dp-h915dp-xhdpi",
    density: 2.0,
    widthDp: 412.0,
    heightDp: 915.0,
    widthPx: 824,
    heightPx: 1830
  },
  homescreenImage: "generated/assets/homescreen-preview.webp",
  states: [
    { id: "resting", previewFn: "BrowserChromePreview", image: "generated/assets/homescreen-preview.webp" },
    { id: "crt-directory", previewFn: "CrtOnionDirectoryPreview", image: "generated/assets/state-crt-directory.webp" },
    { id: "side-remote", previewFn: "BrowserMenuPreview", image: "generated/assets/state-side-remote.webp" },
    { id: "tor-circuit", previewFn: "TorBootstrapPreview", image: "generated/assets/state-tor-circuit.webp" },
    { id: "pgp-keyring", previewFn: "NeonGraffitiKeyringPreview", image: "generated/assets/state-pgp-keyring.webp" },
    { id: "monero-wallet", previewFn: "MoneroWalletHomescreenPreview", image: "generated/assets/state-monero-wallet.webp" }
  ],
  components: [
    {
      id: "start-zine-viewport",
      index: "01",
      side: "left",
      testTag: "webpage_viewport_host",
      composable: "StartPage / BrowserScreen",
      sourcePath: "android/app/src/main/kotlin/dev/onionvault/ui/StartPage.kt",
      statePreviewId: "resting",
      boundsDp: { left: 0, top: 0, right: 412, bottom: 915, width: 412, height: 915 },
      boundsNorm: { left: 0.03, top: 0.015, width: 0.94, height: 0.46 },
      anchorNorm: { x: 0.48, y: 0.19 }
    },
    {
      id: "perimeter-steel-rails",
      index: "02",
      side: "left",
      testTag: "perimeter_steel_rails",
      composable: "PerimeterSteelRails",
      sourcePath: "android/app/src/main/kotlin/dev/onionvault/ui/pods/PerimeterSteelRails.kt",
      statePreviewId: "tor-circuit",
      boundsDp: { left: 0, top: 0, right: 412, bottom: 915, width: 412, height: 915 },
      boundsNorm: { left: 0.0, top: 0.0, width: 0.0291, height: 1.0 },
      anchorNorm: { x: 0.0146, y: 0.43 }
    },
    {
      id: "kpt-back-pod",
      index: "03",
      side: "left",
      testTag: "kpt_back_pod",
      composable: "KptBackButtonPod",
      sourcePath: "android/app/src/main/kotlin/dev/onionvault/ui/pods/KptBackButtonPod.kt",
      statePreviewId: "resting",
      boundsDp: { left: 22, top: 707, right: 84, bottom: 747, width: 62, height: 40 },
      boundsNorm: { left: 0.0534, top: 0.7727, width: 0.1505, height: 0.0437 },
      anchorNorm: { x: 0.1286, y: 0.7945 }
    },
    {
      id: "tor-orb-pod",
      index: "04",
      side: "left",
      testTag: "tor_orb_pod",
      composable: "TorKnurledOrbPod",
      sourcePath: "android/app/src/main/kotlin/dev/onionvault/ui/pods/TorKnurledOrbPod.kt",
      statePreviewId: "tor-circuit",
      boundsDp: { left: 8, top: 759, right: 112, bottom: 863, width: 104, height: 104 },
      boundsNorm: { left: 0.0194, top: 0.8295, width: 0.2524, height: 0.1137 },
      anchorNorm: { x: 0.1456, y: 0.8863 }
    },
    {
      id: "crt-address-pod",
      index: "05",
      side: "right",
      testTag: "crt_address_pod",
      composable: "RecessedCrtAddressPod",
      sourcePath: "android/app/src/main/kotlin/dev/onionvault/ui/pods/RecessedCrtAddressPod.kt",
      statePreviewId: "crt-directory",
      boundsDp: { left: 124, top: 648, right: 280, bottom: 783, width: 156, height: 135 },
      boundsNorm: { left: 0.301, top: 0.7082, width: 0.3786, height: 0.1475 },
      anchorNorm: { x: 0.4903, y: 0.782 }
    },
    {
      id: "pgp-rocker-pod",
      index: "06",
      side: "right",
      testTag: "pgp_rocker_pod",
      composable: "OpenPgpRockerPod",
      sourcePath: "android/app/src/main/kotlin/dev/onionvault/ui/pods/OpenPgpRockerPod.kt",
      statePreviewId: "pgp-keyring",
      boundsDp: { left: 337.68, top: 556.72, right: 419.68, bottom: 680.72, width: 82, height: 124 },
      boundsNorm: { left: 0.8196, top: 0.6084, width: 0.1804, height: 0.1355 },
      anchorNorm: { x: 0.89, y: 0.6762 }
    },
    {
      id: "side-remote-pod",
      index: "07",
      side: "right",
      testTag: "side_remote_pod",
      composable: "PhotoSoapSideRemote",
      sourcePath: "android/app/src/main/kotlin/dev/onionvault/ui/pods/PhotoSoapSideRemote.kt",
      statePreviewId: "side-remote",
      boundsDp: { left: 363.62, top: 706.35, right: 423.62, bottom: 892.35, width: 60, height: 186 },
      boundsNorm: { left: 0.8826, top: 0.772, width: 0.1174, height: 0.2033 },
      anchorNorm: { x: 0.89, y: 0.8736 }
    }
  ]
};

const appState = {
  manifest: FALLBACK_MANIFEST,
  activeComponentId: null,
  hoveredComponentId: null,
  activeStateId: "resting",
  showAllBounds: false
};

async function loadManifest() {
  try {
    const response = await fetch("./generated/homescreen-sync.json", { cache: "no-cache" });
    if (response.ok) {
      const parsed = await response.json();
      if (parsed && Array.isArray(parsed.components) && parsed.components.length > 0) {
        appState.manifest = parsed;
      }
    }
  } catch (_) {
    // Uses embedded FALLBACK_MANIFEST when opened directly via file://
  }
}

function getFocusedId() {
  return appState.hoveredComponentId || appState.activeComponentId;
}

function formatBoundsDp(boundsDp) {
  if (!boundsDp) return "";
  const w = Math.round(boundsDp.width);
  const h = Math.round(boundsDp.height);
  const x = Math.round(boundsDp.left);
  const y = Math.round(boundsDp.top);
  return `${w}×${h}dp @ (${x}, ${y})dp`;
}

function renderTelemetryHeader() {
  const { appVersion, gitCommit, viewport } = appState.manifest;
  const verEl = document.getElementById("sync-version-chip");
  const vpEl = document.getElementById("sync-viewport-chip");
  const footerCommitEl = document.getElementById("footer-sync-meta");

  if (verEl) {
    const commitSuffix = gitCommit && gitCommit !== "synced" ? ` · ${gitCommit}` : "";
    verEl.textContent = `v${appVersion}${commitSuffix}`;
  }
  if (vpEl && viewport) {
    vpEl.textContent = `${Math.round(viewport.widthDp)}×${Math.round(viewport.heightDp)}dp (${viewport.widthPx}×${viewport.heightPx}px)`;
  }
  if (footerCommitEl && viewport) {
    footerCommitEl.textContent =
      `Synced from @Preview BrowserChromePreview() · v${appVersion} · ${viewport.qualifiers} (${viewport.widthPx}×${viewport.heightPx}px)`;
  }
}

function renderStateTabs() {
  const container = document.getElementById("state-tabs");
  if (!container) return;
  container.innerHTML = "";

  for (const st of appState.manifest.states) {
    const meta = STATE_LABELS[st.id] || {
      tabLabel: st.previewFn,
      captionTitle: `@Preview ${st.previewFn}()`,
      captionDesc: ""
    };
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "state-tab";
    btn.id = `state-tab-${st.id}`;
    btn.setAttribute("role", "tab");
    btn.setAttribute("aria-selected", String(appState.activeStateId === st.id));
    btn.textContent = meta.tabLabel;
    btn.addEventListener("click", () => {
      appState.activeStateId = st.id;
      syncVisualState();
    });
    container.appendChild(btn);
  }
}

function createCalloutCard(comp) {
  const doc = COMPONENT_DOCS[comp.id] || {
    title: comp.composable,
    role: comp.testTag,
    summary: "",
    gestures: [],
    securityNote: "",
    previewActionLabel: "Inspect @Preview →"
  };

  const article = document.createElement("article");
  article.className = "callout-card";
  article.id = `callout-${comp.index}`;
  article.dataset.componentId = comp.id;
  article.tabIndex = 0;
  article.setAttribute("role", "button");
  article.setAttribute("aria-label", `${comp.index} ${doc.title}`);

  const gesturesHtml = doc.gestures
    .map(
      (g) => `
      <li class="gesture-item">
        <span class="gesture-key">${escapeHtml(g.key)}</span>
        <span>${escapeHtml(g.desc)}</span>
      </li>`
    )
    .join("");

  const hasDedicatedState = comp.statePreviewId && comp.statePreviewId !== "resting";
  const jumpButtonHtml = hasDedicatedState
    ? `<button type="button" class="preview-jump-btn" id="jump-${comp.index}" data-state-target="${escapeHtml(
        comp.statePreviewId
      )}">${escapeHtml(doc.previewActionLabel)}</button>`
    : "";

  article.innerHTML = `
    <div class="callout-header">
      <div class="callout-title-group">
        <span class="callout-index">${escapeHtml(comp.index)}</span>
        <h2 class="callout-title">${escapeHtml(doc.title)}</h2>
      </div>
      <code class="callout-tag">${escapeHtml(comp.testTag)}</code>
    </div>
    <p class="callout-summary">${escapeHtml(doc.summary)}</p>
    <ul class="gesture-list">${gesturesHtml}</ul>
    <div class="callout-footer">
      <span class="callout-bounds">${escapeHtml(formatBoundsDp(comp.boundsDp))}</span>
      <span class="callout-links">
        ${jumpButtonHtml}
        <a href="${REPO_BLOB_BASE}${escapeHtml(comp.sourcePath)}" target="_blank" rel="noopener noreferrer" id="src-link-${comp.index}">${escapeHtml(
          comp.composable
        )} ↗</a>
      </span>
    </div>
  `;

  article.addEventListener("mouseenter", () => {
    appState.hoveredComponentId = comp.id;
    syncVisualState();
  });
  article.addEventListener("mouseleave", () => {
    appState.hoveredComponentId = null;
    syncVisualState();
  });
  article.addEventListener("click", (e) => {
    if (e.target.closest("a") || e.target.closest(".preview-jump-btn")) {
      return;
    }
    if (appState.activeStateId !== "resting") {
      appState.activeStateId = "resting";
    }
    appState.activeComponentId = appState.activeComponentId === comp.id ? null : comp.id;
    syncVisualState();
  });
  article.addEventListener("keydown", (e) => {
    if (e.key === "Enter" || e.key === " ") {
      if (e.target.closest("a") || e.target.closest(".preview-jump-btn")) return;
      e.preventDefault();
      appState.activeStateId = "resting";
      appState.activeComponentId = appState.activeComponentId === comp.id ? null : comp.id;
      syncVisualState();
    }
  });

  const jumpBtn = article.querySelector(".preview-jump-btn");
  if (jumpBtn) {
    jumpBtn.addEventListener("click", (e) => {
      e.stopPropagation();
      const targetState = jumpBtn.getAttribute("data-state-target");
      if (targetState) {
        appState.activeComponentId = comp.id;
        appState.activeStateId = targetState;
        syncVisualState();
      }
    });
  }

  return article;
}

function renderCalloutColumnsAndOverlay() {
  const leftCol = document.getElementById("callouts-left");
  const rightCol = document.getElementById("callouts-right");
  const overlay = document.getElementById("viewport-overlay");
  if (!leftCol || !rightCol || !overlay) return;

  leftCol.innerHTML = "";
  rightCol.innerHTML = "";
  overlay.innerHTML = "";

  for (const comp of appState.manifest.components) {
    const card = createCalloutCard(comp);
    if (comp.side === "left") {
      leftCol.appendChild(card);
    } else {
      rightCol.appendChild(card);
    }

    const rectEl = document.createElement("div");
    rectEl.className = "bounds-rect";
    rectEl.id = `bounds-${comp.index}`;
    rectEl.dataset.componentId = comp.id;
    rectEl.style.left = `${(comp.boundsNorm.left * 100).toFixed(2)}%`;
    rectEl.style.top = `${(comp.boundsNorm.top * 100).toFixed(2)}%`;
    rectEl.style.width = `${(comp.boundsNorm.width * 100).toFixed(2)}%`;
    rectEl.style.height = `${(comp.boundsNorm.height * 100).toFixed(2)}%`;
    overlay.appendChild(rectEl);

    const pin = document.createElement("button");
    pin.type = "button";
    pin.className = "hotspot-pin";
    pin.id = `pin-${comp.index}`;
    pin.dataset.componentId = comp.id;
    pin.style.left = `${(comp.anchorNorm.x * 100).toFixed(2)}%`;
    pin.style.top = `${(comp.anchorNorm.y * 100).toFixed(2)}%`;
    pin.textContent = comp.index;
    pin.setAttribute(
      "aria-label",
      `Select component ${comp.index}: ${(COMPONENT_DOCS[comp.id] || {}).title || comp.composable}`
    );

    pin.addEventListener("mouseenter", () => {
      appState.hoveredComponentId = comp.id;
      syncVisualState();
    });
    pin.addEventListener("mouseleave", () => {
      appState.hoveredComponentId = null;
      syncVisualState();
    });
    pin.addEventListener("click", (e) => {
      e.stopPropagation();
      appState.activeComponentId = appState.activeComponentId === comp.id ? null : comp.id;
      const cardEl = document.getElementById(`callout-${comp.index}`);
      if (cardEl && window.innerWidth < 1180) {
        cardEl.scrollIntoView({ behavior: "smooth", block: "nearest" });
      }
      syncVisualState();
    });
    overlay.appendChild(pin);
  }
}

function renderSpecificationMatrix() {
  const tbody = document.getElementById("spec-table-body");
  if (!tbody) return;
  tbody.innerHTML = "";

  for (const comp of appState.manifest.components) {
    const doc = COMPONENT_DOCS[comp.id] || {};
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td><span class="callout-index">${escapeHtml(comp.index)}</span></td>
      <td>
        <strong>${escapeHtml(doc.title || comp.composable)}</strong><br />
        <a href="${REPO_BLOB_BASE}${escapeHtml(comp.sourcePath)}" target="_blank" rel="noopener noreferrer"><code>${escapeHtml(
          comp.composable
        )}</code> ↗</a>
      </td>
      <td><code>${escapeHtml(comp.testTag)}</code><br /><span class="callout-bounds">${escapeHtml(
        formatBoundsDp(comp.boundsDp)
      )}</span></td>
      <td>${escapeHtml(doc.role || "")}</td>
      <td>${escapeHtml(doc.securityNote || "")}</td>
    `;
    tbody.appendChild(tr);
  }
}

function drawLeaderLines() {
  const svg = document.getElementById("leader-svg");
  const stage = document.getElementById("diagram-stage");
  const viewport = document.getElementById("phone-viewport");
  if (!svg || !stage || !viewport) return;

  if (window.innerWidth <= 1180 || appState.activeStateId !== "resting") {
    svg.innerHTML = "";
    return;
  }

  const stageRect = stage.getBoundingClientRect();
  const vpRect = viewport.getBoundingClientRect();
  if (stageRect.width <= 0 || vpRect.width <= 0) return;

  svg.setAttribute("viewBox", `0 0 ${stageRect.width} ${stageRect.height}`);
  const focusedId = getFocusedId();
  const ns = "http://www.w3.org/2000/svg";
  const fragment = document.createDocumentFragment();

  for (const comp of appState.manifest.components) {
    const cardEl = document.getElementById(`callout-${comp.index}`);
    if (!cardEl) continue;
    const cardRect = cardEl.getBoundingClientRect();

    const isLeft = comp.side === "left";
    const startX = (isLeft ? cardRect.right : cardRect.left) - stageRect.left;
    const startY = cardRect.top + Math.min(28, cardRect.height * 0.24) - stageRect.top;

    const targetX = vpRect.left - stageRect.left + comp.anchorNorm.x * vpRect.width;
    const targetY = vpRect.top - stageRect.top + comp.anchorNorm.y * vpRect.height;

    const gutterBendX = isLeft
      ? startX + Math.max(14, (vpRect.left - stageRect.left - startX) * 0.52)
      : startX - Math.max(14, (startX - (vpRect.right - stageRect.left)) * 0.52);

    const d = `M ${startX.toFixed(1)} ${startY.toFixed(1)} L ${gutterBendX.toFixed(1)} ${startY.toFixed(
      1
    )} L ${gutterBendX.toFixed(1)} ${targetY.toFixed(1)} L ${targetX.toFixed(1)} ${targetY.toFixed(1)}`;

    const g = document.createElementNS(ns, "g");
    let cls = "leader-group";
    if (focusedId === comp.id) {
      cls += " is-active";
    } else if (focusedId && focusedId !== comp.id) {
      cls += " is-dimmed";
    }
    g.setAttribute("class", cls);

    const halo = document.createElementNS(ns, "path");
    halo.setAttribute("class", "leader-halo");
    halo.setAttribute("d", d);
    g.appendChild(halo);

    const line = document.createElementNS(ns, "path");
    line.setAttribute("class", "leader-line");
    line.setAttribute("d", d);
    g.appendChild(line);

    const port = document.createElementNS(ns, "circle");
    port.setAttribute("class", "leader-port");
    port.setAttribute("cx", startX.toFixed(1));
    port.setAttribute("cy", startY.toFixed(1));
    port.setAttribute("r", "4");
    g.appendChild(port);

    fragment.appendChild(g);
  }

  svg.innerHTML = "";
  svg.appendChild(fragment);
}

function syncVisualState() {
  const focusedId = getFocusedId();
  const isResting = appState.activeStateId === "resting";

  // 1. Sync state tabs & phone image
  const stateObj =
    appState.manifest.states.find((s) => s.id === appState.activeStateId) || appState.manifest.states[0];
  const imgEl = document.getElementById("preview-image");
  if (imgEl && stateObj && imgEl.getAttribute("src") !== `./${stateObj.image}`) {
    imgEl.setAttribute("src", `./${stateObj.image}`);
  }

  const captionTitleEl = document.getElementById("phone-caption-title");
  const captionDescEl = document.getElementById("phone-caption-desc");
  const labelMeta = STATE_LABELS[appState.activeStateId] || STATE_LABELS.resting;
  if (captionTitleEl) captionTitleEl.textContent = labelMeta.captionTitle;
  if (captionDescEl) captionDescEl.textContent = labelMeta.captionDesc;

  document.querySelectorAll(".state-tab").forEach((btn) => {
    btn.setAttribute("aria-selected", String(btn.id === `state-tab-${appState.activeStateId}`));
  });

  // 2. Sync callout cards, bounding boxes, and pins
  for (const comp of appState.manifest.components) {
    const card = document.getElementById(`callout-${comp.index}`);
    const rectEl = document.getElementById(`bounds-${comp.index}`);
    const pinEl = document.getElementById(`pin-${comp.index}`);

    const isFocused = focusedId === comp.id;
    const isDimmed = Boolean(focusedId && !isFocused);

    if (card) {
      card.classList.toggle("is-active", isFocused);
      card.classList.toggle("is-dimmed", isDimmed);
    }
    if (rectEl) {
      const showRect = isResting && (appState.showAllBounds || isFocused);
      rectEl.classList.toggle("is-visible", showRect);
    }
    if (pinEl) {
      pinEl.classList.toggle("is-active", isFocused);
      pinEl.classList.toggle("is-hidden", !isResting);
    }
  }

  // 3. Sync toolbar buttons
  const toggleBoundsBtn = document.getElementById("toggle-bounds-btn");
  if (toggleBoundsBtn) {
    toggleBoundsBtn.setAttribute("aria-pressed", String(appState.showAllBounds));
  }

  drawLeaderLines();
}

function escapeHtml(raw) {
  return String(raw ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function bindGlobalControls() {
  const toggleBoundsBtn = document.getElementById("toggle-bounds-btn");
  if (toggleBoundsBtn) {
    toggleBoundsBtn.addEventListener("click", () => {
      appState.activeStateId = "resting";
      appState.showAllBounds = !appState.showAllBounds;
      syncVisualState();
    });
  }

  const resetBtn = document.getElementById("reset-selection-btn");
  if (resetBtn) {
    resetBtn.addEventListener("click", () => {
      appState.activeStateId = "resting";
      appState.activeComponentId = null;
      appState.hoveredComponentId = null;
      appState.showAllBounds = false;
      syncVisualState();
    });
  }

  window.addEventListener("resize", () => {
    window.requestAnimationFrame(drawLeaderLines);
  });

  window.addEventListener("scroll", () => {
    if (window.innerWidth > 1180 && appState.activeStateId === "resting") {
      window.requestAnimationFrame(drawLeaderLines);
    }
  }, { passive: true });

  window.addEventListener("keydown", (e) => {
    if (e.target && (e.target.tagName === "INPUT" || e.target.tagName === "TEXTAREA")) return;
    if (e.key >= "1" && e.key <= "7") {
      const idx = Number(e.key) - 1;
      const target = appState.manifest.components[idx];
      if (target) {
        appState.activeStateId = "resting";
        appState.activeComponentId = appState.activeComponentId === target.id ? null : target.id;
        syncVisualState();
      }
    } else if (e.key === "Escape" || e.key === "0") {
      appState.activeComponentId = null;
      appState.hoveredComponentId = null;
      syncVisualState();
    } else if (e.key === "b" || e.key === "B") {
      appState.activeStateId = "resting";
      appState.showAllBounds = !appState.showAllBounds;
      syncVisualState();
    }
  });
}

async function init() {
  await loadManifest();
  renderTelemetryHeader();
  renderStateTabs();
  renderCalloutColumnsAndOverlay();
  renderSpecificationMatrix();
  bindGlobalControls();

  const imgEl = document.getElementById("preview-image");
  if (imgEl) {
    imgEl.addEventListener("load", () => {
      window.requestAnimationFrame(drawLeaderLines);
    });
  }

  syncVisualState();
  setTimeout(drawLeaderLines, 120);
}

document.addEventListener("DOMContentLoaded", init);
