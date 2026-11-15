# Home run: from "crash on launch" to the browser we promised

Written 2026-11-14. This is the plan of record. Every step ends in something
someone has **seen running on an emulator or a phone**, not just compiled.

---

## 0. Where we actually are (verified, not claimed)

As of `main` @ `612e4cc` (pushed 2026-11-14), on a Limrun x86_64 Android 15 emulator:

| Check | Result | Evidence |
|---|---|---|
| App launches and stays up past 90 s | yes | logcat, no `AndroidRuntime` / `F DEBUG` |
| Chromium (Cefrium 0.19.0 / Chromium 156) initialises | yes | `TrapApplication: Cefrium 0.19.0 ... OK` |
| Embedded Tor (Arti) bootstraps | yes, ~3 s | `ov_tor: Tor client bootstrapped` |
| Android Keystore master key | yes | `master key is provided by Android Keystore` |
| Onion service loads, JavaScript off | yes | DuckDuckGo onion, `OnLoadEnd: status 200`, screenshot |
| Clearnet blocked in hidden-service-only mode | yes | `refusing clearnet connection to content-autofill.googleapis.com` |
| PGP chips on page fields | **no** | needs a new DOM transport (section 3, step 3) |
| Settings screen, start page, context menu | **no** | never built |
| Monero wallet | **no** | artwork only, no code |

**The GitHub releases before `612e4cc` (build-1 … build-20) all crash on launch.**
Mark them as pre-release or delete them. Only advertise builds that pass the gate in section 2.

**Warning about a second working tree.** Another session has a local tree with
`CrashGuard`, `CefRuntime.kt`, a reverted design-system module and inline KDoc.
That tree was **never built into an APK** (the Gradle daemon was OOM-killed) and
it still drives CEF through the C API. Do not push it over `main`. Copy over only
the pieces that are useful (the crash-guard idea, the UI catalogue KDoc), one at
a time, and launch on the emulator after each one.

---

## 1. Why Cefrium, and why not "cef-rs + bundle it ourselves"

- **CEF** (Chromium Embedded Framework) is Chromium packaged as a library.
  Upstream CEF, and therefore **cef-rs**, ships builds for Windows, macOS and
  Linux only. `cef-rs` has no Android binary to bundle; its bindings are only
  the C API headers.
- **Cefrium** is the port of CEF to Android: Chromium 156 built for Android,
  with the CEF C API on top. It is what produced the `libcef.so` this project
  has shipped from day one. It is not an extra dependency; the old build just
  consumed it wrong (it kept the `.so` and dropped the Java half the `.so` needs).
- **"Bundle it ourselves"** means building Chromium for Android with CEF's patches:
  a checkout of about 100 GB, hours per build on a large machine, and rebasing
  every Chromium security release ourselves. That isn't viable for this team.
- **Alternatives, if we ever want to leave Chromium:**
  - **System WebView:** no engine to bundle, and the APK shrinks by about 200 MB.
    Its weaknesses are a per-app proxy and no DOM access without JavaScript.
  - **GeckoView:** the engine Tor Browser for Android uses. A big switch, and
    it has the same JavaScript-only DOM access.

  **Decision (bootstrap -> source-owned migration):** use Cefrium (`0.19.0`) only
  as the verified bootstrap to get Trap booting and gated on real devices today—never
  the broken `cef-rs` / `crates/cefpack` desktop C-API wrapper (`0653953`)—and then
  replace pre-built `com.cefrium:cefrium-sdk` packages with a first-party, source-built
  Chromium `//content/public/android` AAR compiled via `siso` REv2 on Limrun's remote
  build plane (Step 8; full specification in
  [`docs/BROWSER-RUNTIME-BUILD-PLAN.md`](docs/BROWSER-RUNTIME-BUILD-PLAN.md)).

---

## 2. The gate (do this first; it's what stops a fourth crash)

No release, README claim or announcement unless this passes:

1. CI builds the APK (arm64 + x86_64) with `scripts/setup-toolchain.sh` versions.
2. CI boots an **x86_64 emulator** (`reactivecircus/android-emulator-runner`, API 35,
   or the Limrun API), installs the APK and launches it.
3. CI asserts, within 120 s, these lines in logcat and none of `FATAL EXCEPTION` /
   `F DEBUG`: `Cefrium ... OK`, `the browser is ready`, `Tor client bootstrapped`.
4. CI loads an onion URL (`am start` with a VIEW intent, or adb input) and asserts
   `OnLoadEnd: status 200`.
5. A screenshot is attached to the release.
6. The `release` job `needs:` this job. A green unit test suite alone never publishes.

The README gets a **Status** table with one row per feature: *verified on device
(date, build)* or *not yet*. Nothing else is claimed.

---

## 3. The path, in order. Each step ends with a gate run.

### Step 1: Honest public state (30 min)
- Pre-release or delete builds 1–20. Publish `612e4cc` as `v0.1.0-alpha`
  ("boots, browses onion services over Tor with JavaScript off; PGP in page not yet").
- README: replace feature claims with the Status table.

### Step 2: The gate (half a day)
Section 2, as a CI job. Until it exists, every change is installed on the
Limrun emulator by hand (`syncApp` + `launchApp`; the script lives in the session notes).

### Step 3: Cut the code that can never run (1 day)
On Android, Chromium belongs to Cefrium. Delete everything that pretends otherwise:
- `ov-cef`: the render-process handler, the `CefApp`/`CefClient` wrappers, OSR paint
  handler, request handler, snapshot collector. **Keep** the pure DOM model
  (HTML parser, PGP field classifier, node ids) and its tests.
- `ov-core`: `SurfaceRenderer`, and the JNI calls for surface, touch, keys, IME,
  message loop, subprocess, snapshots, the DOM mutation calls.
- Kotlin: the dead `OvNative` externals, `OnionBackStackManager` frame snapshots
  (rebuild with `CefriumBrowser.captureBitmap` in step 6).
- Drop the `cef` / `cef-dll-sys` crates from the Android build. **The result:**
  `libonion_vault.so` no longer links `libcef.so` at all. Rust is just
  **Tor + OpenPGP over a small JNI surface**. Most of today's boot bugs came from
  that link and are gone with it.

Resulting architecture:

```
┌──────────────────────── Kotlin / Compose ────────────────────────┐
│ TrapApplication: Chromium process setup, Cefrium.initialize       │
│ MainActivity / chrome (design system, themes, predictive back)    │
│ CefriumBrowser: JS off, proxy = Tor SOCKS, DevTools DOM.* access  │
│ PgpPageController: finds PGP fields/blocks, shows chips & sheets  │
└──────────────┬───────────────────────────────────┬────────────────┘
               │ JNI (≈10 calls)                   │ DevTools protocol
┌──────────────▼──────────────┐       ┌────────────▼──────────────┐
│ libonion_vault.so (Rust)     │       │ Chromium (Cefrium SDK)     │
│  ov-tor  Arti + SOCKS5,      │       │  renders, networks only    │
│          leak policy         │       │  through the Tor proxy     │
│  ov-pgp  Sequoia, Keystore   │       └────────────────────────────┘
│  ov-dom  parser + classifier │
└──────────────────────────────┘
```

### Step 4: PGP on web pages, the promised headline feature (2–3 days)
Zero page JavaScript is preserved by working from the browser process:
1. **Spike first (half a day, on the emulator):** with `setJavaScriptEnabled(false)`,
   check which of these work through `CefriumBrowser.executeDevToolsMethod`:
   `DOM.getDocument`, `DOM.querySelectorAll`, `DOM.getOuterHTML`,
   `DOM.getBoxModel`, `DOM.setAttributeValue`, `DOM.setNodeValue`, and whether
   `DOM.resolveNode` + `Runtime.callFunctionOn` (isolated world, not page script)
   can read/write a field's live `value`. Record the results in this file.
2. Feed `DOM.getOuterHTML` to the existing Rust classifier, which returns the fields
   and armored blocks. Position chips from `DOM.getBoxModel`.
3. Decrypt is shown in a native sheet (never written into the page unless the user taps
   Insert). Encrypt/Insert key write the field through whatever the spike proved.
4. **Fallback that needs no DevTools at all:** `setTextSelectionActionModeCallback`
   adds *Decrypt* / *Encrypt* / *Import key* to the text-selection menu, using the
   clipboard. Ship this first if the spike is slow; it's useful on its own.
5. Gate addition: a local test page with an armored message must show a Decrypt
   chip and decrypt it on the emulator.

Also fix from the OpenPGP audit:
- Show the signer's identity, not just "verified".
- Sign outgoing messages.
- Passphrase prompt for imported keys.
- Persist the desktop master key (dev only).

### Step 5: Never die silently (half a day)
- `Thread.setDefaultUncaughtExceptionHandler`: write the stack trace to a file, then let the process die.
- On the next start: read the file plus `ActivityManager.getHistoricalProcessExitReasons`
  (this also covers native crashes and their tombstone trace) and show a screen with
  the trace, a **Copy** button and **Try again**. No native signal handler is needed.
- Every startup step already returns a reason to an error screen instead of throwing (done in `8bb4ea2`).

### Step 6: The chrome (design work, 1–2 weeks)
- One token system (colour, type, shape, motion, elevation) with **two themes**:
  the neon graffiti board, and the calm SecureDrop browns from the attached design
  (`#0f0c09 / #1a1510 / #2a211a / #4a3a2a`, amber `#e8a33d`, teal `#2ec4b0`,
  green `#3ecf6a`; 44 px controls, 8 px gaps, input + Go as one unit, four nav buttons).
  Light and dark for both.
- Surfaces: start page (first run), address bar, nav bar, menu, settings,
  context menu (`CefriumBrowser.Callback.onContextMenu`), PGP chips/sheets/key manager,
  Tor circuit display, licences, error/crash screen.
- Predictive back: preview of the previous page via `captureBitmap`. The tilt-up
  "under the screen" graffiti layer is a design-system animation on top of that.
- Navigation 3 back stack owned by the design system.
- Build it one surface at a time, gate after each.

### Step 7: Ship-ready (1–2 days)
- ABI splits or an App Bundle (the APK is ~450 MB with both ABIs; a phone download is ~150 MB).
- Release signing, versioning, R8 with the SDK's ProGuard rules.
- Monero wallet: out of scope for v1. Remove the artwork-only entry points or
  label them "coming later".

### Step 8: Source-built browser runtime — replace pre-built Cefrium packages (7–11 days)
Full forensic audit and engineering specification: [`docs/BROWSER-RUNTIME-BUILD-PLAN.md`](docs/BROWSER-RUNTIME-BUILD-PLAN.md).
- **Why pre-built `com.cefrium:cefrium-sdk:0.19.0` must go:** `cefrium-sdk-0.19.0.aar` ships 450+ MB of third-party pre-compiled native binaries (`libcef.so`, Chromium `156.0.8078.80`, 242 unused `cef_*` C-API symbols) plus a 120.2 MB Java-25 `classes.jar` (`31,398` classes including `9,424` `org.chromium.chrome.*` classes and `50` Play Services / Firebase / Clearcut / MLKit dependencies). Now that `libonion_vault.so` has `0` `libcef.so` `DT_NEEDED` symbols, Trap does not need the desktop CEF C-API middleman at all.
- **Phase 0 (1 day):** Extract Kotlin `BrowserEngine` / `BrowserSession` interface seam (`CefriumEngineAdapter` first, zero behavior change).
- **Phase 1 (1–2 days):** Verify Chromium `siso` REv2 remote execution & cache against Limrun's Linux `x86_64` build plane (`reapi_address = "buildplane.us-east1.limrun.net:4443"`, `reapi_instance = "org_01m7ger979f4b98sesd3w802kv"`, `SISO_CREDENTIAL_HELPER` -> `lim-bazel-credentials`, `OSFamily = "linux"`, `limrun-toolchains = "jdk-17-21-25-clang18-v2"`).
- **Phase 2 (4–6 days):** Build zero-patch `trap-chromium-content.aar` (`libtrap_chromium_content.so`, `arm64-v8a` + `x86_64`, 16KB ELF `PT_LOAD` alignment, ARMv9 MTE-safe `PartitionAlloc`, `enable_webrtc = false`, `proprietary_codecs = false`) directly atop pinned upstream `chromium/src` (`//content/public/android` + `ContentViewRenderView` + `ProxyConfigClient` Mojo SOCKS5h pipe + `WebPreferences::javascript_enabled = false` + `DevToolsAgentHostClient` JSON bridge).
- **Phase 3 (2–3 days):** Publish reproducible `trap-chromium-content.aar` with CycloneDX/SPDX SBOM + Sigstore SLSA v1 provenance, remove `maven.cefrium.com` and `com.cefrium:*` completely, enforce zero `Lcom/cefrium/` & `Lcom/google/android/gms/` DEX classes in `scripts/audit-apk.sh`, and pass `QEMU virt,mte=on`, `pixel16k`, and `limrun-device-gate`.

---

## 4. Definition of "nearly ready"

All of these are green on the gate, on a recorded emulator run **and** one real phone:

- [x] Launches, stays up, Tor bootstraps, onion page loads, clearnet blocked
- [ ] Gate job blocks releases (section 2)
- [ ] Dead CEF/JNI code deleted; `libonion_vault.so` doesn't link `libcef.so`
- [ ] PGP: decrypt a message on a page, encrypt into a field, import/export a key
- [ ] Crash screen with copyable trace after a forced crash
- [ ] Both themes, light + dark, on every surface listed in step 6
- [ ] Release APK/AAB signed, sized per ABI, README Status table matches reality
- [ ] Browser runtime built from pinned Chromium source (`trap-chromium-content.aar` via `siso` REv2 on Limrun; zero pre-built `com.cefrium:*` packages — Step 8 / [`docs/BROWSER-RUNTIME-BUILD-PLAN.md`](docs/BROWSER-RUNTIME-BUILD-PLAN.md))

Rough total: **2–3 weeks of focused work**, front-loaded so that a working,
honestly described alpha exists today and every later step only adds verified features.

---

## 5. Housekeeping

- Rotate the three Limrun API keys pasted into chat sessions.
- Toolchain: `scripts/setup-toolchain.sh`, then `source /opt/trap-toolchain/env.sh`,
  then `cd android && ./gradlew :app:assembleDebug` (under a minute once warm; needs about 20 GB free disk).
- One branch (`main`), small commits, every commit message says what was *run*.
