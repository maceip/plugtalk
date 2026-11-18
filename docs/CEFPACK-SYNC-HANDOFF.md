# Cloud-to-Local Sync & Cefpack Cutover Notes

## 1. Architecture Summary

- `libonion_vault.so` (Trap's Rust core: Arti Tor client, Tor isolation, vault crypto, PGP, and pure-Rust CDP DOM classifier) calls **0** browser C-API symbols and has **0** `DT_NEEDED` browser library dependencies.
- Trap drives the browser engine strictly from Kotlin (`TrapApplication`, `TabSessionManager`, `CdpDomPgpController`, `OnionBackStackManager`, `MainActivity`) via `dev.cefpack.android.{CefpackEngine,CefpackSession}` plus in-process `content::DevToolsAgentHost` CDP JSON.
- `cefpack-android.aar` (`arm64-v8a` + `x86_64`) is built from pinned Chromium `156.0.8078.96` (`2ad9b7f5a86846e0b264090723a6a78964bb84c4`) directly atop `//content/public/android` + `//components/embedder_support/android` in [`https://github.com/maceip/cefpack`](https://github.com/maceip/cefpack), keeping `0` `//chrome/android` and `0` Google Play Services / Firebase / MLKit classes.

## 2. Verified Chromium `156.0.8078.96` Build Notes (`maceip/cefpack`)

1. **Generated `lastchange.py` Header Hooks When Skipping `gclient runhooks`:**
   ```bash
   python3 build/util/lastchange.py -m GPU_LISTS_VERSION --revision-id-only --header gpu/config/gpu_lists_version.h
   python3 build/util/lastchange.py -m SKIA_COMMIT_HASH -s third_party/skia --header skia/ext/skia_commit_hash.h
   python3 build/util/lastchange.py -m DAWN_COMMIT_HASH -s third_party/dawn --revision gpu/webgpu/DAWN_VERSION --header gpu/webgpu/dawn_commit_hash.h
   ```
2. **Chromium 156 `ContentBrowserClient` Signatures (`content/public/browser/content_browser_client.h`):**
   - `void OverrideWebPreferences(content::WebContents* web_contents, content::SiteInstance& main_frame_site, blink::web_pref::WebPreferences* prefs) override`
   - `void CreateThrottlesForNavigation(content::NavigationThrottleRegistry& registry) override`
   - `std::vector<std::unique_ptr<blink::URLLoaderThrottle>> CreateURLLoaderThrottles(...) override`
   - Zoom API: `content::HostZoomMap::SetZoomLevel(web_contents, level)`.
3. **Multi-ABI (`arm64-v8a` + `x86_64`) AAR Packaging (`embedder/assemble_aar.py`):**
   - `v8_use_external_startup_data = false`, `enable_jni_multiplexing = false`, and `use_hashed_jni_names = false`.
   - Bundles `org.chromium.build.BuildConfig`, `org.jni_zero.GEN_JNI`, runtime-ABI `org.chromium.build.NativeLibraries`, and delegating `org.chromium.*.R` classes forwarding to `dev.cefpack.android.R`.
   - Strips `com/google/android/gms/*`, `com/google/firebase/*`, `com/google/mlkit/*`, and `org/chromium/content/browser/sms/*`.
   - Links `libcefpack_content.so` with `-Wl,-z,max-page-size=16384` (`16 KB` `PT_LOAD` alignment) and declares `SandboxedProcessService0..39` (`android:isolatedProcess="true"`) + `PrivilegedProcessService0..4` (`android:isolatedProcess="false"`).

## 3. Trap Integration Contract

- `:app:resolveCefpackAar` in `android/app/build.gradle.kts` downloads and SHA-256-verifies `cefpack-android.aar` from `https://github.com/maceip/cefpack/releases/download/<tag>/cefpack-android.aar` into `android/app/build/cefpack/<tag>/cefpack-android.aar` (supporting `-PcefpackAarPath` / `CEFPACK_AAR_PATH` local overrides).
- Runtime logcat markers verified by `scripts/emulator-gate.sh` and `scripts/limrun-gate.mjs`:
  - `Cefpack : Initialized Cefpack content engine (proxy=...)` and `Trap : the engine is ready`
  - `Cefpack : loadUrl: <url>`
  - `Cefpack : OnLoadEnd: status <code>, isMain=<1|0>`
