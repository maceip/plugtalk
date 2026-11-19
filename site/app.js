/* ==========================================================================
   Trap — Synced Compose @Preview Showcase & Full-Screen Interactive Browser
   ========================================================================== */

/* --------------------------------------------------------------------------
   STAGE 1: Clean High-Contrast Prose Callouts + Synced Semantics Geometry
   -------------------------------------------------------------------------- */

const CALLOUT_PROSE = {
  "start-zine-viewport": {
    heading: "Full-Bleed Zero-JavaScript Viewport",
    copy:
      "Draws edge-to-edge from the top of the screen with no top bar, bottom bar, or notification banners. Displays the street-zine canvas on blank tabs and renders active .onion documents inside an off-the-record Chromium 156 surface with JavaScript disabled at every layer."
  },
  "perimeter-steel-rails": {
    heading: "Perimeter Brushed-Steel Rails",
    copy:
      "Frames the left and right edges with brushed-steel vertical bars and street overspray while passing every touch straight through to the webpage. Sweeps an acid-yellow pulse across the top seam while a Tor circuit or hidden service document is loading."
  },
  "kpt-back-pod": {
    heading: "Cast-Pewter Back & Forward Switch",
    copy:
      "Rectangular chamfered pewter keycap positioned above the Tor orb so the orb remains the sole sphere in the shell. A single tap steps back through tab history or returns to the start zine; holding briefly steps forward."
  },
  "tor-orb-pod": {
    heading: "Knurled Tor Circuit & Identity Orb",
    copy:
      "Driven by the embedded Rust Arti client, shifting from an amber orbital beacon during bootstrap to ultraviolet once the local SOCKS5h proxy is ready. Tapping opens per-site circuit telemetry; holding rotates to a fresh Tor identity."
  },
  "crt-address-pod": {
    heading: "Pewter CRT Address & Directory Monitor",
    copy:
      "Displays a tail-preserving green phosphor .onion readout so v3 vanity and checksum suffixes stay legible. Tapping expands the address bar alongside eleven SHA3-verified .onion services, swiping horizontally switches tabs, and pulling upward opens the tab switcher."
  },
  "pgp-rocker-pod": {
    heading: "Translucent Red OpenPGP Switchblade",
    copy:
      "Illuminates an internal LED whenever native DOM inspection detects armored OpenPGP blocks or form fields on the page. One click opens a fresh tab, two clicks open the Sequoia hardware keyring, and sliding the latch upward decrypts or verifies page armor."
  },
  "side-remote-pod": {
    heading: "2×5 LED Hardware Side Remote",
    copy:
      "Reflects live Tor readiness, stream isolation, loading state, keyring status, and zero-JS protection across ten recessed LEDs. Tapping expands the upright command deck for Onion Sites, Text Search, OpenPGP actions, and the Monero light wallet."
  }
};

const FALLBACK_MANIFEST = {
  schemaVersion: 1,
  appVersion: "0.1.44",
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
      statePreviewId: "resting",
      boundsNorm: { left: 0.03, top: 0.015, width: 0.94, height: 0.46 },
      anchorNorm: { x: 0.48, y: 0.19 }
    },
    {
      id: "perimeter-steel-rails",
      index: "02",
      side: "left",
      statePreviewId: "resting",
      boundsNorm: { left: 0.0, top: 0.0, width: 0.0291, height: 1.0 },
      anchorNorm: { x: 0.0146, y: 0.43 }
    },
    {
      id: "kpt-back-pod",
      index: "03",
      side: "left",
      statePreviewId: "resting",
      boundsNorm: { left: 0.0534, top: 0.7727, width: 0.1505, height: 0.0437 },
      anchorNorm: { x: 0.1286, y: 0.7945 }
    },
    {
      id: "tor-orb-pod",
      index: "04",
      side: "left",
      statePreviewId: "tor-circuit",
      boundsNorm: { left: 0.0194, top: 0.8295, width: 0.2524, height: 0.1137 },
      anchorNorm: { x: 0.1456, y: 0.8863 }
    },
    {
      id: "crt-address-pod",
      index: "05",
      side: "right",
      statePreviewId: "crt-directory",
      boundsNorm: { left: 0.301, top: 0.7082, width: 0.3786, height: 0.1475 },
      anchorNorm: { x: 0.4903, y: 0.782 }
    },
    {
      id: "pgp-rocker-pod",
      index: "06",
      side: "right",
      statePreviewId: "pgp-keyring",
      boundsNorm: { left: 0.8196, top: 0.6084, width: 0.1804, height: 0.1355 },
      anchorNorm: { x: 0.89, y: 0.6762 }
    },
    {
      id: "side-remote-pod",
      index: "07",
      side: "right",
      statePreviewId: "side-remote",
      boundsNorm: { left: 0.8826, top: 0.772, width: 0.1174, height: 0.2033 },
      anchorNorm: { x: 0.89, y: 0.8736 }
    }
  ]
};

const showcaseState = {
  manifest: FALLBACK_MANIFEST,
  hoveredId: null,
  lockedId: null,
  activePreviewId: "resting"
};

async function loadSyncManifest() {
  try {
    const res = await fetch("./generated/homescreen-sync.json", { cache: "no-cache" });
    if (res.ok) {
      const json = await res.json();
      if (json && Array.isArray(json.components) && json.components.length > 0) {
        showcaseState.manifest = json;
      }
    }
  } catch (_) {
    // Fallback manifest used when opened directly via file://
  }
}

function getFocusedCalloutId() {
  return showcaseState.hoveredId || showcaseState.lockedId;
}

function getOrderedComponents() {
  const comps = [...showcaseState.manifest.components];
  const left = comps.filter((c) => c.side === "left").sort((a, b) => a.anchorNorm.y - b.anchorNorm.y);
  const right = comps.filter((c) => c.side !== "left").sort((a, b) => a.anchorNorm.y - b.anchorNorm.y);
  return [...left, ...right];
}

function renderPhoneShowcase() {
  const leftCol = document.getElementById("callouts-left");
  const rightCol = document.getElementById("callouts-right");
  const overlay = document.getElementById("viewport-overlay");
  if (!leftCol || !rightCol || !overlay) return;

  leftCol.innerHTML = "";
  rightCol.innerHTML = "";
  overlay.innerHTML = "";

  for (const comp of getOrderedComponents()) {
    const prose = CALLOUT_PROSE[comp.id] || {
      heading: comp.composable || comp.id,
      copy: ""
    };

    const card = document.createElement("article");
    card.className = "callout-card";
    card.id = `callout-${comp.id}`;
    card.tabIndex = 0;
    card.setAttribute("role", "button");
    card.innerHTML = `
      <h2 class="callout-heading">${escapeHtml(prose.heading)}</h2>
      <p class="callout-copy">${escapeHtml(prose.copy)}</p>
    `;

    card.addEventListener("mouseenter", () => {
      showcaseState.hoveredId = comp.id;
      if (!showcaseState.lockedId) {
        showcaseState.activePreviewId = "resting";
      }
      syncShowcaseVisuals();
    });

    card.addEventListener("mouseleave", () => {
      showcaseState.hoveredId = null;
      syncShowcaseVisuals();
    });

    const toggleLock = () => {
      if (showcaseState.lockedId === comp.id) {
        showcaseState.lockedId = null;
        showcaseState.activePreviewId = "resting";
      } else {
        showcaseState.lockedId = comp.id;
        showcaseState.activePreviewId = comp.statePreviewId || "resting";
      }
      syncShowcaseVisuals();
    };

    card.addEventListener("click", toggleLock);
    card.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        toggleLock();
      }
    });

    if (comp.side === "left") {
      leftCol.appendChild(card);
    } else {
      rightCol.appendChild(card);
    }

    const rectEl = document.createElement("div");
    rectEl.className = "bounds-rect";
    rectEl.id = `bounds-${comp.id}`;
    rectEl.style.left = `${(comp.boundsNorm.left * 100).toFixed(2)}%`;
    rectEl.style.top = `${(comp.boundsNorm.top * 100).toFixed(2)}%`;
    rectEl.style.width = `${(comp.boundsNorm.width * 100).toFixed(2)}%`;
    rectEl.style.height = `${(comp.boundsNorm.height * 100).toFixed(2)}%`;
    overlay.appendChild(rectEl);

    const dot = document.createElement("button");
    dot.type = "button";
    dot.className = "hotspot-dot";
    dot.id = `dot-${comp.id}`;
    dot.style.left = `${(comp.anchorNorm.x * 100).toFixed(2)}%`;
    dot.style.top = `${(comp.anchorNorm.y * 100).toFixed(2)}%`;
    dot.setAttribute("aria-label", prose.heading);

    dot.addEventListener("mouseenter", () => {
      showcaseState.hoveredId = comp.id;
      syncShowcaseVisuals();
    });
    dot.addEventListener("mouseleave", () => {
      showcaseState.hoveredId = null;
      syncShowcaseVisuals();
    });
    dot.addEventListener("click", (e) => {
      e.stopPropagation();
      toggleLock();
    });

    overlay.appendChild(dot);
  }

  const stageEl = document.getElementById("phone-showcase");
  if (stageEl) {
    stageEl.addEventListener("click", (e) => {
      if (!e.target.closest(".callout-card") && !e.target.closest(".hotspot-dot")) {
        if (showcaseState.lockedId || showcaseState.activePreviewId !== "resting") {
          showcaseState.lockedId = null;
          showcaseState.activePreviewId = "resting";
          syncShowcaseVisuals();
        }
      }
    });
  }
}

function drawLeaderLines() {
  const svg = document.getElementById("leader-svg");
  const stage = document.getElementById("diagram-stage");
  const viewport = document.getElementById("phone-viewport");
  if (!svg || !stage || !viewport) return;

  if (window.innerWidth <= 1120 || showcaseState.activePreviewId !== "resting") {
    svg.innerHTML = "";
    return;
  }

  const stageRect = stage.getBoundingClientRect();
  const vpRect = viewport.getBoundingClientRect();
  if (stageRect.width <= 0 || vpRect.width <= 0) return;

  svg.setAttribute("viewBox", `0 0 ${stageRect.width} ${stageRect.height}`);
  const focusedId = getFocusedCalloutId();
  const ns = "http://www.w3.org/2000/svg";
  const fragment = document.createDocumentFragment();

  const ordered = getOrderedComponents();
  const leftLaneIds = ordered.filter((c) => c.side === "left").map((c) => c.id);
  const rightLaneIds = ordered.filter((c) => c.side !== "left").map((c) => c.id);

  for (const comp of ordered) {
    const cardEl = document.getElementById(`callout-${comp.id}`);
    if (!cardEl) continue;
    const cardRect = cardEl.getBoundingClientRect();

    const isLeft = comp.side === "left";
    const laneList = isLeft ? leftLaneIds : rightLaneIds;
    const laneIdx = Math.max(0, laneList.indexOf(comp.id));
    const laneCount = Math.max(1, laneList.length);
    const laneFrac = laneCount === 1 ? 0.5 : 0.28 + (laneIdx / (laneCount - 1)) * 0.44;

    const startX = (isLeft ? cardRect.right : cardRect.left) - stageRect.left;
    const startY = cardRect.top + cardRect.height * 0.5 - stageRect.top;

    const targetX = vpRect.left - stageRect.left + comp.anchorNorm.x * vpRect.width;
    const targetY = vpRect.top - stageRect.top + comp.anchorNorm.y * vpRect.height;

    const gutterWidth = isLeft
      ? vpRect.left - stageRect.left - startX
      : startX - (vpRect.right - stageRect.left);
    const gutterBendX = isLeft
      ? startX + Math.max(12, gutterWidth * laneFrac)
      : startX - Math.max(12, gutterWidth * (1 - laneFrac));

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

function syncShowcaseVisuals() {
  const focusedId = getFocusedCalloutId();
  const previewId = showcaseState.hoveredId ? "resting" : showcaseState.activePreviewId;
  const isResting = previewId === "resting";

  const stateEntry =
    showcaseState.manifest.states.find((s) => s.id === previewId) || showcaseState.manifest.states[0];
  const imgEl = document.getElementById("preview-image");
  if (imgEl && stateEntry && imgEl.getAttribute("src") !== `./${stateEntry.image}`) {
    imgEl.setAttribute("src", `./${stateEntry.image}`);
  }

  for (const comp of showcaseState.manifest.components) {
    const card = document.getElementById(`callout-${comp.id}`);
    const rectEl = document.getElementById(`bounds-${comp.id}`);
    const dotEl = document.getElementById(`dot-${comp.id}`);

    const isFocused = focusedId === comp.id;
    const isDimmed = Boolean(focusedId && !isFocused);

    if (card) {
      card.classList.toggle("is-active", isFocused);
      card.classList.toggle("is-dimmed", isDimmed);
    }
    if (rectEl) {
      rectEl.classList.toggle("is-visible", isResting && isFocused);
    }
    if (dotEl) {
      dotEl.classList.toggle("is-active", isFocused);
      dotEl.classList.toggle("is-hidden", !isResting);
    }
  }

  drawLeaderLines();
}

/* --------------------------------------------------------------------------
   STAGE 2: 100vw x 100vh Edge-to-Edge Interactive Trap Browser
   -------------------------------------------------------------------------- */

const TRUSTED_ONION_DIRECTORY = [
  {
    id: "duckduckgo",
    section: "SEARCH & PRESS",
    title: "DuckDuckGo",
    description: "Private .onion Search",
    url: "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/"
  },
  {
    id: "bellingcat",
    section: "SEARCH & PRESS",
    title: "Bellingcat",
    description: "Investigative Journalism",
    url: "http://bellcatmbguthn3age23lrbseln2lryzv3mt7whis7ktjw4qrestbzad.onion/"
  },
  {
    id: "dw_news",
    section: "SEARCH & PRESS",
    title: "Deutsche Welle",
    description: "International Newsroom",
    url: "https://www.dwnewsgngmhlplxy6o2twtfgjnrnjxbegbwqx6wnotdhkzt562tszfid.onion/en"
  },
  {
    id: "bbc_news",
    section: "SEARCH & PRESS",
    title: "BBC News",
    description: "International News",
    url: "https://www.bbcnewsd73hkzno2ini43t4gblxvycyac5aw4gnv7t2rccijh7745uqd.onion/news"
  },
  {
    id: "the_guardian",
    section: "SEARCH & PRESS",
    title: "The Guardian",
    description: "News & Whistleblowing",
    url: "https://www.guardian2zotagl6tmjucg3lrhxdk4dw3lhbqnkvvkywawy3oqfoprid.onion/us"
  },
  {
    id: "securedrop",
    section: "INFRASTRUCTURE & DROPBOXES",
    title: "SecureDrop Project",
    description: "Freedom of the Press Foundation",
    url: "http://sdolvtfhatvsysc6l34d65ymdwxcujausv7k5jk4cy5ttzhjoi6fzvyd.onion/"
  },
  {
    id: "tor_project",
    section: "INFRASTRUCTURE & DROPBOXES",
    title: "Tor Project",
    description: "Official Tor Project",
    url: "http://2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion/"
  },
  {
    id: "debian",
    section: "INFRASTRUCTURE & DROPBOXES",
    title: "Debian Archive",
    description: "Official Debian Packages",
    url: "http://5ekxbftvqg26oir5wle3p27ax3wksbxcecnm6oemju7bjra2pn26s3qd.onion/"
  },
  {
    id: "proton",
    section: "INFRASTRUCTURE & DROPBOXES",
    title: "Proton",
    description: "Encrypted Mail & Services",
    url: "https://protonmailrmez3lotccipshtkleegetolb73fuirgj7r4o4vfu7ozyd.onion/"
  },
  {
    id: "riseup",
    section: "INFRASTRUCTURE & DROPBOXES",
    title: "Riseup",
    description: "Autonomous Tech Collective",
    url: "http://vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion/"
  },
  {
    id: "mullvad",
    section: "INFRASTRUCTURE & DROPBOXES",
    title: "Mullvad",
    description: "Privacy Infrastructure",
    url: "http://o54hon2e2vj6c7m3aqqu6uyece65by3vgoxxhlqlsvkmacw6a7m7kiad.onion/en"
  }
];

const REMOTE_LED_COORDS = [
  { x: 24.68, y: 14.69 },
  { x: 71.52, y: 14.69 },
  { x: 24.68, y: 23.67 },
  { x: 71.52, y: 23.67 },
  { x: 24.68, y: 32.57 },
  { x: 71.52, y: 32.57 },
  { x: 24.68, y: 41.43 },
  { x: 71.52, y: 41.43 },
  { x: 24.68, y: 50.29 },
  { x: 71.52, y: 50.29 }
];

const GUARD_RELAYS = [
  "de-fra-guard04 (185.220.101.34)",
  "ch-zrh-guard11 (179.43.182.91)",
  "is-rek-guard02 (82.221.105.18)",
  "nl-ams-guard09 (198.98.56.142)"
];

const MIDDLE_RELAYS = [
  "se-sto-mid17 (193.138.218.70)",
  "at-vie-mid06 (185.100.87.202)",
  "ro-buc-mid12 (89.40.181.45)",
  "fi-hel-mid03 (95.216.144.89)"
];

function createTab(initialUrl = "about:blank", isPrivate = false) {
  return {
    id: `tab-${Math.random().toString(36).slice(2, 8)}`,
    history: [initialUrl],
    historyIndex: 0,
    isPrivate,
    zoomPercent: 100,
    submissionDraft: " Whistleblower report: attach ledger checksums before newsroom handoff.",
    xmr402Unlocked: false
  };
}

const fsBrowser = {
  tabs: [createTab("about:blank", false)],
  activeTabIndex: 0,
  isLoading: false,
  podsRetracted: false,
  torReady: true,
  circuitGeneration: 1,
  hiddenServiceOnly: false,
  clearnetRules: {},
  pendingClearnetUrl: null,
  crtOpen: false,
  remoteOpen: false,
  remoteMode: "menu",
  findOpen: false,
  findQuery: "",
  findIndex: 0,
  findTotal: 0,
  modalKind: null,
  decryptedMessage: null,
  keys: [
    {
      userId: "Source Identity <source@trap.onion>",
      fingerprint: "A3D9071F5C2E8B40D16F7394C8E5A2B6F04D71C9",
      hasSecret: true
    },
    {
      userId: "SecureDrop Newsroom <desk@securedrop.onion>",
      fingerprint: "6F1A2C939B4E77D10E585A623F8B91C47D0A11E2",
      hasSecret: false
    }
  ],
  xmrBalance: 1.8425,
  xmrSyncedHeight: 3389412
};

function currentTab() {
  return fsBrowser.tabs[fsBrowser.activeTabIndex] || fsBrowser.tabs[0];
}

function currentUrl() {
  const tab = currentTab();
  return tab.history[tab.historyIndex] || "about:blank";
}

function extractHost(url) {
  if (!url || url === "about:blank") return "";
  try {
    const normalized = /^[a-z]+:\/\//i.test(url) ? url : `http://${url}`;
    return new URL(normalized).hostname.toLowerCase();
  } catch (_) {
    return url.replace(/^[a-z]+:\/\//i, "").split("/")[0].toLowerCase();
  }
}

function extractShortOnion(url) {
  if (!url || url === "about:blank") return "search / .onion";
  const host = extractHost(url).replace(/^www\./, "");
  if (host.endsWith(".onion")) {
    const label = host.slice(0, -6);
    if (label.length > 11) {
      return `${label.slice(0, 5)}…${label.slice(-4)}.onion`;
    }
    return host;
  }
  return host.length > 16 ? `${host.slice(0, 14)}…` : host || "search / .onion";
}

function deriveIsolationToken(url) {
  const host = extractHost(url).replace(/^www\./, "");
  if (!host) return null;
  if (host.endsWith(".onion")) {
    return `iso-${host.slice(0, 16)}`;
  }
  return `iso-clear-${host.slice(0, 12)}`;
}

function pageHasPgp(url) {
  const host = extractHost(url);
  return host.includes("sdolvtfhatvsysc6") || host.includes("protonmailrmez3l") || host.includes("vww6ybal4bd7szmg");
}

function resolveUserNavigationInput(raw) {
  const trimmed = (raw || "").trim();
  if (!trimmed) return null;
  if (trimmed === "about:blank") return "about:blank";
  if (/^https?:\/\//i.test(trimmed)) return trimmed;
  if (!/\s/.test(trimmed) && (trimmed.includes(".onion") || trimmed.includes("."))) {
    const scheme = trimmed.includes(".onion") ? "http://" : "https://";
    return `${scheme}${trimmed}`;
  }
  return `https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/html/?q=${encodeURIComponent(trimmed)}`;
}

function triggerLoadPulse(callback) {
  fsBrowser.isLoading = true;
  renderFsChrome();
  window.setTimeout(() => {
    fsBrowser.isLoading = false;
    if (callback) callback();
    renderFsBrowser();
  }, 320);
}

function navigateTo(rawTarget, pushHistory = true) {
  const resolved = resolveUserNavigationInput(rawTarget);
  if (!resolved) return;

  fsBrowser.podsRetracted = false;
  fsBrowser.crtOpen = false;
  fsBrowser.remoteOpen = false;

  if (resolved !== "about:blank") {
    const host = extractHost(resolved);
    const isOnion = host.endsWith(".onion");
    if (!isOnion) {
      if (fsBrowser.hiddenServiceOnly) {
        fsBrowser.pendingClearnetUrl = resolved;
        fsBrowser.modalKind = "clearnet-blocked";
        renderFsBrowser();
        return;
      }
      const rootDomain = host.split(".").slice(-2).join(".");
      if (!fsBrowser.clearnetRules[host] && !fsBrowser.clearnetRules[`*.${rootDomain}`]) {
        fsBrowser.pendingClearnetUrl = resolved;
        fsBrowser.modalKind = "clearnet-consent";
        renderFsBrowser();
        return;
      }
    }
  }

  const tab = currentTab();
  if (pushHistory && tab.history[tab.historyIndex] !== resolved) {
    tab.history = tab.history.slice(0, tab.historyIndex + 1);
    tab.history.push(resolved);
    tab.historyIndex = tab.history.length - 1;
  }

  triggerLoadPulse(() => {
    const scrollEl = document.getElementById("fs-page-scroll");
    if (scrollEl) scrollEl.scrollTop = 0;
  });
}

function goBackInTab() {
  if (fsBrowser.podsRetracted) {
    fsBrowser.podsRetracted = false;
    renderFsChrome();
    return;
  }
  const tab = currentTab();
  if (tab.historyIndex > 0) {
    tab.historyIndex -= 1;
    triggerLoadPulse();
  } else if (currentUrl() !== "about:blank") {
    navigateTo("about:blank", true);
  }
}

function goForwardInTab() {
  fsBrowser.podsRetracted = false;
  const tab = currentTab();
  if (tab.historyIndex < tab.history.length - 1) {
    tab.historyIndex += 1;
    triggerLoadPulse();
  }
}

function openNewTab(isPrivate = false, initialUrl = "about:blank") {
  fsBrowser.podsRetracted = false;
  fsBrowser.tabs.push(createTab(initialUrl, isPrivate));
  fsBrowser.activeTabIndex = fsBrowser.tabs.length - 1;
  fsBrowser.remoteOpen = false;
  fsBrowser.modalKind = null;
  renderFsBrowser();
}

function rotateTorCircuit() {
  fsBrowser.podsRetracted = false;
  fsBrowser.torReady = false;
  fsBrowser.isLoading = true;
  renderFsChrome();
  if (fsBrowser.remoteOpen && fsBrowser.remoteMode === "circuit") {
    renderRemoteDrawer();
  }
  window.setTimeout(() => {
    fsBrowser.circuitGeneration += 1;
    fsBrowser.torReady = true;
    fsBrowser.isLoading = false;
    renderFsBrowser();
  }, 850);
}

function encryptSubmissionDraft() {
  const tab = currentTab();
  const textarea = document.getElementById("sd-submission-field");
  const rawText = textarea ? textarea.value : tab.submissionDraft;
  if (rawText.includes("-----BEGIN PGP MESSAGE-----")) return;
  const digest = btoa(unescape(encodeURIComponent(rawText.slice(0, 96)))).replace(/=+$/, "");
  tab.submissionDraft = [
    "-----BEGIN PGP MESSAGE-----",
    "",
    "hF4D7x9K2mQ1vL8SAQdA9c2f0e31b7d4491a03f8c2291b64e08a51f9c2d4e8a1",
    `${digest.slice(0, 64)}`,
    "0sF8nK4pQ2wR9tY6uI3oP7aS1dF5gH8jK2lZ9xC4vB6nM1qW3eR5tY7uI9oP0aS2",
    "=9K4Q",
    "-----END PGP MESSAGE-----"
  ].join("\n");
  fsBrowser.modalKind = null;
  renderFsBrowser();
}

function quickVerifyOrDecryptPage() {
  fsBrowser.podsRetracted = false;
  fsBrowser.decryptedMessage = {
    signer: "SecureDrop Newsroom <desk@securedrop.onion>",
    fingerprint: "6F1A 2C93 9B4E 77D1 0E58 5A62 3F8B 91C4 7D0A 11E2",
    body:
      "Receipt confirmed over isolated v3 rendezvous circuit.\n\nWe verified your Ed25519 detached signature on archive batch #409. Check your codename dropbox in 24 hours."
  };
  fsBrowser.modalKind = "pgp-decrypted";
  renderFsBrowser();
}

function renderHiddenServiceDocument(url) {
  const container = document.getElementById("fs-page-content");
  if (!container) return;

  const host = extractHost(url);
  const tab = currentTab();

  if (host.includes("duckduckgogg42xj")) {
    let query = "";
    try {
      const parsed = new URL(url);
      query = parsed.searchParams.get("q") || "";
    } catch (_) {
      query = "";
    }

    const filtered = TRUSTED_ONION_DIRECTORY.filter((entry) => {
      if (!query) return entry.id !== "duckduckgo";
      const hay = `${entry.title} ${entry.description} ${entry.url}`.toLowerCase();
      return hay.includes(query.toLowerCase());
    });

    const resultsHtml = filtered
      .map(
        (entry) => `
        <article class="hs-card">
          <button type="button" class="hs-link-btn" data-nav-url="${escapeHtml(entry.url)}">${escapeHtml(
            entry.title
          )} — ${escapeHtml(entry.description)}</button>
          <p style="margin-top:0.3rem;font-family:var(--font-mono);font-size:0.77rem;color:var(--phosphor);">${escapeHtml(
            entry.url
          )}</p>
        </article>`
      )
      .join("");

    container.innerHTML = `
      <div class="hs-doc">
        <h2 class="hs-title">DuckDuckGo Onion Search (Zero-JS HTML)</h2>
        <p class="hs-sub">Connected via Arti v3 rendezvous circuit <code>${escapeHtml(
          deriveIsolationToken(url) || ""
        )}</code> with script execution disabled.</p>
        <form class="hs-search-form" id="ddg-search-form">
          <input type="text" class="hs-input" id="ddg-search-input" value="${escapeHtml(
            query
          )}" placeholder="Search verified .onion services…" />
          <button type="submit" class="fs-well-btn fs-well-btn--go" id="ddg-search-submit">Search</button>
        </form>
        <article class="hs-card">
          <button type="button" class="hs-link-btn" data-nav-url="http://xmr402vault7k9qp2m4n8v3c6x1z5l9r2t4y6u8i0o2p4a6s8d0f2gad.onion/">XMR402 Cryptographic Wire Archive (HTTP 402 .onion Demo)</button>
          <p style="margin-top:0.3rem;">Zero-account hidden service archive gated by an instant Monero 402 payment proof.</p>
        </article>
        ${resultsHtml || `<article class="hs-card"><p>No matching hidden services for "${escapeHtml(query)}".</p></article>`}
      </div>
    `;

    const form = document.getElementById("ddg-search-form");
    const input = document.getElementById("ddg-search-input");
    if (form && input) {
      form.addEventListener("submit", (e) => {
        e.preventDefault();
        navigateTo(input.value || TRUSTED_ONION_DIRECTORY[0].url, true);
      });
    }
  } else if (host.includes("sdolvtfhatvsysc6")) {
    container.innerHTML = `
      <div class="hs-doc">
        <h2 class="hs-title">SecureDrop Whistleblower Submission Desk</h2>
        <p class="hs-sub">Native C++ DevTools inspection detected 1 editable submission field, 1 armored OpenPGP message, and 1 public key block — zero page JavaScript executed.</p>

        <div class="hs-card">
          <h3>Encrypted Submission Field</h3>
          <textarea class="hs-textarea" id="sd-submission-field">${escapeHtml(tab.submissionDraft)}</textarea>
          <div class="hs-action-row">
            <button type="button" class="fs-well-btn fs-well-btn--go" id="sd-encrypt-btn">Encrypt Field via Sequoia OpenPGP</button>
          </div>
        </div>

        <div class="hs-card">
          <h3>Armored Newsroom Reply</h3>
          <pre class="fs-mono-block">-----BEGIN PGP MESSAGE-----

hQEMA8x3vR9L2kP1AQf/W9d4c7e1a0b382f5c6d9e0f1a2b3c4d5e6f7a8b9c0d1
9mN4bV7cX2zL5kJ8hG1fD4sA7pO0iU3yT6rE9wQ2aZ5xS8dC1fV4gB7hN0jM3kL6
=x8P2
-----END PGP MESSAGE-----</pre>
          <div class="hs-action-row">
            <button type="button" class="fs-well-btn" id="sd-decrypt-btn">Verify &amp; Decrypt Block</button>
          </div>
        </div>

        <div class="hs-card">
          <h3>Newsroom Public Key</h3>
          <pre class="fs-mono-block">-----BEGIN PGP PUBLIC KEY BLOCK-----

mDMEZ8xK1xYJKwYBBAHaRw8BAQdA7f9e2c4b8a1d6f0e3c5b7a9d2e4f6a8b0c1d
Newsroom Desk &lt;desk@securedrop.onion&gt;
=4L9z
-----END PGP PUBLIC KEY BLOCK-----</pre>
        </div>
      </div>
    `;

    const textarea = document.getElementById("sd-submission-field");
    if (textarea) {
      textarea.addEventListener("input", () => {
        tab.submissionDraft = textarea.value;
      });
    }
    const encBtn = document.getElementById("sd-encrypt-btn");
    if (encBtn) encBtn.addEventListener("click", encryptSubmissionDraft);
    const decBtn = document.getElementById("sd-decrypt-btn");
    if (decBtn) decBtn.addEventListener("click", quickVerifyOrDecryptPage);
  } else if (host.includes("xmr402vault7k9qp")) {
    if (tab.xmr402Unlocked) {
      container.innerHTML = `
        <div class="hs-doc">
          <h2 class="hs-title">XMR402 Archive — Unlocked via Subaddress Proof</h2>
          <p class="hs-sub">HTTP 200 OK · Zero-confirmation Monero tx proof verified over Tor Keep-Alive circuit.</p>
          <div class="hs-card">
            <h3>Dispatches: Zero-JavaScript Hidden Service Architecture</h3>
            <p>Full cryptographic wire dossier unlocked without accounts, cookies, or script execution. Subaddress isolation ensured zero on-chain linkage across tabs.</p>
          </div>
        </div>
      `;
    } else {
      container.innerHTML = `
        <div class="hs-doc">
          <h2 class="hs-title">HTTP 402 Payment Required — XMR402 Challenge</h2>
          <p class="hs-sub">This hidden service requests a <code>0.0150 XMR</code> payment proof over Tor before serving the archive payload.</p>
          <div class="hs-card">
            <h3>Challenge Parameters</h3>
            <pre class="fs-mono-block">WWW-Authenticate: XMR402 amount="0.0150", recipient="88a9kXmr402Subaddress9vL3pQ7mN2cR5tW8yZ1dF4gH6jK9uV2bN5mQ8xP3w", nonce="9f3c2a7e1b84"</pre>
            <div class="hs-action-row">
              <button type="button" class="fs-well-btn fs-well-btn--go" id="xmr402-pay-btn">Pay 0.0150 XMR &amp; Unlock Archive</button>
            </div>
          </div>
        </div>
      `;
      const payBtn = document.getElementById("xmr402-pay-btn");
      if (payBtn) {
        payBtn.addEventListener("click", () => {
          if (fsBrowser.xmrBalance >= 0.015) {
            fsBrowser.xmrBalance = Number((fsBrowser.xmrBalance - 0.015).toFixed(4));
            tab.xmr402Unlocked = true;
            triggerLoadPulse();
          }
        });
      }
    }
  } else {
    const known = TRUSTED_ONION_DIRECTORY.find((e) => extractHost(e.url) === host);
    const title = known ? `${known.title} (${known.description})` : host;
    container.innerHTML = `
      <div class="hs-doc">
        <h2 class="hs-title">${escapeHtml(title)}</h2>
        <p class="hs-sub">Served over isolated Tor stream <code>${escapeHtml(
          deriveIsolationToken(url) || ""
        )}</code> · <code>javascript_enabled = false</code>.</p>
        <div class="hs-card">
          <h3>Endpoint Telemetry</h3>
          <pre class="fs-mono-block">Origin:    ${escapeHtml(url)}
Transport: Arti SOCKS5h (127.0.0.1:41952)
Circuit:   ${escapeHtml(GUARD_RELAYS[fsBrowser.circuitGeneration % GUARD_RELAYS.length])} → ${escapeHtml(
      MIDDLE_RELAYS[fsBrowser.circuitGeneration % MIDDLE_RELAYS.length]
    )} → v3 Rendezvous
Scripts:   Blocked (Blink WebPreferences + V8 jitless + CDP Emulation)</pre>
        </div>
        <div class="hs-card">
          <h3>Verified Directory Shortcuts</h3>
          <div class="hs-action-row">
            <button type="button" class="fs-well-btn" data-nav-url="${escapeHtml(
              TRUSTED_ONION_DIRECTORY[0].url
            )}">DuckDuckGo .onion</button>
            <button type="button" class="fs-well-btn" data-nav-url="${escapeHtml(
              TRUSTED_ONION_DIRECTORY[5].url
            )}">SecureDrop PGP Desk</button>
            <button type="button" class="fs-well-btn" data-nav-url="http://xmr402vault7k9qp2m4n8v3c6x1z5l9r2t4y6u8i0o2p4a6s8d0f2gad.onion/">XMR402 402 Gate</button>
          </div>
        </div>
      </div>
    `;
  }

  container.querySelectorAll("[data-nav-url]").forEach((btn) => {
    btn.addEventListener("click", () => {
      const target = btn.getAttribute("data-nav-url");
      if (target) navigateTo(target, true);
    });
  });

  if (fsBrowser.findOpen && fsBrowser.findQuery.trim()) {
    applyFindHighlights();
  }
}

function renderDirectorySheet() {
  const sheet = document.getElementById("fs-directory-sheet");
  if (!sheet) return;

  const sections = ["SEARCH & PRESS", "INFRASTRUCTURE & DROPBOXES"];
  let html = "";
  for (const section of sections) {
    html += `<div class="fs-dir-section">${escapeHtml(section)}</div>`;
    for (const entry of TRUSTED_ONION_DIRECTORY.filter((e) => e.section === section)) {
      html += `
        <button type="button" class="fs-dir-row" data-dir-url="${escapeHtml(entry.url)}">
          <span class="fs-dir-meta">
            <strong>${escapeHtml(entry.title)}</strong>
            <span>${escapeHtml(entry.description)}</span>
          </span>
          <span class="fs-dir-onion">${escapeHtml(extractShortOnion(entry.url))}</span>
        </button>
      `;
    }
  }
  sheet.innerHTML = html;

  sheet.querySelectorAll("[data-dir-url]").forEach((row) => {
    row.addEventListener("click", () => {
      const target = row.getAttribute("data-dir-url");
      if (target) navigateTo(target, true);
    });
  });
}

function renderRemoteLeds() {
  const container = document.getElementById("fs-remote-leds");
  if (!container) return;

  const url = currentUrl();
  const isStart = url === "about:blank";
  const host = extractHost(url);
  const isOnion = host.endsWith(".onion");
  const hasPgp = pageHasPgp(url);

  const states = [
    fsBrowser.torReady ? "green" : "off",
    !fsBrowser.torReady ? "amber" : "off",
    isOnion || fsBrowser.hiddenServiceOnly ? "green" : "off",
    !isOnion && !isStart ? "amber" : "off",
    deriveIsolationToken(url) || isStart ? "green" : "off",
    fsBrowser.isLoading ? "amber" : "off",
    fsBrowser.keys.length > 0 ? "green" : "off",
    hasPgp ? "amber" : "off",
    "green",
    "off"
  ];

  let html = `<span class="fs-led-dot ${fsBrowser.torReady ? "is-green" : "is-amber"}" style="left:48.35%;top:6.04%;"></span>`;
  REMOTE_LED_COORDS.forEach((pt, idx) => {
    const st = states[idx];
    const cls = st === "green" ? "is-green" : st === "amber" ? "is-amber" : "";
    html += `<span class="fs-led-dot ${cls}" style="left:${pt.x}%;top:${pt.y}%;"></span>`;
  });
  container.innerHTML = html;
}

function renderRemoteDrawer() {
  const drawer = document.getElementById("fs-remote-drawer");
  const titleEl = document.getElementById("fs-drawer-title");
  const bodyEl = document.getElementById("fs-drawer-body");
  if (!drawer || !titleEl || !bodyEl) return;

  drawer.hidden = !fsBrowser.remoteOpen;
  if (!fsBrowser.remoteOpen) return;

  const tab = currentTab();
  const url = currentUrl();

  if (fsBrowser.remoteMode === "circuit") {
    titleEl.textContent = "Tor Circuit";
    const iso = deriveIsolationToken(url) || "standby (start zine)";
    const guard = GUARD_RELAYS[fsBrowser.circuitGeneration % GUARD_RELAYS.length];
    const middle = MIDDLE_RELAYS[fsBrowser.circuitGeneration % MIDDLE_RELAYS.length];
    bodyEl.innerHTML = `
      <div class="fs-circuit-card">
        <div><strong>Arti SOCKS5h:</strong> <code>${fsBrowser.torReady ? "127.0.0.1:41952 (Ready)" : "Rotating circuit…"}</code></div>
        <div><strong>Isolation Token:</strong> <code>${escapeHtml(iso)}</code></div>
        <div class="fs-circuit-hop"><span>01 Guard</span><span>${escapeHtml(guard.split(" ")[0])}</span></div>
        <div class="fs-circuit-hop"><span>02 Middle</span><span>${escapeHtml(middle.split(" ")[0])}</span></div>
        <div class="fs-circuit-hop"><span>03 Rendezvous</span><span>v3 .onion descriptor</span></div>
      </div>
      <button type="button" class="fs-remote-btn" id="rem-toggle-hs-only">
        <span>.onion-Only Lock</span>
        <span class="mono-sub">${fsBrowser.hiddenServiceOnly ? "ON" : "OFF"}</span>
      </button>
      <button type="button" class="fs-remote-btn" id="rem-rotate-id">
        <span>Rotate Tor Identity</span>
        <span class="mono-sub">New Circuit</span>
      </button>
      <button type="button" class="fs-remote-btn" id="rem-back-menu">
        <span>← All Remote Controls</span>
      </button>
    `;

    document.getElementById("rem-toggle-hs-only")?.addEventListener("click", () => {
      fsBrowser.hiddenServiceOnly = !fsBrowser.hiddenServiceOnly;
      renderRemoteDrawer();
      renderRemoteLeds();
    });
    document.getElementById("rem-rotate-id")?.addEventListener("click", rotateTorCircuit);
    document.getElementById("rem-back-menu")?.addEventListener("click", () => {
      fsBrowser.remoteMode = "menu";
      renderRemoteDrawer();
    });
    return;
  }

  titleEl.textContent = "Hardware Remote";
  bodyEl.innerHTML = `
    <button type="button" class="fs-remote-btn" id="rem-onion-sites">
      <span>Onion Sites Directory</span>
      <span class="mono-sub">11 Verified</span>
    </button>
    <button type="button" class="fs-remote-btn" id="rem-tor-circuit">
      <span>Tor Circuit &amp; .onion Lock</span>
      <span class="mono-sub">${fsBrowser.torReady ? "Ready" : "Boot"}</span>
    </button>
    <div class="fs-remote-grid">
      <button type="button" class="fs-remote-btn" id="rem-new-tab"><span>New Tab</span><span>+</span></button>
      <button type="button" class="fs-remote-btn" id="rem-tabs"><span>Tabs</span><span class="mono-sub">${fsBrowser.tabs.length}</span></button>
      <button type="button" class="fs-remote-btn" id="rem-reload"><span>Reload</span><span>↻</span></button>
      <button type="button" class="fs-remote-btn" id="rem-home"><span>Start Zine</span><span>⌂</span></button>
      <button type="button" class="fs-remote-btn" id="rem-zoom-out"><span>Zoom −</span><span class="mono-sub">${tab.zoomPercent}%</span></button>
      <button type="button" class="fs-remote-btn" id="rem-zoom-in"><span>Zoom +</span><span class="mono-sub">${tab.zoomPercent}%</span></button>
    </div>
    <button type="button" class="fs-remote-btn" id="rem-find">
      <span>Text Search in Page</span>
      <span class="mono-sub">Zero-JS CDP</span>
    </button>
    <button type="button" class="fs-remote-btn" id="rem-keyring">
      <span>OpenPGP Keyring</span>
      <span class="mono-sub">${fsBrowser.keys.length} Keys</span>
    </button>
    <button type="button" class="fs-remote-btn" id="rem-wallet">
      <span>Monero Light Wallet</span>
      <span class="mono-sub">${fsBrowser.xmrBalance.toFixed(4)} XMR</span>
    </button>
  `;

  document.getElementById("rem-onion-sites")?.addEventListener("click", () => {
    fsBrowser.remoteOpen = false;
    fsBrowser.crtOpen = true;
    renderFsBrowser();
    document.getElementById("fs-crt-input")?.focus();
  });
  document.getElementById("rem-tor-circuit")?.addEventListener("click", () => {
    fsBrowser.remoteMode = "circuit";
    renderRemoteDrawer();
  });
  document.getElementById("rem-new-tab")?.addEventListener("click", () => openNewTab(false));
  document.getElementById("rem-tabs")?.addEventListener("click", () => {
    fsBrowser.remoteOpen = false;
    fsBrowser.modalKind = "tabs";
    renderFsBrowser();
  });
  document.getElementById("rem-reload")?.addEventListener("click", () => {
    fsBrowser.remoteOpen = false;
    triggerLoadPulse();
  });
  document.getElementById("rem-home")?.addEventListener("click", () => navigateTo("about:blank", true));
  document.getElementById("rem-zoom-out")?.addEventListener("click", () => {
    tab.zoomPercent = Math.max(80, tab.zoomPercent - 10);
    renderFsBrowser();
  });
  document.getElementById("rem-zoom-in")?.addEventListener("click", () => {
    tab.zoomPercent = Math.min(150, tab.zoomPercent + 10);
    renderFsBrowser();
  });
  document.getElementById("rem-find")?.addEventListener("click", () => {
    fsBrowser.remoteOpen = false;
    if (currentUrl() === "about:blank") {
      navigateTo(TRUSTED_ONION_DIRECTORY[5].url, true);
    }
    fsBrowser.findOpen = true;
    renderFsBrowser();
    document.getElementById("fs-find-input")?.focus();
  });
  document.getElementById("rem-keyring")?.addEventListener("click", () => {
    fsBrowser.remoteOpen = false;
    fsBrowser.modalKind = "keyring";
    renderFsBrowser();
  });
  document.getElementById("rem-wallet")?.addEventListener("click", () => {
    fsBrowser.remoteOpen = false;
    fsBrowser.modalKind = "wallet";
    renderFsBrowser();
  });
}

function renderModalSheet() {
  const layer = document.getElementById("fs-modal-layer");
  const sheet = document.getElementById("fs-modal-sheet");
  if (!layer || !sheet) return;

  layer.hidden = !fsBrowser.modalKind;
  if (!fsBrowser.modalKind) return;

  if (fsBrowser.modalKind === "tabs") {
    const rows = fsBrowser.tabs
      .map((t, idx) => {
        const u = t.history[t.historyIndex];
        const label = u === "about:blank" ? "Trap Start Zine" : extractShortOnion(u);
        return `
          <div class="fs-list-card ${idx === fsBrowser.activeTabIndex ? "is-current" : ""}">
            <div>
              <strong>${escapeHtml(label)}</strong>
              <div style="font-family:var(--font-mono);font-size:0.74rem;color:var(--ink-muted);">${
                t.isPrivate ? "Private Ephemeral Tab" : "Isolated Tor Tab"
              }</div>
            </div>
            <div style="display:flex;gap:0.4rem;">
              <button type="button" class="fs-well-btn" data-select-tab="${idx}">Switch</button>
              ${
                fsBrowser.tabs.length > 1
                  ? `<button type="button" class="fs-well-btn" data-close-tab="${idx}">✕</button>`
                  : ""
              }
            </div>
          </div>
        `;
      })
      .join("");

    sheet.innerHTML = `
      <div class="fs-modal-head">
        <h3>Spatial Tab Switcher (${fsBrowser.tabs.length})</h3>
        <button type="button" class="fs-drawer-close" id="modal-close-btn">✕</button>
      </div>
      <div class="fs-modal-stack">
        ${rows}
        <div style="display:flex;gap:0.5rem;margin-top:0.35rem;">
          <button type="button" class="fs-well-btn fs-well-btn--go" id="modal-new-tab">+ New Tor Tab</button>
          <button type="button" class="fs-well-btn" id="modal-new-priv-tab">+ Private Tab</button>
        </div>
      </div>
    `;

    sheet.querySelectorAll("[data-select-tab]").forEach((btn) => {
      btn.addEventListener("click", () => {
        fsBrowser.activeTabIndex = Number(btn.getAttribute("data-select-tab"));
        fsBrowser.modalKind = null;
        renderFsBrowser();
      });
    });
    sheet.querySelectorAll("[data-close-tab]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const idx = Number(btn.getAttribute("data-close-tab"));
        fsBrowser.tabs.splice(idx, 1);
        if (fsBrowser.activeTabIndex >= fsBrowser.tabs.length) {
          fsBrowser.activeTabIndex = fsBrowser.tabs.length - 1;
        }
        renderFsBrowser();
      });
    });
    document.getElementById("modal-new-tab")?.addEventListener("click", () => openNewTab(false));
    document.getElementById("modal-new-priv-tab")?.addEventListener("click", () => openNewTab(true));
  } else if (fsBrowser.modalKind === "keyring") {
    const hasPgpOnPage = pageHasPgp(currentUrl());
    const keysHtml = fsBrowser.keys
      .map(
        (k) => `
        <div class="fs-list-card">
          <div>
            <strong>${escapeHtml(k.userId)}</strong>
            <div style="font-family:var(--font-mono);font-size:0.72rem;color:var(--phosphor);">${escapeHtml(
              k.fingerprint
            )}</div>
          </div>
          <span style="font-family:var(--font-mono);font-size:0.72rem;color:var(--amber);">${
            k.hasSecret ? "Ed25519 Secret" : "Public Key"
          }</span>
        </div>`
      )
      .join("");

    sheet.innerHTML = `
      <div class="fs-modal-head">
        <h3>Sequoia OpenPGP Keyring</h3>
        <button type="button" class="fs-drawer-close" id="modal-close-btn">✕</button>
      </div>
      <div class="fs-modal-stack">
        ${
          hasPgpOnPage
            ? `<div class="hs-action-row" style="margin-top:0;">
                <button type="button" class="fs-well-btn fs-well-btn--go" id="kr-encrypt-page">Encrypt Page Field</button>
                <button type="button" class="fs-well-btn" id="kr-decrypt-page">Verify &amp; Decrypt Page Block</button>
              </div>`
            : `<div style="display:flex;align-items:center;justify-content:space-between;gap:0.75rem;padding:0.65rem 0.85rem;border-radius:10px;background:#141b25;border:1px solid #2a3444;">
                <span style="font-size:0.85rem;color:var(--ink-body);">Open SecureDrop .onion to test live zero-JS field encryption &amp; decryption.</span>
                <button type="button" class="fs-well-btn fs-well-btn--go" id="kr-open-sd">Open SecureDrop</button>
              </div>`
        }
        ${keysHtml}
        <button type="button" class="fs-well-btn" id="kr-gen-key">+ Generate Ephemeral Ed25519 / Cv25519 Keypair</button>
      </div>
    `;

    document.getElementById("kr-encrypt-page")?.addEventListener("click", encryptSubmissionDraft);
    document.getElementById("kr-decrypt-page")?.addEventListener("click", quickVerifyOrDecryptPage);
    document.getElementById("kr-open-sd")?.addEventListener("click", () => {
      fsBrowser.modalKind = null;
      navigateTo(TRUSTED_ONION_DIRECTORY[5].url, true);
    });
    document.getElementById("kr-gen-key")?.addEventListener("click", () => {
      const hex = Array.from({ length: 40 }, () =>
        Math.floor(Math.random() * 16)
          .toString(16)
          .toUpperCase()
      ).join("");
      fsBrowser.keys.unshift({
        userId: `Burner Alias #${fsBrowser.keys.length + 1} <anon@onion>`,
        fingerprint: hex,
        hasSecret: true
      });
      renderModalSheet();
      renderRemoteLeds();
    });
  } else if (fsBrowser.modalKind === "pgp-decrypted" && fsBrowser.decryptedMessage) {
    sheet.innerHTML = `
      <div class="fs-modal-head">
        <h3>Verified OpenPGP Plaintext</h3>
        <button type="button" class="fs-drawer-close" id="modal-close-btn">✕</button>
      </div>
      <div class="fs-modal-stack">
        <div class="fs-list-card" style="border-color:var(--phosphor);">
          <div>
            <strong style="color:var(--phosphor);">Good Ed25519 Signature — ${escapeHtml(
              fsBrowser.decryptedMessage.signer
            )}</strong>
            <div style="font-family:var(--font-mono);font-size:0.72rem;color:var(--ink-body);">${escapeHtml(
              fsBrowser.decryptedMessage.fingerprint
            )}</div>
          </div>
        </div>
        <pre class="fs-mono-block">${escapeHtml(fsBrowser.decryptedMessage.body)}</pre>
      </div>
    `;
  } else if (fsBrowser.modalKind === "wallet") {
    sheet.innerHTML = `
      <div class="fs-modal-head">
        <h3>Monero Tor Light Wallet (ov-xmr)</h3>
        <button type="button" class="fs-drawer-close" id="modal-close-btn">✕</button>
      </div>
      <div class="fs-modal-stack">
        <div class="fs-list-card">
          <div>
            <strong style="font-size:1.2rem;font-family:var(--font-mono);color:var(--amber);">${fsBrowser.xmrBalance.toFixed(
              4
            )} XMR</strong>
            <div style="font-family:var(--font-mono);font-size:0.74rem;color:var(--phosphor);">Synced over .onion monerod RPC · Height #${
              fsBrowser.xmrSyncedHeight
            }</div>
          </div>
          <button type="button" class="fs-well-btn" id="xmr-sync-btn">Sync</button>
        </div>
        <pre class="fs-mono-block">Site-Isolated Subaddress:
87tTrapSubaddr9mK2vL8pQ4xR7nC1wZ5yB3dF6gH8jK2mN4pQ6rS8tU0vW2xY4z</pre>
        <button type="button" class="fs-well-btn fs-well-btn--go" id="xmr-open-402-demo">Test HTTP 402 XMR402 Hidden Service Gate</button>
      </div>
    `;

    document.getElementById("xmr-sync-btn")?.addEventListener("click", () => {
      fsBrowser.xmrSyncedHeight += Math.floor(1 + Math.random() * 4);
      renderModalSheet();
    });
    document.getElementById("xmr-open-402-demo")?.addEventListener("click", () => {
      fsBrowser.modalKind = null;
      navigateTo("http://xmr402vault7k9qp2m4n8v3c6x1z5l9r2t4y6u8i0o2p4a6s8d0f2gad.onion/", true);
    });
  } else if (fsBrowser.modalKind === "clearnet-consent" && fsBrowser.pendingClearnetUrl) {
    const host = extractHost(fsBrowser.pendingClearnetUrl);
    const rootDomain = host.split(".").slice(-2).join(".");
    sheet.innerHTML = `
      <div class="fs-modal-head">
        <h3>Clearnet Destination Held at SOCKS5h Gate</h3>
        <button type="button" class="fs-drawer-close" id="modal-close-btn">✕</button>
      </div>
      <div class="fs-modal-stack">
        <p style="margin:0;color:var(--ink-body);font-size:0.92rem;">
          <code>${escapeHtml(host)}</code> is a non-.onion clearnet origin. Trap holds unrecognized clearnet streams until you authorize routing over Tor exit relays.
        </p>
        <div class="hs-action-row">
          <button type="button" class="fs-well-btn fs-well-btn--go" id="cn-allow-3d">Allow 3 Days (*.${escapeHtml(
            rootDomain
          )})</button>
          <button type="button" class="fs-well-btn" id="cn-allow-once">Allow Once</button>
          <button type="button" class="fs-well-btn" id="cn-block">Block</button>
        </div>
      </div>
    `;

    document.getElementById("cn-allow-3d")?.addEventListener("click", () => {
      const target = fsBrowser.pendingClearnetUrl;
      fsBrowser.clearnetRules[`*.${rootDomain}`] = true;
      fsBrowser.pendingClearnetUrl = null;
      fsBrowser.modalKind = null;
      if (target) navigateTo(target, true);
    });
    document.getElementById("cn-allow-once")?.addEventListener("click", () => {
      const target = fsBrowser.pendingClearnetUrl;
      fsBrowser.clearnetRules[host] = true;
      fsBrowser.pendingClearnetUrl = null;
      fsBrowser.modalKind = null;
      if (target) navigateTo(target, true);
    });
    document.getElementById("cn-block")?.addEventListener("click", () => {
      fsBrowser.pendingClearnetUrl = null;
      fsBrowser.modalKind = null;
      renderFsBrowser();
    });
  } else if (fsBrowser.modalKind === "clearnet-blocked") {
    sheet.innerHTML = `
      <div class="fs-modal-head">
        <h3>Blocked by .onion-Only Lock</h3>
        <button type="button" class="fs-drawer-close" id="modal-close-btn">✕</button>
      </div>
      <div class="fs-modal-stack">
        <p style="margin:0;color:var(--ink-body);font-size:0.92rem;">
          <code>${escapeHtml(
            extractHost(fsBrowser.pendingClearnetUrl || "")
          )}</code> was refused because <strong>.onion-Only Lock</strong> is enabled in the Tor Circuit inspector.
        </p>
      </div>
    `;
  }

  document.getElementById("modal-close-btn")?.addEventListener("click", () => {
    fsBrowser.modalKind = null;
    renderFsBrowser();
  });
}

function applyFindHighlights() {
  const content = document.getElementById("fs-page-content");
  const countEl = document.getElementById("fs-find-count");
  if (!content || !countEl) return;

  // Clear previous highlights cleanly
  content.querySelectorAll("mark.trap-find-hit").forEach((m) => {
    const parent = m.parentNode;
    if (parent) {
      parent.replaceChild(document.createTextNode(m.textContent || ""), m);
      parent.normalize();
    }
  });

  const q = fsBrowser.findQuery.trim();
  if (!q) {
    fsBrowser.findTotal = 0;
    countEl.textContent = "0/0";
    return;
  }

  const walker = document.createTreeWalker(content, NodeFilter.SHOW_TEXT);
  const textNodes = [];
  let node;
  while ((node = walker.nextNode())) {
    const tag = node.parentElement ? node.parentElement.tagName : "";
    if (tag !== "TEXTAREA" && tag !== "INPUT" && tag !== "SCRIPT" && node.nodeValue && node.nodeValue.trim()) {
      textNodes.push(node);
    }
  }

  const lowerQ = q.toLowerCase();
  const hits = [];

  for (const tNode of textNodes) {
    const text = tNode.nodeValue || "";
    const lower = text.toLowerCase();
    let idx = lower.indexOf(lowerQ);
    if (idx === -1) continue;

    const frag = document.createDocumentFragment();
    let last = 0;
    while (idx !== -1) {
      if (idx > last) {
        frag.appendChild(document.createTextNode(text.slice(last, idx)));
      }
      const mark = document.createElement("mark");
      mark.className = "trap-find-hit";
      mark.textContent = text.slice(idx, idx + q.length);
      frag.appendChild(mark);
      hits.push(mark);
      last = idx + q.length;
      idx = lower.indexOf(lowerQ, last);
    }
    if (last < text.length) {
      frag.appendChild(document.createTextNode(text.slice(last)));
    }
    tNode.parentNode?.replaceChild(frag, tNode);
  }

  fsBrowser.findTotal = hits.length;
  if (hits.length > 0) {
    fsBrowser.findIndex = ((fsBrowser.findIndex % hits.length) + hits.length) % hits.length;
    const activeMark = hits[fsBrowser.findIndex];
    activeMark.classList.add("is-current");
    activeMark.scrollIntoView({ behavior: "smooth", block: "center" });
    countEl.textContent = `${fsBrowser.findIndex + 1}/${hits.length}`;
  } else {
    countEl.textContent = "0/0";
  }
}

function renderFsChrome() {
  const tab = currentTab();
  const url = currentUrl();
  const isStart = url === "about:blank";

  const podsLayer = document.getElementById("fs-pods-layer");
  const loadPulse = document.getElementById("fs-load-pulse");
  const backPod = document.getElementById("fs-back-pod");
  const torOrb = document.getElementById("fs-tor-orb");
  const crtReadout = document.getElementById("fs-crt-readout");
  const pgpPod = document.getElementById("fs-pgp-pod");
  const crtOverlay = document.getElementById("fs-crt-overlay");
  const findPod = document.getElementById("fs-find-pod");

  const hideFloatingPods = fsBrowser.crtOpen || fsBrowser.findOpen;
  if (podsLayer) {
    podsLayer.hidden = hideFloatingPods;
    podsLayer.classList.toggle("is-retracted", fsBrowser.podsRetracted && !hideFloatingPods);
  }

  if (loadPulse) {
    loadPulse.classList.toggle("is-loading", fsBrowser.isLoading);
  }

  if (backPod) {
    backPod.classList.toggle("can-go-back", tab.historyIndex > 0 || !isStart);
  }

  if (torOrb) {
    torOrb.classList.toggle("is-bootstrapping", !fsBrowser.torReady);
  }

  if (crtReadout) {
    crtReadout.textContent = extractShortOnion(url);
  }

  if (pgpPod) {
    pgpPod.hidden = fsBrowser.remoteOpen;
    pgpPod.classList.toggle("has-pgp", pageHasPgp(url));
  }

  const remotePod = document.getElementById("fs-remote-pod");
  if (remotePod) {
    remotePod.hidden = fsBrowser.remoteOpen;
  }

  if (crtOverlay) {
    crtOverlay.hidden = !fsBrowser.crtOpen;
  }

  if (findPod) {
    findPod.hidden = !fsBrowser.findOpen;
  }

  renderRemoteLeds();
}

function renderFsBrowser() {
  const url = currentUrl();
  const isStart = url === "about:blank";
  const tab = currentTab();

  const startSurface = document.getElementById("fs-start-surface");
  const pageScroll = document.getElementById("fs-page-scroll");
  const pageZoom = document.getElementById("fs-page-zoom");

  if (startSurface) startSurface.hidden = !isStart;
  if (pageScroll) pageScroll.hidden = isStart;
  if (pageZoom) {
    const scale = tab.zoomPercent / 100;
    pageZoom.style.transform = `scale(${scale})`;
    pageZoom.style.width = `${(100 / scale).toFixed(2)}%`;
  }

  if (!isStart) {
    renderHiddenServiceDocument(url);
  }

  renderFsChrome();
  renderRemoteDrawer();
  renderModalSheet();
}

function bindFullScreenBrowserInteractions() {
  renderDirectorySheet();

  // 1. Back / Forward Pewter Switch (tap = back, hold 400ms = forward)
  const backPod = document.getElementById("fs-back-pod");
  if (backPod) {
    let holdTimer = null;
    let held = false;
    backPod.addEventListener("pointerdown", () => {
      held = false;
      holdTimer = window.setTimeout(() => {
        held = true;
        goForwardInTab();
      }, 400);
    });
    const clearBackHold = () => {
      if (holdTimer) {
        clearTimeout(holdTimer);
        holdTimer = null;
      }
    };
    backPod.addEventListener("pointerup", clearBackHold);
    backPod.addEventListener("pointercancel", clearBackHold);
    backPod.addEventListener("click", () => {
      if (!held) goBackInTab();
    });
  }

  // 2. Tor Knurled Orb (tap = Tor Circuit remote, hold 400ms = rotate identity)
  const torOrb = document.getElementById("fs-tor-orb");
  if (torOrb) {
    let holdTimer = null;
    let held = false;
    torOrb.addEventListener("pointerdown", () => {
      held = false;
      holdTimer = window.setTimeout(() => {
        held = true;
        rotateTorCircuit();
      }, 400);
    });
    const clearOrbHold = () => {
      if (holdTimer) {
        clearTimeout(holdTimer);
        holdTimer = null;
      }
    };
    torOrb.addEventListener("pointerup", clearOrbHold);
    torOrb.addEventListener("pointercancel", clearOrbHold);
    torOrb.addEventListener("click", () => {
      if (held) return;
      if (fsBrowser.podsRetracted) {
        fsBrowser.podsRetracted = false;
        renderFsChrome();
        return;
      }
      if (fsBrowser.remoteOpen && fsBrowser.remoteMode === "circuit") {
        fsBrowser.remoteOpen = false;
      } else {
        fsBrowser.remoteMode = "circuit";
        fsBrowser.remoteOpen = true;
      }
      renderFsBrowser();
    });
  }

  // 3. Pewter CRT TV (tap = directory + input, swipe X = switch tab, pull up = tab switcher)
  const crtPod = document.getElementById("fs-crt-pod");
  if (crtPod) {
    let startX = 0;
    let startY = 0;
    let tracking = false;
    let gestureHandled = false;

    crtPod.addEventListener("pointerdown", (e) => {
      startX = e.clientX;
      startY = e.clientY;
      tracking = true;
      gestureHandled = false;
      try {
        crtPod.setPointerCapture?.(e.pointerId);
      } catch (_) {}
    });

    crtPod.addEventListener("pointermove", (e) => {
      if (!tracking || gestureHandled) return;
      const dx = e.clientX - startX;
      const dy = e.clientY - startY;
      if (Math.abs(dx) >= 36 && Math.abs(dx) > Math.abs(dy)) {
        gestureHandled = true;
        if (fsBrowser.tabs.length > 1) {
          const delta = dx < 0 ? 1 : -1;
          fsBrowser.activeTabIndex =
            (fsBrowser.activeTabIndex + delta + fsBrowser.tabs.length) % fsBrowser.tabs.length;
          renderFsBrowser();
        }
      } else if (dy <= -28 && Math.abs(dy) > Math.abs(dx)) {
        gestureHandled = true;
        fsBrowser.podsRetracted = false;
        fsBrowser.modalKind = "tabs";
        renderFsBrowser();
      }
    });

    crtPod.addEventListener("pointerup", () => {
      if (!tracking) return;
      tracking = false;
      if (!gestureHandled) {
        if (fsBrowser.podsRetracted) {
          fsBrowser.podsRetracted = false;
          renderFsChrome();
          return;
        }
        fsBrowser.crtOpen = true;
        fsBrowser.remoteOpen = false;
        const input = document.getElementById("fs-crt-input");
        if (input) {
          input.value = currentUrl() === "about:blank" ? "" : currentUrl();
        }
        renderFsBrowser();
        input?.focus();
        input?.select();
      }
    });

    crtPod.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        fsBrowser.crtOpen = true;
        renderFsBrowser();
        document.getElementById("fs-crt-input")?.focus();
      }
    });
  }

  // CRT Form & Scrim
  document.getElementById("fs-crt-scrim")?.addEventListener("click", () => {
    fsBrowser.crtOpen = false;
    renderFsChrome();
  });
  document.getElementById("fs-crt-close-btn")?.addEventListener("click", () => {
    fsBrowser.crtOpen = false;
    renderFsChrome();
  });
  document.getElementById("fs-crt-form")?.addEventListener("submit", (e) => {
    e.preventDefault();
    const val = document.getElementById("fs-crt-input")?.value || "";
    if (val.trim()) {
      navigateTo(val, true);
    }
  });

  // 4. Red OpenPGP Switchblade (1 click = New Tab, 2 clicks = Keyring, drag up = Quick Decrypt)
  const pgpPod = document.getElementById("fs-pgp-pod");
  const pgpSprite = document.getElementById("fs-pgp-sprite");
  if (pgpPod && pgpSprite) {
    let startY = 0;
    let dragging = false;
    let latchFired = false;
    let clickTimer = null;

    pgpPod.addEventListener("pointerdown", (e) => {
      startY = e.clientY;
      dragging = true;
      latchFired = false;
      try {
        pgpPod.setPointerCapture?.(e.pointerId);
      } catch (_) {}
    });

    pgpPod.addEventListener("pointermove", (e) => {
      if (!dragging) return;
      const dy = e.clientY - startY;
      if (dy < 0) {
        const lift = Math.max(-26, dy);
        pgpSprite.style.transform = `translateY(${lift}px)`;
        if (dy <= -18 && !latchFired) {
          latchFired = true;
          quickVerifyOrDecryptPage();
        }
      }
    });

    const releaseLatch = () => {
      if (!dragging) return;
      dragging = false;
      pgpSprite.style.transform = "translateY(0)";
    };

    pgpPod.addEventListener("pointerup", () => {
      const wasLatch = latchFired;
      releaseLatch();
      if (wasLatch) return;

      if (fsBrowser.podsRetracted) {
        fsBrowser.podsRetracted = false;
        renderFsChrome();
        return;
      }

      if (clickTimer) {
        clearTimeout(clickTimer);
        clickTimer = null;
        fsBrowser.modalKind = "keyring";
        renderFsBrowser();
      } else {
        clickTimer = window.setTimeout(() => {
          clickTimer = null;
          openNewTab(false);
        }, 250);
      }
    });

    pgpPod.addEventListener("pointercancel", releaseLatch);
    pgpPod.addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        e.preventDefault();
        openNewTab(false);
      }
    });
  }

  // 5. ZK "TOR" Side Remote
  document.getElementById("fs-remote-pod")?.addEventListener("click", () => {
    if (fsBrowser.podsRetracted) {
      fsBrowser.podsRetracted = false;
      renderFsChrome();
      return;
    }
    fsBrowser.remoteMode = "menu";
    fsBrowser.remoteOpen = true;
    renderFsBrowser();
  });
  document.getElementById("fs-drawer-close")?.addEventListener("click", () => {
    fsBrowser.remoteOpen = false;
    renderFsBrowser();
  });

  // Modal scrim
  document.getElementById("fs-modal-scrim")?.addEventListener("click", () => {
    fsBrowser.modalKind = null;
    renderFsBrowser();
  });

  // Find in Page controls
  const findInput = document.getElementById("fs-find-input");
  if (findInput) {
    findInput.addEventListener("input", () => {
      fsBrowser.findQuery = findInput.value;
      fsBrowser.findIndex = 0;
      applyFindHighlights();
    });
  }
  document.getElementById("fs-find-next")?.addEventListener("click", () => {
    fsBrowser.findIndex += 1;
    applyFindHighlights();
  });
  document.getElementById("fs-find-prev")?.addEventListener("click", () => {
    fsBrowser.findIndex -= 1;
    applyFindHighlights();
  });
  document.getElementById("fs-find-close")?.addEventListener("click", () => {
    fsBrowser.findOpen = false;
    fsBrowser.findQuery = "";
    renderFsBrowser();
  });

  // 110ms Mechanical Scroll Retraction on active hidden-service scroll
  const pageScroll = document.getElementById("fs-page-scroll");
  if (pageScroll) {
    let lastTop = 0;
    pageScroll.addEventListener(
      "scroll",
      () => {
        const top = pageScroll.scrollTop;
        const delta = top - lastTop;
        lastTop = top;
        if (delta > 10 && top > 24 && !fsBrowser.podsRetracted) {
          fsBrowser.podsRetracted = true;
          fsBrowser.remoteOpen = false;
          renderFsChrome();
          renderRemoteDrawer();
        } else if ((delta < -8 || top <= 8) && fsBrowser.podsRetracted) {
          fsBrowser.podsRetracted = false;
          renderFsChrome();
        }
      },
      { passive: true }
    );
  }

  // Escape closes active overlays
  window.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      let changed = false;
      if (showcaseState.lockedId || showcaseState.activePreviewId !== "resting") {
        showcaseState.lockedId = null;
        showcaseState.activePreviewId = "resting";
        syncShowcaseVisuals();
      }
      if (fsBrowser.modalKind) {
        fsBrowser.modalKind = null;
        changed = true;
      } else if (fsBrowser.crtOpen) {
        fsBrowser.crtOpen = false;
        changed = true;
      } else if (fsBrowser.remoteOpen) {
        fsBrowser.remoteOpen = false;
        changed = true;
      } else if (fsBrowser.findOpen) {
        fsBrowser.findOpen = false;
        changed = true;
      }
      if (changed) renderFsBrowser();
    }
  });
}

function escapeHtml(raw) {
  return String(raw ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

async function init() {
  await loadSyncManifest();
  renderPhoneShowcase();
  syncShowcaseVisuals();

  bindFullScreenBrowserInteractions();
  renderFsBrowser();

  const imgEl = document.getElementById("preview-image");
  if (imgEl) {
    imgEl.addEventListener("load", () => {
      window.requestAnimationFrame(drawLeaderLines);
    });
  }

  window.addEventListener("resize", () => {
    window.requestAnimationFrame(drawLeaderLines);
  });

  window.setTimeout(drawLeaderLines, 120);
}

document.addEventListener("DOMContentLoaded", init);
