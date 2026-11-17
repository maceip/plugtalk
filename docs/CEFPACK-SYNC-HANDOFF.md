# Cloud-to-Local Sync & Cefpack Cutover Handoff

## 1. Repository State at Sync Point

- **`maceip/trap` (`origin/main`)**
  - `ddd6261` — Android Picture-in-Picture upgrade + Android 15–17 (API 35–37) integrations.
  - `118e6be` — Updated Android launcher icons (`mdpi`–`xxxhdpi`), adaptive foreground/background, Android 13+ `<monochrome>`, Play Store (`512px`) & `1024px` artwork, light/dark wordmark lockups (`design/lockups/*.{png,svg}`), in-app `ov_trap_mark.png`, XML color resources, and Compose palette from `trap_cutout_v3@2x.webp`.
  - Working tree: **100% clean and pushed**.
- **`maceip/cefpack` (`origin/main`)**
  - Fast-forwarded cleanly to `59a0117` (local agent's active commits `87c3d9f..59a0117` for host sysroot, `pkgconf`, `LASTCHANGE`, and `content-link-measure.sh`).
  - **Zero edits or commits were made from the cloud VM to `maceip/cefpack`**, so the local agent's branch/working tree has zero upstream conflicts.

---

## 2. Architecture Decision: Skip Unused Desktop `cef_*` C-API Shim

- `libonion_vault.so` (Trap's Rust core: Arti Tor client, Tor isolation, vault crypto, PGP) calls **0** `cef_*` symbols and has **0** `DT_NEEDED` browser library dependencies.
- Trap drives the browser engine strictly from Kotlin (`TrapApplication`, `TabSessionManager`, `CdpDomPgpController`, `OnionBackStackManager`, `MainActivity`) plus browser-process DevTools CDP JSON.
- Building an Android Content Embedder AAR (`cefpack-android.aar`, `arm64-v8a` + `x86_64`) directly atop `//content/public/android` + `//components/embedder_support/android` + `content::DevToolsAgentHost` eliminates the desktop-only `cef/` `toolkit_views` C-API shim (`libcef_dll_wrapper.a`) while keeping `0` `//chrome/android` and `0` Google Play Services / Firebase / MLKit classes.

---

## 3. Verified Chromium `156.0.8078.96` (`2ad9b7f5a86846e0b264090723a6a78964bb84c4`) Build Notes for `cefpack`

1. **Remaining `lastchange.py` Header Hooks When Skipping `gclient runhooks`:**
   In addition to `build/util/LASTCHANGE` (added in `maceip/cefpack@eee1306`), Chromium 156's `DEPS` runs three generated-header hooks required when compiling `gpu/` and `skia/`:
   ```bash
   python3 build/util/lastchange.py -m GPU_LISTS_VERSION --revision-id-only --header gpu/config/gpu_lists_version.h
   python3 build/util/lastchange.py -m SKIA_COMMIT_HASH -s third_party/skia --header skia/ext/skia_commit_hash.h
   python3 build/util/lastchange.py -m DAWN_COMMIT_HASH -s third_party/dawn --revision gpu/webgpu/DAWN_VERSION --header gpu/webgpu/dawn_commit_hash.h
   ```
2. **Exact Chromium 156 `ContentBrowserClient` Signatures (`content/public/browser/content_browser_client.h`):**
   - `void OverrideWebPreferences(content::WebContents* web_contents, content::SiteInstance& main_frame_site, blink::web_pref::WebPreferences* prefs) override`
   - `void CreateThrottlesForNavigation(content::NavigationThrottleRegistry& registry) override` (uses `registry.GetNavigationHandle()` and `registry.AddThrottle(...)` from `content/public/browser/navigation_throttle_registry.h`)
   - `std::vector<std::unique_ptr<blink::URLLoaderThrottle>> CreateURLLoaderThrottles(const network::ResourceRequest& request, content::BrowserContext* browser_context, const base::RepeatingCallback<content::WebContents*()>& wc_getter, content::NavigationUIData* navigation_ui_data, content::FrameTreeNodeId frame_tree_node_id, std::optional<int64_t> navigation_id) override`
   - Zoom API: `content::HostZoomMap::SetZoomLevel(web_contents, level)` (`content/public/browser/host_zoom_map.h`).
3. **Multi-ABI (`arm64-v8a` + `x86_64`) AAR Packaging Without Collisions:**
   - Set `v8_use_external_startup_data = false` so each ABI embeds its V8 snapshot inside `jni/<abi>/libcefpack_content.so` instead of colliding on `assets/snapshot_blob_64.bin`.
   - Set `enable_jni_multiplexing = false` and `use_hashed_jni_names = false` so `org.jni_zero.GEN_JNI` is identical across `arm64` and `x64`.
   - Include `generate_build_config_srcjar` (`org.chromium.build.BuildConfig`), `org.jni_zero.GEN_JNI`, and a runtime-ABI `org.chromium.build.NativeLibraries` (`sCpuFamily` selected from `Build.SUPPORTED_ABIS[0]`), because `//build/android:build_java` excludes `BuildConfig.class` and `NativeLibraries.class` from `dist_aar`.
   - Generate delegating `org.chromium.*.R` classes forwarding non-final static fields to `dev.cefpack.android.R` so `SelectionPopupControllerImpl` and `embedder_support` resolve real AGP `0x7f......` resource IDs at runtime.
4. **Zero GMS / Firebase / MLKit Classes:**
   - Exclude `"com/google/android/gms/*"`, `"com/google/firebase/*"`, `"com/google/mlkit/*"`, and `"org/chromium/content/browser/sms/*"` in `dist_aar`'s `jar_excluded_patterns` (`content_jni_onload.cc` performs lazy JNI lookup, so unused WebOTP SMS classes are never resolved unless invoked).
5. **Android 15/16/17 Sandbox & 16 KB Page Alignment:**
   - Link `libcefpack_content.so` with `-Wl,-z,max-page-size=16384`.
   - Declare `SandboxedProcessService0..39` (`android:isolatedProcess="true"`, `android:exported="false"`, **no** `android:useAppZygote`, **no** `android:memtagMode`) and `PrivilegedProcessService0..4` (`android:isolatedProcess="false"`, `android:exported="false"`), plus `--disable-features=PartitionAllocMemoryTagging` in `BasicStartupComplete` before `FeatureList` initialization.

---

## 4. Remaining Cutover Checklist in `maceip/trap` (Once `cefpack-android.aar` Ships)

1. Introduce `dev.onionvault.engine.BrowserEngine` / `BrowserSession` and `CefpackEngineAdapter` under `android/app/src/main/kotlin/dev/onionvault/engine/`.
2. Migrate `TrapApplication.kt`, `TabSessionManager.kt`, `CdpDomPgpController.kt`, `OnionBackStackManager.kt`, `MainActivity.kt`, and `BrowserChromeInstrumentedTest.kt` off `com.cefrium.*`.
3. Remove `maven("https://maven.cefrium.com")`, `id("com.cefrium")`, and `com.cefrium:cefrium-sdk` from `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/app/build.gradle.kts`, and `android/app/proguard-rules.pro`; pull and SHA-256-verify `cefpack-android.aar` from `maceip/cefpack` Releases.
4. Preserve gate logcat markers (`Trap: the engine is ready`, `loadUrl: <url>`, and `OnLoadEnd: status <code>, isMain=<1|0>`) and update `scripts/audit-apk.sh`, `scripts/generate-sbom.py`, `scripts/emulator-gate.sh`, `scripts/limrun-gate.mjs`, and `THIRD-PARTY-NOTICES.md` to assert `0` `Lcom/cefrium/`, `Lcom/google/android/gms/`, `Lcom/google/firebase/`, and `Lcom/google/mlkit/` classes.
