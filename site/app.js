/* ==========================================================================
   Trap — Synced Compose @Preview Showcase & Full-Screen Interactive Browser
   ========================================================================== */

const SVG_NS = "http://www.w3.org/2000/svg";

/* --------------------------------------------------------------------------
   STAGE 1: Clean High-Contrast Prose Callouts + Encircling Contours +
            Animated Jetpack Compose @Preview Expansion
   -------------------------------------------------------------------------- */

const CALLOUT_PROSE = {
  "start-zine-viewport": {
    heading: "Full-Bleed Zero-JavaScript Viewport",
    copy:
      "Draws edge-to-edge onto the top status bar with no top bar, bottom bar, or snackbars. Click to open the live Compose @Preview of an active .onion page with zero-JS native OpenPGP field actions."
  },
  "perimeter-steel-rails": {
    heading: "Perimeter Brushed-Steel Rails",
    copy:
      "Frames both vertical edges with 12dp brushed-steel bars and street overspray at 100% touch passthrough. Click to open the Compose @Preview of the acid-yellow load pulse and active swap HUD."
  },
  "kpt-back-pod": {
    heading: "Cast-Pewter Back & Forward Switch",
    copy:
      "Chamfered non-round pewter keycap above the Tor orb: tap steps back, hold steps forward. Click to open the Compose @Preview of Trap's mid-swipe Predictive Back snapshot transition."
  },
  "tor-orb-pod": {
    heading: "Knurled Tor Circuit & Identity Orb",
    copy:
      "Embedded Rust Arti indicator shifting from amber bootstrap to ultraviolet once local SOCKS5h is ready; hold to rotate identity. Click to open the Compose @Preview of the Tor Circuit inspector."
  },
  "pgp-rocker-pod": {
    heading: "Translucent Red OpenPGP Switchblade",
    copy:
      "Glows when native DOM inspection spots OpenPGP fields or armor: 1 click opens a new tab, 2 clicks open the keyring, upward pull decrypts. Click to open the Sequoia Keyring Compose @Preview."
  },
  "crt-address-pod": {
    heading: "Pewter CRT Address & Directory Monitor",
    copy:
      "Shows a tail-preserving green phosphor .onion readout; swipe sideways to switch tabs or pull up for the tab deck. Click to open the expanded CRT bar and 11-site .onion directory Compose @Preview."
  },
  "side-remote-pod": {
    heading: "2×5 LED Hardware Side Remote",
    copy:
      "Ten recessed LEDs reflect live Tor, circuit isolation, keyring, and zero-JS state. Click to animate open the upright Hardware Remote Compose @Preview with Monero Wallet and Swap BTC → XMR."
  },
  "monero-wallet": {
    heading: "Monero Tor Light Wallet & HTTP 402",
    copy:
      "Pure-Rust ov-xmr Keystore wallet synced over .onion monerod RPC with per-site 8… subaddresses and HTTP 402 XMR402 proofs. Click to open the Monero Light Wallet Compose @Preview."
  },
  "btc-xmr-swap": {
    heading: "Native BTC → XMR Zero-JS Tor Swap",
    copy:
      "Fetches live no-KYC .onion quotes over Arti Tor, mints an unlinkable 8… payout subaddress per swap, and tracks BTC deposits via QR and browser HUD. Click to open the Swap Compose @Preview."
  }
};

const FALLBACK_MANIFEST = {
  schemaVersion: 2,
  appVersion: "0.1.46",
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
    { id: "zerojs-page", previewFn: "PgpActionChipsPreview", image: "generated/assets/state-zerojs-page.webp" },
    { id: "steel-rails-load", previewFn: "SteelRailsLoadPulsePreview", image: "generated/assets/state-steel-rails-load.webp" },
    { id: "predictive-back", previewFn: "PredictiveBackPreview", image: "generated/assets/state-predictive-back.webp" },
    { id: "tor-circuit", previewFn: "TorBootstrapPreview", image: "generated/assets/state-tor-circuit.webp" },
    { id: "pgp-keyring", previewFn: "NeonGraffitiKeyringPreview", image: "generated/assets/state-pgp-keyring.webp" },
    { id: "crt-directory", previewFn: "CrtOnionDirectoryPreview", image: "generated/assets/state-crt-directory.webp" },
    { id: "side-remote", previewFn: "BrowserMenuPreview", image: "generated/assets/state-side-remote.webp" },
    { id: "monero-wallet", previewFn: "MoneroWalletHomescreenPreview", image: "generated/assets/state-monero-wallet.webp" },
    { id: "btc-xmr-swap", previewFn: "BtcXmrSwapHomescreenPreview", image: "generated/assets/state-btc-xmr-swap.webp" }
  ],
  components: [
    {
      id: "start-zine-viewport",
      index: "01",
      side: "left",
      statePreviewId: "zerojs-page",
      contourDp: { shape: "roundrect", cx: 206, cy: 272, width: 384, height: 520, rx: 14, rotationDeg: 0 },
      expandedContourDp: { shape: "roundrect", cx: 206, cy: 320, width: 384, height: 612, rx: 16, rotationDeg: 0 },
      anchorNorm: { x: 0.034, y: 0.2131 },
      expandedAnchorNorm: { x: 0.034, y: 0.3497 }
    },
    {
      id: "perimeter-steel-rails",
      index: "02",
      side: "left",
      statePreviewId: "steel-rails-load",
      contourDp: { shape: "dual-rails", cx: 206, cy: 457.5, width: 412, height: 915, rx: 4, rotationDeg: 0 },
      expandedContourDp: {
        shape: "dual-rails",
        cx: 206,
        cy: 457.5,
        width: 412,
        height: 915,
        rx: 4,
        rotationDeg: 0,
        subFocus: { cx: 206, cy: 481, r: 34 }
      },
      anchorNorm: { x: 0.0291, y: 0.4317 },
      expandedAnchorNorm: { x: 0.0194, y: 0.5 }
    },
    {
      id: "kpt-back-pod",
      index: "03",
      side: "left",
      statePreviewId: "predictive-back",
      contourDp: { shape: "roundrect", cx: 53, cy: 727, width: 62, height: 40, rx: 8, rotationDeg: 0 },
      expandedContourDp: { shape: "roundrect", cx: 206, cy: 320, width: 384, height: 612, rx: 16, rotationDeg: 0 },
      anchorNorm: { x: 0.0534, y: 0.7945 },
      expandedAnchorNorm: { x: 0.034, y: 0.3497 }
    },
    {
      id: "tor-orb-pod",
      index: "04",
      side: "left",
      statePreviewId: "tor-circuit",
      contourDp: { shape: "circle", cx: 60, cy: 811, width: 104, height: 104, rx: 52, rotationDeg: 0 },
      expandedContourDp: { shape: "roundrect", cx: 268, cy: 753, width: 268, height: 288, rx: 24, rotationDeg: 0 },
      anchorNorm: { x: 0.0194, y: 0.8863 },
      expandedAnchorNorm: { x: 0.3252, y: 0.823 }
    },
    {
      id: "pgp-rocker-pod",
      index: "05",
      side: "right",
      statePreviewId: "pgp-keyring",
      contourDp: { shape: "roundrect", cx: 355, cy: 629, width: 82, height: 124, rx: 15, rotationDeg: 20 },
      expandedContourDp: { shape: "roundrect", cx: 206, cy: 457.5, width: 400, height: 903, rx: 22, rotationDeg: 0 },
      anchorNorm: { x: 0.9552, y: 0.7028 },
      expandedAnchorNorm: { x: 0.9806, y: 0.5 }
    },
    {
      id: "crt-address-pod",
      index: "06",
      side: "right",
      statePreviewId: "crt-directory",
      contourDp: { shape: "roundrect", cx: 202, cy: 715.5, width: 156, height: 135, rx: 26, rotationDeg: 0 },
      expandedContourDp: { shape: "roundrect", cx: 206.25, cy: 459.5, width: 391.5, height: 895, rx: 18, rotationDeg: 0 },
      anchorNorm: { x: 0.6796, y: 0.782 },
      expandedAnchorNorm: { x: 0.9757, y: 0.5022 }
    },
    {
      id: "side-remote-pod",
      index: "07",
      side: "right",
      statePreviewId: "side-remote",
      contourDp: { shape: "pill", cx: 360, cy: 804, width: 60, height: 186, rx: 30, rotationDeg: 20 },
      expandedContourDp: { shape: "roundrect", cx: 268, cy: 587, width: 268, height: 620, rx: 24, rotationDeg: 0 },
      anchorNorm: { x: 0.9787, y: 0.8447 },
      expandedAnchorNorm: { x: 0.9757, y: 0.6103 }
    },
    {
      id: "monero-wallet",
      index: "08",
      side: "right",
      statePreviewId: "monero-wallet",
      contourDp: {
        shape: "pill",
        cx: 360,
        cy: 804,
        width: 60,
        height: 186,
        rx: 30,
        rotationDeg: 20,
        subFocus: { cx: 372.31, cy: 770.17, r: 19 }
      },
      expandedContourDp: { shape: "roundrect", cx: 206, cy: 457.5, width: 400, height: 903, rx: 22, rotationDeg: 0 },
      anchorNorm: { x: 0.9422, y: 0.8899 },
      expandedAnchorNorm: { x: 0.9806, y: 0.5 }
    },
    {
      id: "btc-xmr-swap",
      index: "09",
      side: "right",
      statePreviewId: "btc-xmr-swap",
      contourDp: {
        shape: "pill",
        cx: 360,
        cy: 804,
        width: 60,
        height: 186,
        rx: 30,
        rotationDeg: 20,
        subFocus: { cx: 344.95, cy: 845.35, r: 21 }
      },
      expandedContourDp: { shape: "roundrect", cx: 206, cy: 457.5, width: 400, height: 903, rx: 22, rotationDeg: 0 },
      anchorNorm: { x: 0.904, y: 0.9371 },
      expandedAnchorNorm: { x: 0.9806, y: 0.5327 }
    }
  ]
};

const showcaseState = {
  manifest: FALLBACK_MANIFEST,
  hoveredId: null,
  lockedId: null,
  animRaf: null,
  // Interpolated morph state for smooth 60fps contour & leader animation
  morphProgress: 0, // 0 = resting contour, 1 = expanded @Preview contour
  morphComponentId: null,
  fromContour: null,
  toContour: null,
  fromAnchor: null,
  toAnchor: null,
  currentContour: null,
  currentAnchors: {}
};

async function loadSyncManifest() {
  try {
    const res = await fetch("./generated/homescreen-sync.json", { cache: "no-cache" });
    if (res.ok) {
      const json = await res.json();
      if (json && Array.isArray(json.components) && json.components.length > 0 && json.components[0].contourDp) {
        showcaseState.manifest = json;
      }
    }
  } catch (_) {
    // Fallback manifest used when opened directly via file://
  }
}

function preloadStateImages() {
  for (const st of showcaseState.manifest.states) {
    const img = new Image();
    img.src = `./${st.image}`;
  }
}

function getOrderedComponents() {
  const comps = [...showcaseState.manifest.components];
  const left = comps.filter((c) => c.side === "left").sort((a, b) => a.anchorNorm.y - b.anchorNorm.y);
  const right = comps.filter((c) => c.side !== "left").sort((a, b) => a.anchorNorm.y - b.anchorNorm.y);
  return [...left, ...right];
}

function getComponentById(id) {
  return showcaseState.manifest.components.find((c) => c.id === id) || null;
}

function getActiveHighlightId() {
  return showcaseState.hoveredId || showcaseState.lockedId;
}

function lerp(a, b, t) {
  return a + (b - a) * t;
}

function easeOutExpoSnap(t) {
  return 1 - Math.pow(1 - t, 3.2);
}

function interpolateContour(c1, c2, t) {
  if (!c1) return c2;
  if (!c2) return c1;
  const shape = t > 0.5 ? c2.shape : c1.shape;
  return {
    shape,
    cx: lerp(c1.cx, c2.cx, t),
    cy: lerp(c1.cy, c2.cy, t),
    width: lerp(c1.width, c2.width, t),
    height: lerp(c1.height, c2.height, t),
    rx: lerp(c1.rx, c2.rx, t),
    rotationDeg: lerp(c1.rotationDeg || 0, c2.rotationDeg || 0, t),
    subFocus: t < 0.35 && c1.subFocus ? c1.subFocus : t > 0.65 && c2.subFocus ? c2.subFocus : null
  };
}

function startPreviewTransition(nextLockedId) {
  const prevLockedId = showcaseState.lockedId;
  showcaseState.lockedId = nextLockedId;

  const viewportEl = document.getElementById("phone-viewport");
  const expImgEl = document.getElementById("preview-expanded-image");
  const rootW = showcaseState.manifest.viewport?.widthDp || 412;
  const rootH = showcaseState.manifest.viewport?.heightDp || 915;

  if (showcaseState.animRaf) {
    cancelAnimationFrame(showcaseState.animRaf);
    showcaseState.animRaf = null;
  }

  if (nextLockedId) {
    const comp = getComponentById(nextLockedId);
    if (!comp) return;

    const stateObj = showcaseState.manifest.states.find((s) => s.id === comp.statePreviewId);
    const originX = ((comp.contourDp.cx / rootW) * 100).toFixed(1);
    const originY = ((comp.contourDp.cy / rootH) * 100).toFixed(1);

    if (expImgEl && stateObj) {
      const nextSrc = `./${stateObj.image}`;
      // Re-trigger bloom animation cleanly even when switching directly between two callouts
      expImgEl.classList.remove("is-open");
      expImgEl.style.transformOrigin = `${originX}% ${originY}%`;
      if (expImgEl.getAttribute("src") !== nextSrc) {
        expImgEl.setAttribute("src", nextSrc);
      }
      // Force style recalc before applying .is-open so CSS transition animates smoothly
      void expImgEl.offsetWidth;
      expImgEl.classList.add("is-open");
    }
    viewportEl?.classList.add("has-expanded");

    const startContour =
      prevLockedId && showcaseState.currentContour ? { ...showcaseState.currentContour } : { ...comp.contourDp };
    const endContour = { ...(comp.expandedContourDp || comp.contourDp) };
    const startAnchor = showcaseState.currentAnchors[comp.id] || { ...comp.anchorNorm };
    const endAnchor = { ...(comp.expandedAnchorNorm || comp.anchorNorm) };

    animateContourMorph(comp.id, startContour, endContour, startAnchor, endAnchor, 330);
  } else {
    // Animate closed back into the resting pod!
    const closingComp = prevLockedId ? getComponentById(prevLockedId) : null;
    if (expImgEl) {
      if (closingComp) {
        const originX = ((closingComp.contourDp.cx / rootW) * 100).toFixed(1);
        const originY = ((closingComp.contourDp.cy / rootH) * 100).toFixed(1);
        expImgEl.style.transformOrigin = `${originX}% ${originY}%`;
      }
      expImgEl.classList.remove("is-open");
    }
    viewportEl?.classList.remove("has-expanded");

    if (closingComp) {
      const startContour = showcaseState.currentContour || { ...(closingComp.expandedContourDp || closingComp.contourDp) };
      const endContour = { ...closingComp.contourDp };
      const startAnchor = showcaseState.currentAnchors[closingComp.id] || {
        ...(closingComp.expandedAnchorNorm || closingComp.anchorNorm)
      };
      const endAnchor = { ...closingComp.anchorNorm };
      animateContourMorph(closingComp.id, startContour, endContour, startAnchor, endAnchor, 290, () => {
        if (!showcaseState.lockedId) {
          showcaseState.morphComponentId = null;
          showcaseState.currentContour = null;
          syncShowcaseVisuals();
        }
      });
    } else {
      showcaseState.morphComponentId = null;
      showcaseState.currentContour = null;
      syncShowcaseVisuals();
    }
  }
}

function animateContourMorph(compId, fromContour, toContour, fromAnchor, toAnchor, durationMs, onComplete) {
  showcaseState.morphComponentId = compId;
  const startTime = performance.now();

  const step = (now) => {
    const rawT = Math.min(1, (now - startTime) / durationMs);
    const t = easeOutExpoSnap(rawT);
    showcaseState.currentContour = interpolateContour(fromContour, toContour, t);
    showcaseState.currentAnchors[compId] = {
      x: lerp(fromAnchor.x, toAnchor.x, t),
      y: lerp(fromAnchor.y, toAnchor.y, t)
    };
    syncShowcaseVisuals();

    if (rawT < 1) {
      showcaseState.animRaf = requestAnimationFrame(step);
    } else {
      showcaseState.animRaf = null;
      if (onComplete) onComplete();
    }
  };

  showcaseState.animRaf = requestAnimationFrame(step);
}

function appendContourSvgElements(parentGroup, contour, className) {
  const { shape, cx, cy, width, height, rx, rotationDeg } = contour;
  const rotTransform =
    rotationDeg && Math.abs(rotationDeg) > 0.05
      ? `rotate(${rotationDeg.toFixed(2)} ${cx.toFixed(2)} ${cy.toFixed(2)})`
      : null;

  if (shape === "dual-rails") {
    const leftRail = document.createElementNS(SVG_NS, "rect");
    leftRail.setAttribute("class", className);
    leftRail.setAttribute("x", "1.5");
    leftRail.setAttribute("y", "2");
    leftRail.setAttribute("width", "11");
    leftRail.setAttribute("height", String((height - 4).toFixed(1)));
    leftRail.setAttribute("rx", "4");
    parentGroup.appendChild(leftRail);

    const rightRail = document.createElementNS(SVG_NS, "rect");
    rightRail.setAttribute("class", className);
    rightRail.setAttribute("x", String((width - 12.5).toFixed(1)));
    rightRail.setAttribute("y", "2");
    rightRail.setAttribute("width", "11");
    rightRail.setAttribute("height", String((height - 4).toFixed(1)));
    rightRail.setAttribute("rx", "4");
    parentGroup.appendChild(rightRail);
    return;
  }

  if (shape === "circle" && Math.abs(width - height) < 2) {
    const circle = document.createElementNS(SVG_NS, "circle");
    circle.setAttribute("class", className);
    circle.setAttribute("cx", cx.toFixed(2));
    circle.setAttribute("cy", cy.toFixed(2));
    circle.setAttribute("r", (width * 0.5).toFixed(2));
    parentGroup.appendChild(circle);
    return;
  }

  const rect = document.createElementNS(SVG_NS, "rect");
  rect.setAttribute("class", className);
  rect.setAttribute("x", (cx - width * 0.5).toFixed(2));
  rect.setAttribute("y", (cy - height * 0.5).toFixed(2));
  rect.setAttribute("width", width.toFixed(2));
  rect.setAttribute("height", height.toFixed(2));
  rect.setAttribute("rx", Math.min(rx, width * 0.5, height * 0.5).toFixed(2));
  if (rotTransform) {
    rect.setAttribute("transform", rotTransform);
  }
  parentGroup.appendChild(rect);
}

function renderPhoneShowcase() {
  const leftCol = document.getElementById("callouts-left");
  const rightCol = document.getElementById("callouts-right");
  const hitLayer = document.getElementById("contour-hit-layer");
  const svgViewport = document.getElementById("viewport-contour-svg");
  if (!leftCol || !rightCol || !hitLayer || !svgViewport) return;

  const rootW = showcaseState.manifest.viewport?.widthDp || 412;
  const rootH = showcaseState.manifest.viewport?.heightDp || 915;
  svgViewport.setAttribute("viewBox", `0 0 ${rootW} ${rootH}`);

  leftCol.innerHTML = "";
  rightCol.innerHTML = "";
  hitLayer.innerHTML = "";

  const ordered = getOrderedComponents();

  for (const comp of ordered) {
    showcaseState.currentAnchors[comp.id] = { ...comp.anchorNorm };
    const prose = CALLOUT_PROSE[comp.id] || {
      heading: comp.composable || comp.id,
      copy: ""
    };

    const card = document.createElement("article");
    card.className = "callout-card";
    card.id = `callout-${comp.id}`;
    card.tabIndex = 0;
    card.setAttribute("role", "button");
    card.setAttribute("aria-expanded", "false");
    card.innerHTML = `
      <h2 class="callout-heading">${escapeHtml(prose.heading)}</h2>
      <p class="callout-copy">${escapeHtml(prose.copy)}</p>
    `;

    card.addEventListener("mouseenter", () => {
      showcaseState.hoveredId = comp.id;
      syncShowcaseVisuals();
    });

    card.addEventListener("mouseleave", () => {
      showcaseState.hoveredId = null;
      syncShowcaseVisuals();
    });

    const toggleExpand = () => {
      const next = showcaseState.lockedId === comp.id ? null : comp.id;
      startPreviewTransition(next);
    };

    card.addEventListener("click", toggleExpand);
    card.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        toggleExpand();
      }
    });

    if (comp.side === "left") {
      leftCol.appendChild(card);
    } else {
      rightCol.appendChild(card);
    }
  }

  // Render interactive hit-targets on the phone mock (smaller/specific pods on top of full-bleed viewport)
  const hitOrder = [
    "start-zine-viewport",
    "perimeter-steel-rails",
    "crt-address-pod",
    "pgp-rocker-pod",
    "side-remote-pod",
    "kpt-back-pod",
    "tor-orb-pod"
  ];
  for (const hitId of hitOrder) {
    const comp = getComponentById(hitId);
    if (!comp) continue;
    const g = document.createElementNS(SVG_NS, "g");
    g.id = `hit-${comp.id}`;
    appendContourSvgElements(g, comp.contourDp, "contour-hit");
    g.addEventListener("mouseenter", () => {
      if (!showcaseState.lockedId) {
        showcaseState.hoveredId = comp.id;
        syncShowcaseVisuals();
      }
    });
    g.addEventListener("mouseleave", () => {
      if (showcaseState.hoveredId === comp.id) {
        showcaseState.hoveredId = null;
        syncShowcaseVisuals();
      }
    });
    g.addEventListener("click", (e) => {
      e.stopPropagation();
      const next = showcaseState.lockedId === comp.id ? null : comp.id;
      startPreviewTransition(next);
    });
    hitLayer.appendChild(g);
  }

  // Clicking the expanded @Preview phone screen collapses back to resting
  document.getElementById("phone-viewport")?.addEventListener("click", () => {
    if (showcaseState.lockedId) {
      startPreviewTransition(null);
    }
  });

  // Clicking outside cards/phone collapses any open @Preview
  document.getElementById("phone-showcase")?.addEventListener("click", (e) => {
    if (!e.target.closest(".callout-card") && !e.target.closest("#phone-chassis")) {
      if (showcaseState.lockedId) {
        startPreviewTransition(null);
      }
    }
  });
}

function renderViewportContours() {
  const highlightLayer = document.getElementById("contour-highlight-layer");
  const hitLayer = document.getElementById("contour-hit-layer");
  if (!highlightLayer) return;

  highlightLayer.innerHTML = "";
  const activeId = getActiveHighlightId();
  const isExpandedOpen = Boolean(showcaseState.lockedId);

  if (hitLayer) {
    hitLayer.style.display = isExpandedOpen ? "none" : "block";
  }

  // Subtle ambient outlines on the 5 resting hardware pods when idle so contours are immediately legible
  if (!activeId && !showcaseState.morphComponentId) {
    for (const podId of ["kpt-back-pod", "tor-orb-pod", "crt-address-pod", "pgp-rocker-pod", "side-remote-pod"]) {
      const comp = getComponentById(podId);
      if (comp) {
        appendContourSvgElements(highlightLayer, comp.contourDp, "contour-ambient");
      }
    }
    return;
  }

  // Determine which component & contour geometry to draw
  let targetComp = null;
  let contourToDraw = null;
  let isOpenSheet = false;

  if (showcaseState.hoveredId && showcaseState.hoveredId !== showcaseState.lockedId && !isExpandedOpen) {
    targetComp = getComponentById(showcaseState.hoveredId);
    contourToDraw = targetComp?.contourDp;
  } else if (showcaseState.morphComponentId && showcaseState.currentContour) {
    targetComp = getComponentById(showcaseState.morphComponentId);
    contourToDraw = showcaseState.currentContour;
    isOpenSheet = Boolean(showcaseState.lockedId === showcaseState.morphComponentId);
  } else if (activeId) {
    targetComp = getComponentById(activeId);
    contourToDraw =
      showcaseState.lockedId === activeId
        ? targetComp?.expandedContourDp || targetComp?.contourDp
        : targetComp?.contourDp;
    isOpenSheet = showcaseState.lockedId === activeId;
  }

  if (!targetComp || !contourToDraw) return;

  appendContourSvgElements(highlightLayer, contourToDraw, "contour-active-halo");
  appendContourSvgElements(
    highlightLayer,
    contourToDraw,
    isOpenSheet ? "contour-active-stroke contour-active-stroke--open" : "contour-active-stroke"
  );

  if (contourToDraw.subFocus) {
    const sf = contourToDraw.subFocus;
    const haloRing = document.createElementNS(SVG_NS, "circle");
    haloRing.setAttribute("class", "contour-subfocus-halo");
    haloRing.setAttribute("cx", sf.cx.toFixed(2));
    haloRing.setAttribute("cy", sf.cy.toFixed(2));
    haloRing.setAttribute("r", sf.r.toFixed(2));
    highlightLayer.appendChild(haloRing);

    const ring = document.createElementNS(SVG_NS, "circle");
    ring.setAttribute("class", "contour-subfocus-ring");
    ring.setAttribute("cx", sf.cx.toFixed(2));
    ring.setAttribute("cy", sf.cy.toFixed(2));
    ring.setAttribute("r", sf.r.toFixed(2));
    highlightLayer.appendChild(ring);
  }
}

function drawLeaderLines() {
  const svg = document.getElementById("leader-svg");
  const stage = document.getElementById("diagram-stage");
  const viewport = document.getElementById("phone-viewport");
  if (!svg || !stage || !viewport) return;

  if (window.innerWidth <= 1120) {
    svg.innerHTML = "";
    return;
  }

  const stageRect = stage.getBoundingClientRect();
  const vpRect = viewport.getBoundingClientRect();
  if (stageRect.width <= 0 || vpRect.width <= 0) return;

  svg.setAttribute("viewBox", `0 0 ${stageRect.width} ${stageRect.height}`);
  const focusedId = getActiveHighlightId();
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
    const laneFrac = laneCount === 1 ? 0.5 : 0.22 + (laneIdx / (laneCount - 1)) * 0.56;

    const startX = (isLeft ? cardRect.right : cardRect.left) - stageRect.left;
    const startY = cardRect.top + cardRect.height * 0.5 - stageRect.top;

    // Use live-interpolated anchor when this component is animating open/closed
    const activeAnchor =
      showcaseState.morphComponentId === comp.id && showcaseState.currentAnchors[comp.id]
        ? showcaseState.currentAnchors[comp.id]
        : showcaseState.lockedId === comp.id
          ? comp.expandedAnchorNorm || comp.anchorNorm
          : comp.anchorNorm;

    const targetX = vpRect.left - stageRect.left + activeAnchor.x * vpRect.width;
    const targetY = vpRect.top - stageRect.top + activeAnchor.y * vpRect.height;

    const gutterWidth = isLeft
      ? vpRect.left - stageRect.left - startX
      : startX - (vpRect.right - stageRect.left);
    const gutterBendX = isLeft
      ? startX + Math.max(12, gutterWidth * laneFrac)
      : startX - Math.max(12, gutterWidth * (1 - laneFrac));

    const d = `M ${startX.toFixed(1)} ${startY.toFixed(1)} L ${gutterBendX.toFixed(1)} ${startY.toFixed(
      1
    )} L ${gutterBendX.toFixed(1)} ${targetY.toFixed(1)} L ${targetX.toFixed(1)} ${targetY.toFixed(1)}`;

    const g = document.createElementNS(SVG_NS, "g");
    const isThisActive = showcaseState.lockedId
      ? showcaseState.lockedId === comp.id
      : focusedId === comp.id;
    let cls = "leader-group";
    if (isThisActive) {
      cls += " is-active";
    } else if (showcaseState.lockedId) {
      cls += " is-hidden-open";
    } else if (focusedId) {
      cls += " is-dimmed";
    }
    g.setAttribute("class", cls);

    const halo = document.createElementNS(SVG_NS, "path");
    halo.setAttribute("class", "leader-halo");
    halo.setAttribute("d", d);
    g.appendChild(halo);

    const line = document.createElementNS(SVG_NS, "path");
    line.setAttribute("class", "leader-line");
    line.setAttribute("d", d);
    g.appendChild(line);

    const port = document.createElementNS(SVG_NS, "circle");
    port.setAttribute("class", "leader-port");
    port.setAttribute("cx", startX.toFixed(1));
    port.setAttribute("cy", startY.toFixed(1));
    port.setAttribute("r", "4");
    g.appendChild(port);

    const rimJoint = document.createElementNS(SVG_NS, "circle");
    rimJoint.setAttribute("class", "leader-rim-joint");
    rimJoint.setAttribute("cx", targetX.toFixed(1));
    rimJoint.setAttribute("cy", targetY.toFixed(1));
    rimJoint.setAttribute("r", isThisActive ? "4.6" : "3.5");
    g.appendChild(rimJoint);

    fragment.appendChild(g);
  }

  svg.innerHTML = "";
  svg.appendChild(fragment);
}

function syncShowcaseVisuals() {
  const focusedId = getActiveHighlightId();

  for (const comp of showcaseState.manifest.components) {
    const card = document.getElementById(`callout-${comp.id}`);
    if (!card) continue;
    const isOpen = showcaseState.lockedId === comp.id;
    const isFocused = focusedId === comp.id || isOpen;
    const isDimmed = Boolean((focusedId || showcaseState.lockedId) && !isFocused);

    card.classList.toggle("is-active", isFocused);
    card.classList.toggle("is-open", isOpen);
    card.classList.toggle("is-dimmed", isDimmed);
    card.setAttribute("aria-expanded", String(isOpen));
  }

  renderViewportContours();
  drawLeaderLines();
}

/* --------------------------------------------------------------------------
   STAGE 2: 100vw x 100vh Edge-to-Edge Interactive Trap Browser
            (Full Monero Light Wallet + Native BTC -> XMR Tor Swap + QR + HUD)
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

const SWAP_PROVIDERS = [
  {
    id: "exch-onion",
    provider: "eXch (.onion)",
    rate: 194.2,
    chip: ".ONION·NO-KYC",
    url: "http://hszyoqwrcp7cxlxnqmovp6vjvmnwj33g4wviuxqzq47emieaxjaperyd.onion/order/EXCH-9F42C"
  },
  {
    id: "majestic-onion",
    provider: "MajesticBank (.onion)",
    rate: 193.65,
    chip: ".ONION·NO-KYC",
    url: "http://majestictfvnfjgo5hqvmuzynak4kjl5tjs3j5zdabawe6n2aaebldad.onion/order/MB-7410A"
  }
];

function renderQrSvg(seedText) {
  const size = 25;
  const quiet = 3;
  const total = size + quiet * 2;
  let hash = 2166136261;
  for (let i = 0; i < seedText.length; i++) {
    hash ^= seedText.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }

  function isFinder(r, c) {
    const check = (tr, tc) => {
      const lr = r - tr;
      const lc = c - tc;
      if (lr < 0 || lr > 6 || lc < 0 || lc > 6) return null;
      return lr === 0 || lr === 6 || lc === 0 || lc === 6 || (lr >= 2 && lr <= 4 && lc >= 2 && lc <= 4);
    };
    return check(0, 0) ?? check(0, size - 7) ?? check(size - 7, 0);
  }

  let rects = `<rect x="0" y="0" width="${total}" height="${total}" rx="2" fill="#4ade80" />`;
  for (let r = 0; r < size; r++) {
    for (let c = 0; c < size; c++) {
      let dark = false;
      const f = isFinder(r, c);
      if (f !== null) {
        dark = f;
      } else if ((r < 8 && c < 8) || (r < 8 && c >= size - 8) || (r >= size - 8 && c < 8)) {
        dark = false;
      } else if (r === 6 || c === 6) {
        dark = (r + c) % 2 === 0;
      } else {
        hash = Math.imul(hash, 1664525) + 1013904223 + r * 31 + c;
        dark = ((hash >>> 16) & 1) === 1;
      }
      if (dark) {
        rects += `<rect x="${c + quiet}" y="${r + quiet}" width="1.05" height="1.05" fill="#070a0e" />`;
      }
    }
  }
  return `<svg viewBox="0 0 ${total} ${total}" aria-hidden="true">${rects}</svg>`;
}

function mintUnlinkableSubaddress() {
  const alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
  let out = "8";
  for (let i = 0; i < 94; i++) {
    out += alphabet[Math.floor(Math.random() * alphabet.length)];
  }
  return out;
}

function createTab(initialUrl = "about:blank", isPrivate = false) {
  return {
    id: `tab-${Math.random().toString(36).slice(2, 8)}`,
    history: [initialUrl],
    historyIndex: 0,
    isPrivate,
    zoomPercent: 100,
    submissionDraft: "Whistleblower report: attach ledger checksums before newsroom handoff.",
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
  xmrBalance: 1.482,
  xmrSyncedHeight: 3845910,
  xmrActiveSubaddress:
    "888tNkZrPN6JsEgekjMnABU4TBzc2Dt29EPAvkRxbANsAnjyPbb3iQ1YBRk1UXcdRsiKc9dhwMVgN5S9cQUiyoogDavup3H",
  swapBtcInput: "0.01",
  trackedSwap: {
    swapId: "swap-exch-904",
    provider: "eXch (.onion)",
    btcAmount: "0.01",
    estimatedXmr: "1.9420",
    btcDepositAddress: "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
    payoutSubaddress:
      "87cTrapSwapSub9pQ4kL2mN8vR3xW6yZ1bD5fG7hJ9kM2pQ4rS6tU8vW0xY2zA4bC6dE8fG0hJ2kL4mN6pQ8rS0tU2vW4xY",
    orderUrl: "http://hszyoqwrcp7cxlxnqmovp6vjvmnwj33g4wviuxqzq47emieaxjaperyd.onion/order/EXCH-9F42C",
    status: "awaiting_btc_deposit",
    hudDismissed: false
  }
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
  }, 300);
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
  }, 820);
}

function launchSwapQuoteInBrowser(providerObj, btcAmountStr) {
  const amt = Math.max(0.0005, parseFloat(btcAmountStr) || 0.01);
  const estXmr = (amt * providerObj.rate).toFixed(4);
  fsBrowser.trackedSwap = {
    swapId: `swap-${Date.now().toString(36)}`,
    provider: providerObj.provider,
    btcAmount: amt.toFixed(4).replace(/0+$/, "").replace(/\.$/, ""),
    estimatedXmr: estXmr,
    btcDepositAddress: "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
    payoutSubaddress: mintUnlinkableSubaddress(),
    orderUrl: providerObj.url,
    status: "awaiting_btc_deposit",
    hudDismissed: false
  };
  fsBrowser.modalKind = null;
  navigateTo(providerObj.url, true);
}

function settleActiveSwapPayout() {
  const sw = fsBrowser.trackedSwap;
  if (!sw) return;
  if (sw.status !== "completed") {
    const payout = parseFloat(sw.estimatedXmr) || 1.942;
    fsBrowser.xmrBalance = Number((fsBrowser.xmrBalance + payout).toFixed(4));
    fsBrowser.xmrSyncedHeight += 2;
    sw.status = "completed";
    sw.receivedXmr = payout.toFixed(4);
  }
  renderFsBrowser();
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

  if (host.includes("hszyoqwrcp7cxlxn") || host.includes("majestictfvnfjgo")) {
    const sw = fsBrowser.trackedSwap || {
      provider: host.includes("majestic") ? "MajesticBank (.onion)" : "eXch (.onion)",
      btcAmount: "0.01",
      estimatedXmr: "1.9420",
      btcDepositAddress: "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
      payoutSubaddress: fsBrowser.xmrActiveSubaddress,
      status: "awaiting_btc_deposit"
    };
    const btcUri = `bitcoin:${sw.btcDepositAddress}?amount=${sw.btcAmount}`;
    container.innerHTML = `
      <div class="hs-doc">
        <h2 class="hs-title">${escapeHtml(sw.provider)} — Zero-JS .onion Swap Order</h2>
        <p class="hs-sub">Auto-bound to fresh Trap unlinkable Monero subaddress <code>${escapeHtml(
          sw.payoutSubaddress.slice(0, 12)
        )}…${escapeHtml(sw.payoutSubaddress.slice(-8))}</code> over isolated Tor circuit.</p>

        <div class="hs-card">
          <div class="fs-offer-head">
            <h3>Send ${escapeHtml(sw.btcAmount)} BTC → Receive ${escapeHtml(sw.estimatedXmr)} XMR</h3>
            <span class="fs-status-chip ${sw.status === "completed" ? "" : "fs-status-chip--amber"}">${
              sw.status === "completed" ? `PAID +${sw.estimatedXmr} XMR` : "AWAITING BTC DEPOSIT"
            }</span>
          </div>
          <div class="fs-qr-wrap">
            <div class="fs-qr-box">${renderQrSvg(btcUri)}</div>
          </div>
          <pre class="fs-mono-block">BTC Deposit: ${escapeHtml(sw.btcDepositAddress)}
XMR Payout:  ${escapeHtml(sw.payoutSubaddress)}</pre>
          <div class="hs-action-row">
            <button type="button" class="fs-well-btn fs-well-btn--go" id="swap-order-settle-btn">${
              sw.status === "completed" ? "Payout Confirmed in Wallet ✓" : "Simulate BTC Deposit & Sync XMR Payout"
            }</button>
            <button type="button" class="fs-well-btn" id="swap-order-open-wallet">Open Monero Wallet</button>
          </div>
        </div>
      </div>
    `;

    document.getElementById("swap-order-settle-btn")?.addEventListener("click", settleActiveSwapPayout);
    document.getElementById("swap-order-open-wallet")?.addEventListener("click", () => {
      fsBrowser.modalKind = "wallet";
      renderFsBrowser();
    });
  } else if (host.includes("duckduckgogg42xj")) {
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
          <button type="button" class="hs-link-btn" data-nav-url="http://xmr402vault7k9qp2m4n8v3c6x1z5l9r2t4y6u8i0o2p4a6s8d0f2gad.onion/">XMR402 Cryptographic Wire Archive (HTTP 402 .onion Gate)</button>
          <p style="margin-top:0.3rem;">Zero-account hidden service archive unlocked via instant Monero 402 subaddress proof.</p>
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
      </div>
    `;

    const textarea = document.getElementById("sd-submission-field");
    if (textarea) {
      textarea.addEventListener("input", () => {
        tab.submissionDraft = textarea.value;
      });
    }
    document.getElementById("sd-encrypt-btn")?.addEventListener("click", encryptSubmissionDraft);
    document.getElementById("sd-decrypt-btn")?.addEventListener("click", quickVerifyOrDecryptPage);
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
              <button type="button" class="fs-well-btn" id="xmr402-swap-btn">Swap BTC → XMR</button>
            </div>
          </div>
        </div>
      `;
      document.getElementById("xmr402-pay-btn")?.addEventListener("click", () => {
        if (fsBrowser.xmrBalance >= 0.015) {
          fsBrowser.xmrBalance = Number((fsBrowser.xmrBalance - 0.015).toFixed(4));
          tab.xmr402Unlocked = true;
          triggerLoadPulse();
        }
      });
      document.getElementById("xmr402-swap-btn")?.addEventListener("click", () => {
        fsBrowser.modalKind = "swap";
        renderFsBrowser();
      });
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
          <h3>Verified Hidden Service Shortcuts</h3>
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

function renderSwapHud() {
  const hud = document.getElementById("fs-swap-hud");
  if (!hud) return;

  const sw = fsBrowser.trackedSwap;
  const isStart = currentUrl() === "about:blank";
  const shouldShow =
    Boolean(sw) && !sw.hudDismissed && !isStart && !fsBrowser.crtOpen && !fsBrowser.modalKind;

  hud.hidden = !shouldShow;
  if (!shouldShow || !sw) return;

  hud.classList.toggle("is-retracted", fsBrowser.podsRetracted);
  const done = sw.status === "completed";
  hud.innerHTML = `
    <div class="fs-swap-hud-top">
      <span>${escapeHtml(sw.provider)} · ${escapeHtml(sw.btcAmount)} BTC</span>
      <span class="fs-status-chip ${done ? "" : "fs-status-chip--amber"}">${
        done ? `PAID +${escapeHtml(sw.estimatedXmr)} XMR` : "DEPOSIT BTC"
      }</span>
    </div>
    <div class="fs-swap-hud-actions">
      <button type="button" class="fs-well-btn fs-well-btn--go" id="hud-sync-payout">${
        done ? "Open Wallet" : "Sync Payout"
      }</button>
      <button type="button" class="fs-well-btn" id="hud-open-swap">Swap Sheet</button>
      <button type="button" class="fs-well-btn" id="hud-dismiss">✕</button>
    </div>
  `;

  document.getElementById("hud-sync-payout")?.addEventListener("click", () => {
    if (done) {
      fsBrowser.modalKind = "wallet";
      renderFsBrowser();
    } else {
      settleActiveSwapPayout();
    }
  });
  document.getElementById("hud-open-swap")?.addEventListener("click", () => {
    fsBrowser.modalKind = "swap";
    renderFsBrowser();
  });
  document.getElementById("hud-dismiss")?.addEventListener("click", () => {
    sw.hudDismissed = true;
    renderSwapHud();
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
  const hasLiveSwap = fsBrowser.trackedSwap && fsBrowser.trackedSwap.status !== "completed";

  const states = [
    fsBrowser.torReady ? "green" : "off",
    !fsBrowser.torReady ? "amber" : "off",
    isOnion || fsBrowser.hiddenServiceOnly ? "green" : "off",
    hasLiveSwap ? "amber" : !isOnion && !isStart ? "amber" : "off",
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
  const liveSwap = fsBrowser.trackedSwap && fsBrowser.trackedSwap.status !== "completed";

  if (fsBrowser.remoteMode === "circuit") {
    titleEl.textContent = "TOR CIRCUIT";
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

  titleEl.textContent = "REMOTE";
  bodyEl.innerHTML = `
    <button type="button" class="fs-remote-btn" id="rem-onion-sites">
      <span>Onion Sites</span>
      <span class="mono-sub">11</span>
    </button>
    <button type="button" class="fs-remote-btn" id="rem-wallet">
      <span>Monero Wallet</span>
      <span class="mono-sub">${fsBrowser.xmrBalance.toFixed(4)} XMR</span>
    </button>
    <button type="button" class="fs-remote-btn" id="rem-swap">
      <span>Swap BTC → XMR</span>
      <span class="mono-sub ${liveSwap ? "mono-sub--amber" : ""}">${liveSwap ? "1 LIVE" : ".ONION"}</span>
    </button>
    <div class="fs-remote-grid">
      <button type="button" class="fs-remote-btn" id="rem-new-tab"><span>New Tab</span><span>+</span></button>
      <button type="button" class="fs-remote-btn" id="rem-tabs"><span>Tabs</span><span class="mono-sub">${fsBrowser.tabs.length}</span></button>
      <button type="button" class="fs-remote-btn" id="rem-reload"><span>Reload</span><span>↻</span></button>
      <button type="button" class="fs-remote-btn" id="rem-home"><span>Home</span><span>⌂</span></button>
      <button type="button" class="fs-remote-btn" id="rem-zoom-out"><span>Zoom −</span><span class="mono-sub">${tab.zoomPercent}%</span></button>
      <button type="button" class="fs-remote-btn" id="rem-zoom-in"><span>Zoom +</span><span class="mono-sub">${tab.zoomPercent}%</span></button>
    </div>
    <button type="button" class="fs-remote-btn" id="rem-find">
      <span>Text Search</span>
      <span class="mono-sub">Zero-JS</span>
    </button>
    <button type="button" class="fs-remote-btn" id="rem-keyring">
      <span>OpenPGP Keyring</span>
      <span class="mono-sub">${fsBrowser.keys.length} Keys</span>
    </button>
    <button type="button" class="fs-remote-btn" id="rem-tor-circuit">
      <span>Tor Circuit</span>
      <span class="mono-sub">${fsBrowser.torReady ? "READY" : "BOOT"}</span>
    </button>
  `;

  document.getElementById("rem-onion-sites")?.addEventListener("click", () => {
    fsBrowser.remoteOpen = false;
    fsBrowser.crtOpen = true;
    renderFsBrowser();
    document.getElementById("fs-crt-input")?.focus();
  });
  document.getElementById("rem-wallet")?.addEventListener("click", () => {
    fsBrowser.remoteOpen = false;
    fsBrowser.modalKind = "wallet";
    renderFsBrowser();
  });
  document.getElementById("rem-swap")?.addEventListener("click", () => {
    fsBrowser.remoteOpen = false;
    fsBrowser.modalKind = "swap";
    renderFsBrowser();
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
}

function renderModalSheet() {
  const layer = document.getElementById("fs-modal-layer");
  const sheet = document.getElementById("fs-modal-sheet");
  if (!layer || !sheet) return;

  layer.hidden = !fsBrowser.modalKind;
  if (!fsBrowser.modalKind) return;

  if (fsBrowser.modalKind === "wallet") {
    const xmrUri = `monero:${fsBrowser.xmrActiveSubaddress}`;
    sheet.innerHTML = `
      <div class="fs-modal-head">
        <h3>MONERO LIGHT WALLET</h3>
        <div style="display:flex;gap:0.45rem;align-items:center;">
          <button type="button" class="fs-well-btn fs-well-btn--go" id="xmr-sync-btn">Sync Tor</button>
          <button type="button" class="fs-drawer-close" id="modal-close-btn">✕</button>
        </div>
      </div>
      <div class="fs-modal-stack">
        <div class="fs-list-card">
          <div>
            <strong style="font-size:1.22rem;font-family:var(--font-mono);color:var(--phosphor);">${fsBrowser.xmrBalance.toFixed(
              4
            )} XMR</strong>
            <div style="font-family:var(--font-mono);font-size:0.74rem;color:var(--ink-body);">Synced ${
              fsBrowser.xmrSyncedHeight
            } / ${fsBrowser.xmrSyncedHeight} (.onion)</div>
          </div>
          <button type="button" class="fs-well-btn" id="xmr-to-swap-btn">Swap BTC → XMR</button>
        </div>
        <div class="fs-qr-wrap">
          <div class="fs-qr-box" id="fs-wallet-qr">${renderQrSvg(xmrUri)}</div>
        </div>
        <pre class="fs-mono-block">${escapeHtml(fsBrowser.xmrActiveSubaddress)}</pre>
        <div class="hs-action-row" style="margin-top:0;">
          <button type="button" class="fs-well-btn fs-well-btn--go" id="xmr-new-subaddr">+ New 8… Subaddress</button>
          <button type="button" class="fs-well-btn" id="xmr-open-402-demo">Test HTTP 402 XMR402 Gate</button>
        </div>
      </div>
    `;

    document.getElementById("xmr-sync-btn")?.addEventListener("click", () => {
      fsBrowser.xmrSyncedHeight += Math.floor(1 + Math.random() * 4);
      renderModalSheet();
    });
    document.getElementById("xmr-to-swap-btn")?.addEventListener("click", () => {
      fsBrowser.modalKind = "swap";
      renderModalSheet();
    });
    document.getElementById("xmr-new-subaddr")?.addEventListener("click", () => {
      fsBrowser.xmrActiveSubaddress = mintUnlinkableSubaddress();
      renderModalSheet();
    });
    document.getElementById("xmr-open-402-demo")?.addEventListener("click", () => {
      fsBrowser.modalKind = null;
      navigateTo("http://xmr402vault7k9qp2m4n8v3c6x1z5l9r2t4y6u8i0o2p4a6s8d0f2gad.onion/", true);
    });
  } else if (fsBrowser.modalKind === "swap") {
    const btcAmt = Math.max(0.0005, parseFloat(fsBrowser.swapBtcInput) || 0.01);
    const offersHtml = SWAP_PROVIDERS.map((p) => {
      const est = (btcAmt * p.rate).toFixed(4);
      return `
        <div class="fs-offer-card">
          <div class="fs-offer-head">
            <div>
              <strong style="font-size:0.96rem;">${escapeHtml(p.provider)}</strong>
              <div style="font-family:var(--font-mono);font-size:0.76rem;color:var(--phosphor);">${est} XMR · 1 BTC ≈ ${p.rate.toFixed(
                2
              )} XMR</div>
            </div>
            <span class="fs-status-chip">${escapeHtml(p.chip)}</span>
          </div>
          <button type="button" class="fs-well-btn fs-well-btn--go" data-launch-swap="${escapeHtml(
            p.id
          )}">Swap in Browser</button>
        </div>
      `;
    }).join("");

    const sw = fsBrowser.trackedSwap;
    const trackedHtml = sw
      ? `
        <div class="fs-offer-card">
          <div class="fs-offer-head">
            <div>
              <strong>${escapeHtml(sw.provider)} · ${escapeHtml(sw.btcAmount)} BTC</strong>
              <div style="font-family:var(--font-mono);font-size:0.73rem;color:var(--ink-muted);">BTC In: ${escapeHtml(
                sw.btcDepositAddress.slice(0, 12)
              )}…${escapeHtml(sw.btcDepositAddress.slice(-8))}</div>
            </div>
            <span class="fs-status-chip ${sw.status === "completed" ? "" : "fs-status-chip--amber"}">${
              sw.status === "completed" ? `COMPLETED (+${sw.estimatedXmr} XMR)` : "DEPOSIT BTC"
            }</span>
          </div>
          <div class="fs-qr-wrap">
            <div class="fs-qr-box" id="fs-swap-qr">${renderQrSvg(
              `bitcoin:${sw.btcDepositAddress}?amount=${sw.btcAmount}`
            )}</div>
          </div>
          <div class="hs-action-row" style="margin-top:0;">
            <button type="button" class="fs-well-btn" id="swap-open-order">Open Order Page</button>
            <button type="button" class="fs-well-btn fs-well-btn--go" id="swap-sync-payout">Sync Payout</button>
          </div>
        </div>
      `
      : "";

    sheet.innerHTML = `
      <div class="fs-modal-head">
        <h3>SWAP BTC → XMR (TOR)</h3>
        <div style="display:flex;gap:0.45rem;align-items:center;">
          <button type="button" class="fs-well-btn" id="swap-to-wallet-btn">Wallet</button>
          <button type="button" class="fs-drawer-close" id="modal-close-btn">✕</button>
        </div>
      </div>
      <div class="fs-modal-stack">
        <p style="margin:0;font-size:0.82rem;color:var(--ink-muted);">
          Live zero-JS aggregator quotes via OrangeFren .onion over Arti Tor. Each swap mints a fresh unlinkable 8… subaddress.
        </p>
        <form class="hs-search-form" id="swap-quote-form" style="margin:0;">
          <input type="text" class="hs-input" id="swap-btc-input" value="${escapeHtml(
            fsBrowser.swapBtcInput
          )}" style="font-family:var(--font-mono);color:var(--phosphor);" />
          <button type="submit" class="fs-well-btn fs-well-btn--go" id="swap-fetch-btn">Fetch Live Tor Quotes</button>
        </form>
        ${offersHtml}
        ${trackedHtml}
      </div>
    `;

    document.getElementById("swap-to-wallet-btn")?.addEventListener("click", () => {
      fsBrowser.modalKind = "wallet";
      renderModalSheet();
    });
    document.getElementById("swap-quote-form")?.addEventListener("submit", (e) => {
      e.preventDefault();
      const val = document.getElementById("swap-btc-input")?.value || "0.01";
      fsBrowser.swapBtcInput = val.trim() || "0.01";
      renderModalSheet();
    });
    sheet.querySelectorAll("[data-launch-swap]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const pid = btn.getAttribute("data-launch-swap");
        const prov = SWAP_PROVIDERS.find((p) => p.id === pid) || SWAP_PROVIDERS[0];
        const amt = document.getElementById("swap-btc-input")?.value || fsBrowser.swapBtcInput;
        fsBrowser.swapBtcInput = amt;
        launchSwapQuoteInBrowser(prov, amt);
      });
    });
    document.getElementById("swap-open-order")?.addEventListener("click", () => {
      if (fsBrowser.trackedSwap) {
        fsBrowser.modalKind = null;
        navigateTo(fsBrowser.trackedSwap.orderUrl, true);
      }
    });
    document.getElementById("swap-sync-payout")?.addEventListener("click", settleActiveSwapPayout);
  } else if (fsBrowser.modalKind === "tabs") {
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
        <h3>SPATIAL TAB SWITCHER (${fsBrowser.tabs.length})</h3>
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
        <h3>SEQUOIA OPENPGP KEYRING</h3>
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
        <h3>VERIFIED OPENPGP PLAINTEXT</h3>
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
        <pre class="fs-mono-block" style="color:var(--ink-bright);">${escapeHtml(
          fsBrowser.decryptedMessage.body
        )}</pre>
      </div>
    `;
  } else if (fsBrowser.modalKind === "clearnet-consent" && fsBrowser.pendingClearnetUrl) {
    const host = extractHost(fsBrowser.pendingClearnetUrl);
    const rootDomain = host.split(".").slice(-2).join(".");
    sheet.innerHTML = `
      <div class="fs-modal-head">
        <h3>CLEARNET STREAM HELD AT SOCKS5H GATE</h3>
        <button type="button" class="fs-drawer-close" id="modal-close-btn">✕</button>
      </div>
      <div class="fs-modal-stack">
        <p style="margin:0;color:var(--ink-body);font-size:0.92rem;">
          <code>${escapeHtml(host)}</code> is a clearnet origin. Authorize routing over Tor exit relays?
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
        <h3>BLOCKED BY .ONION-ONLY LOCK</h3>
        <button type="button" class="fs-drawer-close" id="modal-close-btn">✕</button>
      </div>
      <div class="fs-modal-stack">
        <p style="margin:0;color:var(--ink-body);font-size:0.92rem;">
          <code>${escapeHtml(
            extractHost(fsBrowser.pendingClearnetUrl || "")
          )}</code> was refused because <strong>.onion-Only Lock</strong> is enabled in Tor Circuit.
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
  renderSwapHud();
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

  document.getElementById("fs-modal-scrim")?.addEventListener("click", () => {
    fsBrowser.modalKind = null;
    renderFsBrowser();
  });

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

  window.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      if (showcaseState.lockedId) {
        startPreviewTransition(null);
      }
      let changed = false;
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
  preloadStateImages();
  renderPhoneShowcase();
  syncShowcaseVisuals();

  bindFullScreenBrowserInteractions();
  renderFsBrowser();

  const imgEl = document.getElementById("preview-base-image");
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
