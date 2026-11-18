#!/usr/bin/env node
/**
 * Limrun Cloud Android Runtime, `.onion` Smoke & 3-Round OpenPGP TOTP Prize Gate for Trap (`dev.onionvault`).
 *
 * Provisions an isolated cloud Android instance via the official `@limrun/api` SDK,
 * syncs & installs both `app-debug.apk` and `app-debug-androidTest.apk` (`syncApp` with
 * `assets.getOrUpload` + `sendAsset` fallback), launches `dev.onionvault` under a live
 * ActivityManager `onExit` crash/ANR watch, and verifies end-to-end:
 *   1. `TrapApplication` initializes Cefpack (`"Initialized Cefpack content engine"` / `"the engine is ready"`),
 *   2. `ov_tor` binds its loopback SOCKS5 listener (`127.0.0.1:<port>`),
 *   3. Embedded Arti bootstraps against the live Tor network (`"Tor client bootstrapped"`
 *      / `"Tor ready"` in UI),
 *   4. Compose `StartPage` renders cleanly (zero `OvCrashScreen`),
 *   5. Live `.onion` navigation (`https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/`)
 *      binds per-service circuit isolation (`iso-duckduckgogg42xj`) and renders without error,
 *   6. Mandatory 3-round OpenPGP TOTP `.onion` Prize Gate (`LiveOnionPgpTotpPrizeTest`):
 *      cold-boots `MainActivity`, generates a Sequoia + Keystore OpenPGP keypair, connects over
 *      Tor to our DigitalOcean OpenPGP TOTP `.onion` hidden service, inserts `"Insert My PubKey"`,
 *      decrypts all 3 OpenPGP TOTP challenges via `DecryptedPlaintextHardwarePod`, submits all 3
 *      HTML form POSTs over Tor, wins Round 3 (`OK (1 test)` + `TrapPrizeGate: PRIZE_WON_JSON=...`),
 *      and captures `limrun-prize.png` while the decrypted prize certificate is displayed,
 *   7. Zero Java `FATAL EXCEPTION`s, ANRs, native signals/tombstones, DropBox crashes,
 *      `CrashReporter` files, or JNI/DEX linkage errors,
 *   8. Captures `limrun-launch.png`, `limrun-onion.png`, `limrun-prize.png`, `limrun-logcat.txt`,
 *      `limrun-ui-tree.xml`, and `limrun-summary.json`, and always deletes the cloud Android
 *      instance in `finally` / `SIGINT` / `SIGTERM`.
 *
 * Fails closed immediately if `LIM_API_KEY` (or `LIMRUN_API_KEY`) is missing or empty, or if
 * Round 3's OpenPGP TOTP prize is not won and verified.
 */

import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(SCRIPT_DIR, '..');

const DEFAULT_APK = path.join(REPO_ROOT, 'android/app/build/outputs/apk/debug/app-debug.apk');
const DEFAULT_TEST_APK = path.join(
  REPO_ROOT,
  'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
);
const DEFAULT_OUT_DIR = path.join(REPO_ROOT, 'dist/limrun');

const PACKAGE = process.env.PACKAGE || 'dev.onionvault';
const TEST_PACKAGE = process.env.TEST_PACKAGE || 'dev.onionvault.test';
const TEST_RUNNER = process.env.TEST_RUNNER || 'androidx.test.runner.AndroidJUnitRunner';
const PRIZE_TEST_CLASS =
  process.env.PRIZE_TEST_CLASS || 'dev.onionvault.LiveOnionPgpTotpPrizeTest';
const ACTIVITY = process.env.ACTIVITY || 'dev.onionvault/.MainActivity';
const DEFAULT_TOTP_ONION_URL =
  'http://b36xrf3dwtizfea3z6pl2i2el2vzraju4gdddben5eqpicdswuvfgoyd.onion/';
const TOTP_ONION_URL = (process.env.TOTP_ONION_URL || DEFAULT_TOTP_ONION_URL).trim();

const ONION_TEST_URL =
  process.env.ONION_TEST_URL ||
  'https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/';
const ONION_EXPECTED_ISOLATION =
  process.env.ONION_EXPECTED_ISOLATION || 'iso-duckduckgogg42xj';
const ONION_EXPECTED_HOST =
  process.env.ONION_EXPECTED_HOST ||
  'duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion';

const LIMRUN_OS_VERSION = process.env.LIMRUN_OS_VERSION || '15';
const LIMRUN_MODEL = process.env.LIMRUN_MODEL || 'phone';
const LIMRUN_JURISDICTION = (process.env.LIMRUN_JURISDICTION || '').trim() || undefined;
const LIMRUN_HARD_TIMEOUT = process.env.LIMRUN_HARD_TIMEOUT || '25m';
const LIMRUN_INACTIVITY_TIMEOUT = process.env.LIMRUN_INACTIVITY_TIMEOUT || '10m';
const LIMRUN_INSTALL_MODE = (process.env.LIMRUN_INSTALL_MODE || 'auto').trim();
const INSTANCE_ID_FILE = (process.env.LIMRUN_INSTANCE_ID_FILE || '').trim() || null;

const BOOT_WAIT_SECONDS = Number(process.env.BOOT_WAIT_SECONDS ?? 150);
const ONION_WAIT_SECONDS = Number(process.env.ONION_WAIT_SECONDS ?? 240);
const PRIZE_WAIT_SECONDS = Number(process.env.PRIZE_WAIT_SECONDS ?? 360);
const MAX_ONION_ATTEMPTS = Number(process.env.MAX_ONION_ATTEMPTS ?? 4);
const STABILITY_SECONDS = Number(process.env.STABILITY_SECONDS ?? 8);
const POLL_INTERVAL_MS = Number(process.env.POLL_INTERVAL_MS ?? 2000);
const SKIP_ONION_SMOKE = process.env.LIMRUN_SKIP_ONION_SMOKE === '1';

const DEVICE_PRIZE_SCREENSHOT_PATH = '/data/local/tmp/trap-prize-won.png';
const VALID_V3_ONION_URL_RE = /^https?:\/\/[a-z2-7]{56}\.onion(?:\/.*)?$/i;

const PNG_MAGIC = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const ZIP_MAGIC = Buffer.from([0x50, 0x4b, 0x03, 0x04]);

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function sanitizeLabelValue(raw) {
  return String(raw || '')
    .toLowerCase()
    .replace(/[^a-z0-9_.-]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 63);
}

function failClosedError(msg) {
  console.error(`::error::${msg}`);
}

function loadLimrunSdk() {
  const candidates = [];
  if (process.env.LIMRUN_TOOLS_DIR) {
    candidates.push(path.join(path.resolve(process.env.LIMRUN_TOOLS_DIR), 'package.json'));
  }
  candidates.push(fileURLToPath(import.meta.url));

  let lastErr = null;
  for (const anchor of candidates) {
    try {
      const req = createRequire(anchor);
      const sdk = req('@limrun/api');
      const Limrun = sdk.Limrun || sdk.default;
      const createInstanceClient = sdk.createInstanceClient;
      if (typeof Limrun === 'function' && typeof createInstanceClient === 'function') {
        return { Limrun, createInstanceClient };
      }
    } catch (err) {
      lastErr = err;
    }
  }
  throw new Error(
    `Could not load @limrun/api (set LIMRUN_TOOLS_DIR or install @limrun/api): ${
      lastErr instanceof Error ? lastErr.message : String(lastErr)
    }`,
  );
}

function resolveTestApkPath(apkPath, explicitArg) {
  const explicit = String(explicitArg || process.env.TEST_APK_PATH || '').trim();
  if (explicit) {
    return path.resolve(explicit);
  }
  const candidates = [
    path.join(path.dirname(apkPath), 'app-debug-androidTest.apk'),
    path.resolve(path.dirname(apkPath), '../androidTest/debug/app-debug-androidTest.apk'),
    DEFAULT_TEST_APK,
  ];
  for (const candidate of candidates) {
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }
  return candidates[0];
}

function assertValidApk(apkPath) {
  let st;
  try {
    st = fs.statSync(apkPath);
  } catch {
    throw new Error(`APK file does not exist: ${apkPath}`);
  }
  if (!st.isFile() || st.size < 4) {
    throw new Error(`APK file is empty or invalid: ${apkPath}`);
  }
  const fd = fs.openSync(apkPath, 'r');
  try {
    const header = Buffer.alloc(4);
    fs.readSync(fd, header, 0, 4, 0);
    if (!header.equals(ZIP_MAGIC)) {
      throw new Error(`File is not a valid APK/ZIP archive: ${apkPath}`);
    }
  } finally {
    fs.closeSync(fd);
  }
  return st.size;
}

function assertValidPngBuffer(buf, label = 'screenshot') {
  if (
    !Buffer.isBuffer(buf) ||
    buf.length < PNG_MAGIC.length ||
    !buf.subarray(0, PNG_MAGIC.length).equals(PNG_MAGIC)
  ) {
    throw new Error(`Decoded ${label} is not a valid non-empty PNG (${buf?.length ?? 0} bytes)`);
  }
  return buf;
}

function decodeScreenshotDataUri(dataUri) {
  if (typeof dataUri !== 'string' || !dataUri.startsWith('data:image/')) {
    throw new Error('Limrun screenshot response did not contain a valid image dataUri');
  }
  const commaIdx = dataUri.indexOf(',');
  if (commaIdx < 0) {
    throw new Error('Malformed dataUri in Limrun screenshot response');
  }
  const buf = Buffer.from(dataUri.slice(commaIdx + 1), 'base64');
  return assertValidPngBuffer(buf, 'screenshot');
}

async function saveScreenshot(client, targetPath) {
  const shot = await client.screenshot();
  const pngBytes = decodeScreenshotDataUri(shot.dataUri);
  fs.mkdirSync(path.dirname(targetPath), { recursive: true });
  fs.writeFileSync(targetPath, pngBytes);
  return pngBytes.length;
}

async function tryPullDevicePngViaBase64(client, remotePath, targetPath) {
  const res = await client
    .adbShell(
      'sh',
      [
        '-c',
        `if run-as ${PACKAGE} test -s files/prize-won.png 2>/dev/null; then run-as ${PACKAGE} base64 files/prize-won.png | tr -d '\\r\\n '; elif [ -s "${remotePath}" ]; then base64 "${remotePath}" | tr -d '\\r\\n '; fi`,
      ],
      { timeoutMs: 25_000 },
    )
    .catch(() => ({ stdout: '', exitCode: 1 }));
  const b64 = String(res.stdout || '').trim();
  if (!b64) {
    return null;
  }
  try {
    const buf = assertValidPngBuffer(Buffer.from(b64, 'base64'), remotePath);
    fs.mkdirSync(path.dirname(targetPath), { recursive: true });
    fs.writeFileSync(targetPath, buf);
    return buf.length;
  } catch {
    return null;
  }
}

async function installApkOnInstance({ limrun, client, apkFilePath, expectedPackage }) {
  let method = null;
  if (LIMRUN_INSTALL_MODE !== 'asset') {
    try {
      await client.syncApp(apkFilePath, {
        install: true,
        watch: false,
        launchMode: 'None',
      });
      method = 'syncApp';
    } catch (syncErr) {
      if (LIMRUN_INSTALL_MODE === 'sync') {
        throw syncErr;
      }
      console.warn(
        `  syncApp fallback to assets.getOrUpload + sendAsset for ${path.basename(apkFilePath)}: ${
          syncErr instanceof Error ? syncErr.message : String(syncErr)
        }`,
      );
    }
  }
  if (!method) {
    const asset = await limrun.assets.getOrUpload({
      path: apkFilePath,
      name: path.basename(apkFilePath),
      ttl: '2h',
    });
    await client.sendAsset(asset.signedDownloadUrl, 300_000);
    method = 'sendAsset';
  }

  const installedPkg = await client.adbShell('pm', ['path', expectedPackage]);
  if (installedPkg.exitCode !== 0 || !installedPkg.stdout.trim().startsWith('package:')) {
    throw new Error(
      `Package ${expectedPackage} was not installed after ${method}: ${installedPkg.stdout} ${installedPkg.stderr}`,
    );
  }
  return method;
}

function extractLatestPrizeWonPayload(logText) {
  if (!logText) return null;
  const matches = [...logText.matchAll(/TrapPrizeGate\s*:[^\n]*?PRIZE_WON_JSON=(\{[^\r\n]+\})/g)];
  if (matches.length === 0) return null;
  const rawJson = matches[matches.length - 1][1];
  try {
    return JSON.parse(rawJson);
  } catch (err) {
    return {
      __parseError: err instanceof Error ? err.message : String(err),
      __raw: rawJson,
    };
  }
}

function detectFatalLogIssues(logText) {
  const issues = [];
  if (/FATAL EXCEPTION/.test(logText)) {
    issues.push('Java FATAL EXCEPTION detected in logcat');
  }
  if (new RegExp(`ANR in ${PACKAGE.replace('.', '\\.')}`).test(logText)) {
    issues.push(`Application Not Responding (ANR in ${PACKAGE}) detected in logcat`);
  }
  // Catch ALL fatal signals across browser, :sandboxed_process* renderer, and
  // :privileged_process* GPU processes — including Fatal signal 5 (SIGTRAP)
  // emitted by Chromium IMMEDIATE_CRASH()/CHECK() (e.g. ARMv9 MTE brk #0) and
  // Fatal signal 31 (SIGSYS) from seccomp.
  if (/Fatal signal \d+\b|\*\*\* \*\*\* \*\*\*/.test(logText)) {
    issues.push('Native fatal signal / tombstone header detected in logcat');
  }
  if (
    /SetHeapTaggingLevel:\s*set_tcf_on_all_threads failed|PartitionAlloc:\s*Failed to enable memory tagging/.test(
      logText,
    )
  ) {
    issues.push(
      'ARMv9 MTE / PartitionAlloc memory-tagging fault detected in logcat (missing android:memtagMode="off" or PartitionAllocMemoryTagging disable)',
    );
  }
  if (/UnsatisfiedLinkError|ClassNotFoundException|NoSuchMethodError|NoSuchFieldError/.test(logText)) {
    issues.push(
      'JNI/DEX linkage error (UnsatisfiedLinkError / ClassNotFoundException / NoSuchMethodError / NoSuchFieldError) detected',
    );
  }
  if (
    /Trap startup failed|Cefpack initialization failed|Failed to initialize Cefpack content engine|Failed to load libonion_vault\.so/.test(
      logText,
    )
  ) {
    issues.push('TrapApplication / MainActivity logged a fatal Cefpack engine or native startup error');
  }
  return issues;
}

function detectCrashScreenInTree(tree) {
  const combinedText = [
    tree?.xml || '',
    ...(Array.isArray(tree?.nodes)
      ? tree.nodes.map((n) => `${n.text || ''} ${n.contentDesc || ''}`)
      : []),
  ].join('\n');

  if (/Trap Crash/i.test(combinedText)) {
    return true;
  }
  if (/Try again/i.test(combinedText) && /Copy(?:\s+crash|\s+trace)?/i.test(combinedText)) {
    return true;
  }
  return false;
}

function hasStaleAboutBlankTitle(tree) {
  if (
    Array.isArray(tree?.nodes) &&
    tree.nodes.some((n) => String(n.text || '').trim().toLowerCase() === 'about:blank')
  ) {
    return true;
  }
  return /\btext="about:blank"/i.test(tree?.xml || '');
}

function isStillLoadingInUi(tree) {
  if (
    Array.isArray(tree?.nodes) &&
    tree.nodes.some((n) => String(n.contentDesc || '').includes('Stop loading'))
  ) {
    return true;
  }
  return /content-desc="[^"]*Stop loading[^"]*"/i.test(tree?.xml || '');
}

function truncateOnionPreservingTail(host) {
  const clean = String(host || '')
    .trim()
    .toLowerCase()
    .replace(/^www\./, '');
  if (!clean.endsWith('.onion')) return clean;
  const stem = clean.slice(0, -6);
  if (stem.length <= 15) return `${stem}.onion`;
  return `${stem.slice(0, 8)}…${stem.slice(-5)}.onion`;
}

function hasOnionHostInChrome(tree, expectedHost) {
  const truncatedHost = truncateOnionPreservingTail(expectedHost);
  const xml = String(tree?.xml || '');
  if (/\btext="Enter address"/i.test(xml) || /\btext="search \/ \.onion"/i.test(xml)) {
    return false;
  }
  if (Array.isArray(tree?.nodes) && tree.nodes.length > 0) {
    if (
      tree.nodes.some((n) => {
        const t = String(n.text || '').trim();
        return t === 'Enter address' || t === 'search / .onion';
      })
    ) {
      return false;
    }
    return tree.nodes.some((n) => {
      const combined = `${n.text || ''} ${n.contentDesc || ''}`;
      return (
        (combined.includes(expectedHost) || combined.includes(truncatedHost)) &&
        !/Navigation failed/i.test(combined) &&
        !combined.includes('about:blank · zero-JS') &&
        !combined.includes('Address bar: search / .onion')
      );
    });
  }
  return (
    !xml.includes('about:blank · zero-JS') &&
    !xml.includes('Address bar: search / .onion') &&
    (xml.includes(expectedHost) || xml.includes(truncatedHost)) &&
    !/Navigation failed[^"]*duckduckgogg/i.test(xml)
  );
}

function extractLatestMainFrameHttpStatus(logBuffer, targetUrl) {
  if (!logBuffer) return null;
  const loadIdx = logBuffer.lastIndexOf(`loadUrl: ${targetUrl}`);
  const postNavLog = loadIdx >= 0 ? logBuffer.slice(loadIdx) : logBuffer;
  const matches = [
    ...postNavLog.matchAll(/Cefpack(?:Engine)?\s*:\s*OnLoadEnd:\s*status\s+([23]\d\d),\s*isMain=1/g),
  ];
  if (matches.length === 0) return null;
  return Number(matches[matches.length - 1][1]);
}

async function main() {
  const apkPath = path.resolve(process.argv[2] || DEFAULT_APK);
  const outDir = path.resolve(process.argv[3] || DEFAULT_OUT_DIR);
  const testApkPath = resolveTestApkPath(apkPath, process.argv[4]);

  const apiKey = (process.env.LIM_API_KEY || process.env.LIMRUN_API_KEY || '').trim();
  if (!apiKey) {
    failClosedError(
      'Missing Limrun API key: neither LIM_API_KEY nor LIMRUN_API_KEY is set. ' +
        'Configure repository secret LIM_API_KEY in GitHub Settings -> Secrets and variables -> Actions. ' +
        'Failing closed so no unverified APK can reach GitHub Releases.',
    );
    process.exit(1);
  }
  process.env.LIM_API_KEY = apiKey;

  if (!['14', '15'].includes(LIMRUN_OS_VERSION)) {
    failClosedError(`Unsupported LIMRUN_OS_VERSION='${LIMRUN_OS_VERSION}' (Limrun supports '14' or '15')`);
    process.exit(1);
  }
  if (!['phone', 'tablet'].includes(LIMRUN_MODEL)) {
    failClosedError(`Unsupported LIMRUN_MODEL='${LIMRUN_MODEL}' (Limrun supports 'phone' or 'tablet')`);
    process.exit(1);
  }

  let apkBytes;
  let testApkBytes;
  try {
    apkBytes = assertValidApk(apkPath);
    testApkBytes = assertValidApk(testApkPath);
  } catch (err) {
    failClosedError(err instanceof Error ? err.message : String(err));
    process.exit(1);
  }

  fs.mkdirSync(outDir, { recursive: true });

  const { Limrun, createInstanceClient } = loadLimrunSdk();
  const limrun = new Limrun({
    apiKey,
    ...(process.env.LIMRUN_BASE_URL ? { baseURL: process.env.LIMRUN_BASE_URL } : {}),
  });

  let instanceId = null;
  let instanceDeleted = false;
  let client = null;
  let logcatProc = null;
  let suppressExitWatchForInstrumentation = false;

  const deleteInstanceSafely = async (reason) => {
    if (!instanceId || instanceDeleted) return;
    instanceDeleted = true;
    try {
      logcatProc?.stop();
    } catch {}
    try {
      client?.disconnect();
    } catch {}
    try {
      console.log(`== [cleanup] Deleting Limrun Android instance ${instanceId} (${reason}) ==`);
      await limrun.androidInstances.delete(instanceId);
      if (INSTANCE_ID_FILE && fs.existsSync(INSTANCE_ID_FILE)) {
        fs.unlinkSync(INSTANCE_ID_FILE);
      }
      console.log(`== [cleanup] Deleted Limrun Android instance ${instanceId} ==`);
    } catch (err) {
      console.warn(
        `::warning::Failed to delete Limrun instance ${instanceId}: ${
          err instanceof Error ? err.message : String(err)
        }`,
      );
    }
  };

  const onSignal = (sig) => {
    void (async () => {
      await deleteInstanceSafely(`signal ${sig}`);
      process.exit(sig === 'SIGINT' ? 130 : 143);
    })();
  };
  process.once('SIGINT', onSignal);
  process.once('SIGTERM', onSignal);

  const summary = {
    startedAt: new Date().toISOString(),
    apkPath,
    apkBytes,
    testApkPath,
    testApkBytes,
    package: PACKAGE,
    testPackage: TEST_PACKAGE,
    activity: ACTIVITY,
    requestedSpec: {
      osVersion: LIMRUN_OS_VERSION,
      model: LIMRUN_MODEL,
      jurisdiction: LIMRUN_JURISDICTION || null,
      hardTimeout: LIMRUN_HARD_TIMEOUT,
      inactivityTimeout: LIMRUN_INACTIVITY_TIMEOUT,
    },
    instance: null,
    deviceProps: {},
    installMethod: null,
    testInstallMethod: null,
    readiness: {
      engineReady: false,
      socksEndpoint: null,
      torBootstrapped: false,
      bootElapsedSeconds: null,
    },
    onionSmoke: {
      skipped: SKIP_ONION_SMOKE,
      targetUrl: ONION_TEST_URL,
      expectedIsolation: ONION_EXPECTED_ISOLATION,
      triggerMethod: null,
      attempts: 1,
      isolationCreatedInLogcat: false,
      isolationVisibleInUi: false,
      onionHostVisibleInUi: false,
      pageLoadedInLogcat: false,
      mainFrameHttpStatus: null,
      onionTitleRenderedInUi: false,
      elapsedSeconds: null,
    },
    totpPrize: {
      testClass: PRIZE_TEST_CLASS,
      runner: `${TEST_PACKAGE}/${TEST_RUNNER}`,
      instrumentationPassed: false,
      onionUrl: null,
      roundsCompleted: 0,
      winnerFingerprint: null,
      prizeBanner: null,
      decryptedCertificate: null,
      elapsedSeconds: null,
      passed: false,
    },
    appPid: null,
    unexpectedExit: null,
    artifacts: {},
    failures: [],
    passed: false,
  };

  const streamedLogLines = [];
  let latestDumpLogText = '';
  const combinedLogText = () => {
    const streamed = streamedLogLines.join('\n');
    if (streamed && latestDumpLogText) {
      return `${streamed}\n${latestDumpLogText}`;
    }
    return streamed || latestDumpLogText;
  };

  const handleUnexpectedAppExit = (logs, info) => {
    if (suppressExitWatchForInstrumentation) {
      return;
    }
    summary.unexpectedExit = {
      at: new Date().toISOString(),
      info,
      recentLogs: Array.isArray(logs) ? logs.slice(-80) : [],
    };
    console.error(
      `::error::${PACKAGE} exited unexpectedly during gate (reason=${info?.reason}): ` +
        `${info?.crash?.shortMsg || info?.anr?.processName || 'process exited'}`,
    );
  };

  try {
    const labels = {
      app: 'trap',
      gate: 'limrun-device-gate',
    };
    if (process.env.GITHUB_REPOSITORY) {
      const v = sanitizeLabelValue(process.env.GITHUB_REPOSITORY);
      if (v) labels.repo = v;
    }
    if (process.env.GITHUB_SHA) {
      const v = sanitizeLabelValue(process.env.GITHUB_SHA.slice(0, 12));
      if (v) labels.sha = v;
    }
    if (process.env.GITHUB_RUN_ID) {
      const runTag = sanitizeLabelValue(
        `${process.env.GITHUB_RUN_ID}-${process.env.GITHUB_RUN_ATTEMPT || '1'}`,
      );
      if (runTag) labels.ci_run = runTag;
    }

    const displayName = process.env.GITHUB_RUN_ID
      ? `trap-ci-${process.env.GITHUB_RUN_ID}-${process.env.GITHUB_RUN_ATTEMPT || '1'}`
      : `trap-gate-${Date.now()}`;

    console.log(
      `== [1/8] Creating Limrun cloud Android instance (Android ${LIMRUN_OS_VERSION}, model=${LIMRUN_MODEL}) ==`,
    );
    const createStarted = Date.now();
    const instance = await limrun.androidInstances.create({
      wait: true,
      reuseIfExists: false,
      metadata: {
        displayName,
        labels,
      },
      spec: {
        model: LIMRUN_MODEL,
        clues: [{ kind: 'OSVersion', osVersion: LIMRUN_OS_VERSION }],
        hardTimeout: LIMRUN_HARD_TIMEOUT,
        inactivityTimeout: LIMRUN_INACTIVITY_TIMEOUT,
        ...(LIMRUN_JURISDICTION ? { jurisdiction: LIMRUN_JURISDICTION } : {}),
      },
    });

    instanceId = instance?.metadata?.id || null;
    if (!instanceId) {
      throw new Error('Limrun androidInstances.create returned an instance without metadata.id');
    }
    if (INSTANCE_ID_FILE) {
      fs.writeFileSync(INSTANCE_ID_FILE, `${instanceId}\n`, 'utf8');
    }

    const apiUrl = instance?.status?.apiUrl;
    const token = instance?.status?.token;
    if (!apiUrl || !token) {
      throw new Error(
        `Limrun instance ${instanceId} is missing status.apiUrl or status.token (state=${instance?.status?.state})`,
      );
    }

    summary.instance = {
      id: instanceId,
      region: instance?.spec?.region || null,
      state: instance?.status?.state || null,
      createSeconds: Number(((Date.now() - createStarted) / 1000).toFixed(2)),
    };
    console.log(
      `  Provisioned ${instanceId} in region=${summary.instance.region || 'auto'} (${summary.instance.createSeconds}s)`,
    );

    console.log('== [2/8] Connecting InstanceClient & inspecting cloud Android device ==');
    client = await createInstanceClient({
      apiUrl,
      adbUrl: instance?.status?.adbWebSocketUrl,
      token,
      logLevel: 'warn',
    });

    for (let attempt = 0; attempt < 60; attempt += 1) {
      const bootRes = await client.adbShell('getprop', ['sys.boot_completed']);
      const pmRes = await client.adbShell('pm', ['path', 'android']);
      if (bootRes.stdout.trim() === '1' && pmRes.stdout.trim().startsWith('package:')) {
        break;
      }
      await sleep(2000);
    }

    const propCommands = {
      abi: ['getprop', ['ro.product.cpu.abi']],
      abilist: ['getprop', ['ro.product.cpu.abilist']],
      sdk: ['getprop', ['ro.build.version.sdk']],
      release: ['getprop', ['ro.build.version.release']],
      deviceModel: ['getprop', ['ro.product.model']],
      fingerprint: ['getprop', ['ro.build.fingerprint']],
      pageSize: ['getconf', ['PAGESIZE']],
    };
    for (const [key, [cmd, args]] of Object.entries(propCommands)) {
      const res = await client.adbShell(cmd, args).catch(() => ({ stdout: '' }));
      summary.deviceProps[key] = res.stdout.trim();
    }
    console.log(
      `  Device: model=${summary.deviceProps.deviceModel} Android ${summary.deviceProps.release} ` +
        `(API ${summary.deviceProps.sdk}) abi=${summary.deviceProps.abi} ` +
        `abilist=[${summary.deviceProps.abilist}] pageSize=${summary.deviceProps.pageSize}`,
    );

    await client.adbShell('input', ['keyevent', 'KEYCODE_WAKEUP']).catch(() => {});
    await client.adbShell('wm', ['dismiss-keyguard']).catch(() => {});

    console.log(
      `== [3/8] Syncing & installing ${path.basename(apkPath)} (${(apkBytes / (1024 * 1024)).toFixed(2)} MiB) + ${path.basename(testApkPath)} (${(testApkBytes / (1024 * 1024)).toFixed(2)} MiB) ==`,
    );
    const installStarted = Date.now();
    summary.installMethod = await installApkOnInstance({
      limrun,
      client,
      apkFilePath: apkPath,
      expectedPackage: PACKAGE,
    });
    summary.testInstallMethod = await installApkOnInstance({
      limrun,
      client,
      apkFilePath: testApkPath,
      expectedPackage: TEST_PACKAGE,
    });
    console.log(
      `  Installed ${PACKAGE} (${summary.installMethod}) and ${TEST_PACKAGE} (${summary.testInstallMethod}) in ${((Date.now() - installStarted) / 1000).toFixed(1)}s`,
    );

    await client
      .adbShell('sh', [
        '-c',
        `run-as ${PACKAGE} rm -f files/crash-java.txt files/crash-native.txt 2>/dev/null || true`,
      ])
      .catch(() => {});
    await client.adbShell('logcat', ['-G', '16M']).catch(() => {});
    await client.adbShell('logcat', ['-c']).catch(() => {});

    try {
      logcatProc = client.logcat(['-v', 'threadtime']);
      logcatProc.on('line', (line) => {
        streamedLogLines.push(line);
      });
      logcatProc.on('error', (err) => {
        console.warn(`  [logcat stream warning] ${err.message}`);
      });
    } catch (err) {
      console.warn(
        `  [logcat stream unavailable, using adbShell logcat -d] ${
          err instanceof Error ? err.message : String(err)
        }`,
      );
    }

    console.log(`== [4/8] Launching ${PACKAGE} under ActivityManager crash/ANR watch ==`);
    await client.launchApp(PACKAGE, {
      mode: 'RelaunchIfRunning',
      onExit: handleUnexpectedAppExit,
    });

    console.log(
      `== [5/8] Waiting up to ${BOOT_WAIT_SECONDS}s for Cefpack engine + ov_tor SOCKS5 + Arti Tor bootstrap ==`,
    );
    const bootLoopStarted = Date.now();
    while ((Date.now() - bootLoopStarted) / 1000 < BOOT_WAIT_SECONDS) {
      if (summary.unexpectedExit) {
        break;
      }

      const dumpRes = await client
        .adbShell('logcat', ['-d', '-v', 'threadtime'], { timeoutMs: 20_000 })
        .catch(() => ({ stdout: '' }));
      if (dumpRes.stdout) {
        latestDumpLogText = dumpRes.stdout;
      }

      const logText = combinedLogText();
      if (/the engine is ready|Initialized Cefpack content engine/.test(logText)) {
        summary.readiness.engineReady = true;
      }
      const socksMatch = logText.match(
        /(?:ov_tor:\s*SOCKS5 listener bound to|Tor SOCKS5 listener bound on|SOCKS5 listener bound (?:to|on))\s+(127\.0\.0\.1:\d+)/,
      );
      if (socksMatch) {
        summary.readiness.socksEndpoint = socksMatch[1];
      }
      if (/Tor client bootstrapped/.test(logText)) {
        summary.readiness.torBootstrapped = true;
      }

      if (
        !summary.readiness.torBootstrapped &&
        summary.readiness.engineReady &&
        summary.readiness.socksEndpoint
      ) {
        const treeProbe = await client
          .getElementTree({ waitForIdleTimeoutMs: 1000 })
          .catch(() => null);
        if (treeProbe && /\bTor ready\b/.test(treeProbe.xml || '')) {
          summary.readiness.torBootstrapped = true;
        }
      }

      const fatalNow = detectFatalLogIssues(logText);
      if (fatalNow.length > 0) {
        break;
      }

      if (
        summary.readiness.engineReady &&
        summary.readiness.socksEndpoint &&
        summary.readiness.torBootstrapped
      ) {
        summary.readiness.bootElapsedSeconds = Number(
          ((Date.now() - bootLoopStarted) / 1000).toFixed(1),
        );
        break;
      }

      await sleep(POLL_INTERVAL_MS);
    }

    const launchScreenshotPath = path.join(outDir, 'limrun-launch.png');
    try {
      const bytes = await saveScreenshot(client, launchScreenshotPath);
      summary.artifacts.launchScreenshot = launchScreenshotPath;
      console.log(`  Captured launch screenshot: ${launchScreenshotPath} (${bytes} bytes)`);
    } catch (err) {
      summary.failures.push(
        `Failed to capture launch screenshot: ${err instanceof Error ? err.message : String(err)}`,
      );
    }

    const startTree = await client
      .getElementTree({ waitForIdleTimeoutMs: 2500 })
      .catch(() => null);
    if (startTree) {
      if (detectCrashScreenInTree(startTree)) {
        summary.failures.push('OvCrashScreen was rendered on startup instead of BrowserScreen');
      }
      const hasPackageNode =
        (startTree.nodes || []).some((n) => n.packageName === PACKAGE) ||
        (startTree.xml || '').includes(PACKAGE);
      if (!hasPackageNode && !summary.unexpectedExit) {
        summary.failures.push(`UIAutomator hierarchy does not show ${PACKAGE} in foreground after launch`);
      }
      if (/Trap Start|about:blank\s*·\s*zero-JS|DuckDuckGo \(\.onion v3\)/i.test(startTree.xml || '')) {
        summary.failures.push(
          'Legacy top header or multi-card StartPage clutter detected instead of 1-row bottom chrome',
        );
      }
    } else {
      summary.failures.push('Failed to inspect UIAutomator element tree after launch');
    }

    if (!summary.readiness.engineReady) {
      summary.failures.push("Cefpack never logged 'Initialized Cefpack content engine' / 'the engine is ready'");
    }
    if (!summary.readiness.socksEndpoint) {
      summary.failures.push('ov_tor never bound its loopback 127.0.0.1:<port> SOCKS5 proxy');
    }
    if (!summary.readiness.torBootstrapped) {
      summary.failures.push("Arti never completed live Tor bootstrap ('Tor client bootstrapped' / 'Tor ready')");
    }

    if (
      !SKIP_ONION_SMOKE &&
      summary.failures.length === 0 &&
      !summary.unexpectedExit
    ) {
      console.log(`== [6/8] Navigating live over Tor to ${ONION_TEST_URL} ==`);
      const onionStarted = Date.now();

      let tappedCard = false;
      if (startTree && Array.isArray(startTree.nodes)) {
        const ddgNode = startTree.nodes.find((n) => {
          const txt = String(n.text || '').trim();
          return txt === 'DuckDuckGo' || txt.includes('DuckDuckGo (.onion v3)');
        });
        if (ddgNode?.parsedBounds) {
          try {
            await client.tap({
              x: ddgNode.parsedBounds.centerX,
              y: ddgNode.parsedBounds.centerY,
            });
            tappedCard = true;
            summary.onionSmoke.triggerMethod = 'ui-tap-start-directory';
          } catch {}
        }
      }

      if (!tappedCard) {
        const amRes = await client.adbShell('am', [
          'start',
          '-W',
          '-a',
          'android.intent.action.VIEW',
          '-d',
          ONION_TEST_URL,
          '-n',
          ACTIVITY,
        ]);
        if (amRes.exitCode !== 0) {
          summary.failures.push(
            `Failed to start ACTION_VIEW intent for ${ONION_TEST_URL}: ${amRes.stdout} ${amRes.stderr}`,
          );
        }
        summary.onionSmoke.triggerMethod = 'am-start-action-view';
      }

      let finalTree = null;
      while ((Date.now() - onionStarted) / 1000 < ONION_WAIT_SECONDS) {
        if (summary.unexpectedExit) {
          break;
        }
        const dumpRes = await client
          .adbShell('logcat', ['-d', '-v', 'threadtime'], { timeoutMs: 20_000 })
          .catch(() => ({ stdout: '' }));
        if (dumpRes.stdout) {
          latestDumpLogText = dumpRes.stdout;
        }
        const logText = combinedLogText();
        if (detectFatalLogIssues(logText).length > 0) {
          break;
        }

        if (logText.includes(`created isolated Tor circuit group ${ONION_EXPECTED_ISOLATION}`)) {
          summary.onionSmoke.isolationCreatedInLogcat = true;
        }

        const httpStatus =
          extractLatestMainFrameHttpStatus(latestDumpLogText, ONION_TEST_URL) ??
          extractLatestMainFrameHttpStatus(streamedLogLines.join('\n'), ONION_TEST_URL);
        if (httpStatus !== null) {
          summary.onionSmoke.pageLoadedInLogcat = true;
          summary.onionSmoke.mainFrameHttpStatus = httpStatus;
        }

        finalTree = await client.getElementTree({ waitForIdleTimeoutMs: 1500 }).catch(() => null);
        if (finalTree) {
          const allText = [
            finalTree.xml || '',
            ...(finalTree.nodes || []).map((n) => `${n.text || ''} ${n.contentDesc || ''}`),
          ].join('\n');

          if (allText.includes(ONION_EXPECTED_ISOLATION)) {
            summary.onionSmoke.isolationVisibleInUi = true;
          }
          summary.onionSmoke.onionHostVisibleInUi = hasOnionHostInChrome(
            finalTree,
            ONION_EXPECTED_HOST,
          );
          if (
            summary.onionSmoke.onionHostVisibleInUi &&
            !hasStaleAboutBlankTitle(finalTree) &&
            !isStillLoadingInUi(finalTree)
          ) {
            summary.onionSmoke.onionTitleRenderedInUi = true;
          } else {
            summary.onionSmoke.onionTitleRenderedInUi = false;
          }
          if (/Navigation failed/i.test(allText) && !summary.onionSmoke.pageLoadedInLogcat) {
            const elapsedSec = (Date.now() - onionStarted) / 1000;
            if (
              summary.onionSmoke.attempts < MAX_ONION_ATTEMPTS &&
              elapsedSec < ONION_WAIT_SECONDS - 25
            ) {
              summary.onionSmoke.attempts += 1;
              summary.onionSmoke.pageLoadedInLogcat = false;
              summary.onionSmoke.mainFrameHttpStatus = null;
              summary.onionSmoke.onionTitleRenderedInUi = false;
              summary.onionSmoke.onionHostVisibleInUi = false;
              console.warn(
                `  .onion rendezvous attempt ${summary.onionSmoke.attempts - 1} reported navigation error at ${elapsedSec.toFixed(1)}s; retrying live navigation (attempt ${summary.onionSmoke.attempts}/${MAX_ONION_ATTEMPTS})...`,
              );
              if (summary.onionSmoke.attempts === 3) {
                const shieldNode = (finalTree.nodes || []).find((n) => {
                  const desc = String(n.contentDesc || '');
                  return (
                    desc === 'Tor security status' ||
                    desc.startsWith('Tor ready. Tap for circuit telemetry')
                  );
                });
                if (shieldNode?.parsedBounds) {
                  await client
                    .tap({ x: shieldNode.parsedBounds.centerX, y: shieldNode.parsedBounds.centerY })
                    .catch(() => {});
                  await sleep(1200);
                  const secTree = await client
                    .getElementTree({ waitForIdleTimeoutMs: 1000 })
                    .catch(() => null);
                  const resetNode = (secTree?.nodes || []).find((n) => {
                    const txt = String(n.text || '');
                    return txt.includes('New Tor Identity') || txt.includes('New Identity');
                  });
                  if (resetNode?.parsedBounds) {
                    await client
                      .tap({ x: resetNode.parsedBounds.centerX, y: resetNode.parsedBounds.centerY })
                      .catch(() => {});
                    await sleep(600);
                    await client
                      .tap({ x: resetNode.parsedBounds.centerX, y: resetNode.parsedBounds.centerY })
                      .catch(() => {});
                    await sleep(1000);
                  }
                  const closeNode = (secTree?.nodes || []).find((n) => {
                    const desc = String(n.contentDesc || '');
                    const txt = String(n.text || '').trim();
                    return desc === 'Close sheet' || txt === '✕';
                  });
                  if (closeNode?.parsedBounds) {
                    await client
                      .tap({ x: closeNode.parsedBounds.centerX, y: closeNode.parsedBounds.centerY })
                      .catch(() => {});
                    await sleep(1000);
                  }
                }
              }
              const reloadNode =
                summary.onionSmoke.attempts !== 3
                  ? (finalTree.nodes || []).find((n) => String(n.contentDesc || '') === 'Reload')
                  : null;
              if (reloadNode?.parsedBounds) {
                await client
                  .tap({ x: reloadNode.parsedBounds.centerX, y: reloadNode.parsedBounds.centerY })
                  .catch(() => {});
              } else {
                await client
                  .adbShell('am', [
                    'start',
                    '-W',
                    '-a',
                    'android.intent.action.VIEW',
                    '-d',
                    ONION_TEST_URL,
                    '-n',
                    ACTIVITY,
                  ])
                  .catch(() => {});
              }
              await sleep(3000);
              continue;
            }
            summary.failures.push(`Browser reported navigation error while loading ${ONION_TEST_URL}`);
            break;
          }
        }

        if (
          (summary.onionSmoke.isolationCreatedInLogcat || summary.onionSmoke.isolationVisibleInUi) &&
          summary.onionSmoke.onionHostVisibleInUi &&
          summary.onionSmoke.pageLoadedInLogcat &&
          summary.onionSmoke.onionTitleRenderedInUi
        ) {
          summary.onionSmoke.elapsedSeconds = Number(
            ((Date.now() - onionStarted) / 1000).toFixed(1),
          );
          break;
        }

        await sleep(POLL_INTERVAL_MS);
      }

      if (
        !summary.onionSmoke.isolationCreatedInLogcat &&
        !summary.onionSmoke.isolationVisibleInUi
      ) {
        summary.failures.push(
          `Expected per-service Tor isolation group '${ONION_EXPECTED_ISOLATION}' was never established for ${ONION_TEST_URL}`,
        );
      }
      if (!summary.onionSmoke.onionHostVisibleInUi) {
        summary.failures.push(
          `Browser chrome never transitioned from StartPage to '${ONION_EXPECTED_HOST}'`,
        );
      }
      if (!summary.onionSmoke.pageLoadedInLogcat) {
        summary.failures.push(
          `Cefpack never completed HTTP 2xx/3xx main-frame OnLoadEnd for ${ONION_TEST_URL}`,
        );
      }
      if (!summary.onionSmoke.onionTitleRenderedInUi) {
        summary.failures.push(
          `Browser UI remained in loading / stale 'about:blank' state instead of finishing render for ${ONION_EXPECTED_HOST}`,
        );
      }

      if (STABILITY_SECONDS > 0 && !summary.unexpectedExit && summary.failures.length === 0) {
        console.log(`  Holding ${STABILITY_SECONDS}s on live .onion session to verify stability...`);
        await sleep(STABILITY_SECONDS * 1000);
      }

      const onionScreenshotPath = path.join(outDir, 'limrun-onion.png');
      try {
        const bytes = await saveScreenshot(client, onionScreenshotPath);
        summary.artifacts.onionScreenshot = onionScreenshotPath;
        console.log(`  Captured .onion screenshot: ${onionScreenshotPath} (${bytes} bytes)`);
      } catch (err) {
        summary.failures.push(
          `Failed to capture .onion screenshot: ${err instanceof Error ? err.message : String(err)}`,
        );
      }

      finalTree =
        (await client.getElementTree({ waitForIdleTimeoutMs: 2000 }).catch(() => null)) || finalTree;
      if (finalTree) {
        const uiXmlPath = path.join(outDir, 'limrun-ui-tree.xml');
        fs.writeFileSync(uiXmlPath, finalTree.xml || '', 'utf8');
        summary.artifacts.uiTreeXml = uiXmlPath;
        if (detectCrashScreenInTree(finalTree)) {
          summary.failures.push('OvCrashScreen appeared during .onion navigation');
        }
      }
    } else if (startTree) {
      const uiXmlPath = path.join(outDir, 'limrun-ui-tree.xml');
      fs.writeFileSync(uiXmlPath, startTree.xml || '', 'utf8');
      summary.artifacts.uiTreeXml = uiXmlPath;
    }

    if (summary.failures.length === 0 && !summary.unexpectedExit) {
      console.log(
        `== [7/8] Running mandatory 3-round OpenPGP TOTP .onion Prize Gate (${PRIZE_TEST_CLASS}) ==`,
      );
      const prizeStarted = Date.now();
      const prizeScreenshotPath = path.join(outDir, 'limrun-prize.png');

      // Android ActivityManager stops & restarts the target package around `am instrument`;
      // suppress the earlier launchApp exit listener while instrumentation owns the process.
      suppressExitWatchForInstrumentation = true;
      await client
        .adbShell('sh', ['-c', `run-as ${PACKAGE} rm -f files/prize-won.png 2>/dev/null || true`])
        .catch(() => {});
      await client.adbShell('rm', ['-f', DEVICE_PRIZE_SCREENSHOT_PATH]).catch(() => {});
      await client.adbShell('input', ['keyevent', 'KEYCODE_WAKEUP']).catch(() => {});
      await client.adbShell('wm', ['dismiss-keyguard']).catch(() => {});

      const instrumentArgs = ['instrument', '-w', '-r', '-e', 'class', PRIZE_TEST_CLASS];
      if (TOTP_ONION_URL) {
        instrumentArgs.push('-e', 'totpOnionUrl', TOTP_ONION_URL);
      }
      instrumentArgs.push(`${TEST_PACKAGE}/${TEST_RUNNER}`);

      let liveWindowShotCaptured = false;
      let instrumentDone = false;
      const liveShotWatcher = (async () => {
        while (!instrumentDone) {
          if (streamedLogLines.some((line) => line.includes('PRIZE_WON_JSON='))) {
            try {
              await saveScreenshot(client, prizeScreenshotPath);
              liveWindowShotCaptured = true;
            } catch {}
            return;
          }
          await sleep(450);
        }
      })();

      let instRes;
      try {
        instRes = await client.adbShell('am', instrumentArgs, {
          timeoutMs: PRIZE_WAIT_SECONDS * 1000,
        });
      } finally {
        instrumentDone = true;
        await liveShotWatcher.catch(() => {});
      }

      summary.totpPrize.elapsedSeconds = Number(
        ((Date.now() - prizeStarted) / 1000).toFixed(1),
      );

      const instCombined = `${instRes?.stdout || ''}\n${instRes?.stderr || ''}`;
      const instOk =
        instRes?.exitCode === 0 &&
        /\bOK \(\d+ tests?\)/.test(instCombined) &&
        !/FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed/i.test(
          instCombined,
        );
      summary.totpPrize.instrumentationPassed = instOk;
      if (!instOk) {
        summary.failures.push(
          `${PRIZE_TEST_CLASS} did not pass 'OK (1 test)' (exit=${instRes?.exitCode}): ${instCombined
            .trim()
            .slice(-600)}`,
        );
      }

      // Prefer the frame-accurate screencap written by LiveOnionPgpTotpPrizeTest while
      // DecryptedPlaintextHardwarePod is open on screen; otherwise use live/current screenshot.
      let prizePngBytes = await tryPullDevicePngViaBase64(
        client,
        DEVICE_PRIZE_SCREENSHOT_PATH,
        prizeScreenshotPath,
      );
      if (!prizePngBytes && liveWindowShotCaptured && fs.existsSync(prizeScreenshotPath)) {
        prizePngBytes = fs.statSync(prizeScreenshotPath).size;
      }
      if (!prizePngBytes) {
        try {
          prizePngBytes = await saveScreenshot(client, prizeScreenshotPath);
        } catch (shotErr) {
          summary.failures.push(
            `Failed to capture prize screenshot (${prizeScreenshotPath}): ${
              shotErr instanceof Error ? shotErr.message : String(shotErr)
            }`,
          );
        }
      }
      if (prizePngBytes) {
        summary.artifacts.prizeScreenshot = prizeScreenshotPath;
        console.log(
          `  Captured TOTP prize screenshot: ${prizeScreenshotPath} (${prizePngBytes} bytes)`,
        );
      }

      const postPrizeDump = await client
        .adbShell('logcat', ['-d', '-v', 'threadtime'], { timeoutMs: 25_000 })
        .catch(() => ({ stdout: '' }));
      if (postPrizeDump.stdout) {
        latestDumpLogText = postPrizeDump.stdout;
      }

      const prizePayload =
        extractLatestPrizeWonPayload(latestDumpLogText) ??
        extractLatestPrizeWonPayload(streamedLogLines.join('\n'));
      if (!prizePayload) {
        summary.failures.push(
          `Missing mandatory 'TrapPrizeGate: PRIZE_WON_JSON=...' logcat record from ${PRIZE_TEST_CLASS}`,
        );
      } else if (prizePayload.__parseError) {
        summary.failures.push(
          `Malformed PRIZE_WON_JSON emitted by ${PRIZE_TEST_CLASS}: ${prizePayload.__parseError}`,
        );
      } else {
        const wonUrl = String(prizePayload.onionUrl || '').trim();
        const rounds = Number(prizePayload.roundsCompleted ?? 0);
        const banner = String(prizePayload.prizeBanner || '').trim();
        const cert = String(prizePayload.decryptedCertificate || '').trim();
        const winnerFp = String(prizePayload.winnerFingerprint || '').trim() || null;

        summary.totpPrize.onionUrl = wonUrl || null;
        summary.totpPrize.roundsCompleted = rounds;
        summary.totpPrize.winnerFingerprint = winnerFp;
        summary.totpPrize.prizeBanner = banner || null;
        summary.totpPrize.decryptedCertificate = cert || null;

        if (!VALID_V3_ONION_URL_RE.test(wonUrl)) {
          summary.failures.push(
            `PRIZE_WON_JSON onionUrl is not a valid Tor v3 .onion URL: '${wonUrl}'`,
          );
        }
        if (rounds !== 3) {
          summary.failures.push(
            `PRIZE_WON_JSON roundsCompleted must equal 3 (got ${JSON.stringify(prizePayload.roundsCompleted)})`,
          );
        }
        if (!banner) {
          summary.failures.push('PRIZE_WON_JSON prizeBanner is empty');
        }
        if (!cert || (banner && !cert.includes(banner))) {
          summary.failures.push(
            'PRIZE_WON_JSON decryptedCertificate is empty or does not match won prizeBanner',
          );
        }

        if (
          instOk &&
          VALID_V3_ONION_URL_RE.test(wonUrl) &&
          rounds === 3 &&
          banner.length > 0 &&
          cert.includes(banner) &&
          Boolean(summary.artifacts.prizeScreenshot)
        ) {
          summary.totpPrize.passed = true;
          console.log('== [PRIZE WON] Trap completed 3/3 OpenPGP TOTP rounds over Tor! ==');
          console.log(`  Onion Service : ${wonUrl}`);
          console.log(`  Prize Banner  : ${banner}`);
          console.log(`  Certificate   : ${cert.replace(/\s*\n\s*/g, ' | ')}`);
          console.log(`  Screenshot    : ${prizeScreenshotPath}`);
        }
      }

      // ActivityManager force-stops the target app when `am instrument` finishes; relaunch
      // cleanly so final PID & live stability audit verifies the post-gate browser process.
      suppressExitWatchForInstrumentation = false;
      await client.launchApp(PACKAGE, {
        mode: 'RelaunchIfRunning',
        onExit: handleUnexpectedAppExit,
      });
      for (let waitAttempt = 0; waitAttempt < 10; waitAttempt += 1) {
        const probePid = await client.adbShell('pidof', [PACKAGE]).catch(() => ({ stdout: '' }));
        if (probePid.stdout.trim()) {
          break;
        }
        await sleep(500);
      }
    }

    console.log('== [8/8] Running final forensic crash, tombstone, DropBox & PID audit ==');
    try {
      logcatProc?.stop();
    } catch {}
    const finalDump = await client
      .adbShell('logcat', ['-d', '-v', 'threadtime'], { timeoutMs: 25_000 })
      .catch(() => ({ stdout: '' }));
    if (finalDump.stdout) {
      latestDumpLogText = finalDump.stdout;
    }

    const fullLog = combinedLogText();
    const logcatPath = path.join(outDir, 'limrun-logcat.txt');
    fs.writeFileSync(logcatPath, fullLog, 'utf8');
    summary.artifacts.logcat = logcatPath;

    for (const issue of detectFatalLogIssues(fullLog)) {
      if (!summary.failures.includes(issue)) {
        summary.failures.push(issue);
      }
    }

    if (summary.unexpectedExit) {
      const reason = summary.unexpectedExit.info?.reason || 'unknown';
      const detail =
        summary.unexpectedExit.info?.crash?.shortMsg ||
        summary.unexpectedExit.info?.anr?.processName ||
        'process terminated unexpectedly';
      summary.failures.push(`ActivityManager reported ${PACKAGE} exit (${reason}): ${detail}`);
    }

    const crashFilesRes = await client
      .adbShell('sh', ['-c', `run-as ${PACKAGE} ls files 2>/dev/null || true`])
      .catch(() => ({ stdout: '' }));
    if (/\bcrash/.test(crashFilesRes.stdout)) {
      const crashBody = await client
        .adbShell('sh', [
          '-c',
          `run-as ${PACKAGE} cat files/crash-java.txt files/crash-native.txt 2>/dev/null || true`,
        ])
        .catch(() => ({ stdout: '' }));
      summary.failures.push(
        `CrashReporter recorded uncaught crash file(s): ${crashBody.stdout.trim().slice(0, 400)}`,
      );
    }

    const dropboxRes = await client
      .adbShell('sh', [
        '-c',
        `dumpsys dropbox --print data_app_crash data_app_native_crash data_app_anr SYSTEM_TOMBSTONE 2>/dev/null | grep -C 5 "${PACKAGE}" || true`,
      ])
      .catch(() => ({ stdout: '' }));
    if (dropboxRes.stdout.trim()) {
      summary.failures.push(
        `Android DropBox recorded crash/tombstone/ANR for ${PACKAGE}: ${dropboxRes.stdout.trim().slice(0, 400)}`,
      );
    }

    const pidRes = await client.adbShell('pidof', [PACKAGE]).catch(() => ({ stdout: '' }));
    const livePid = pidRes.stdout.trim().split(/\s+/).filter(Boolean)[0] || null;
    summary.appPid = livePid;
    if (!livePid) {
      summary.failures.push(`${PACKAGE} process is no longer running at end of gate`);
    }
  } catch (err) {
    summary.failures.push(err instanceof Error ? err.message : String(err));
  } finally {
    summary.finishedAt = new Date().toISOString();
    summary.passed = summary.failures.length === 0;
    const summaryPath = path.join(outDir, 'limrun-summary.json');
    summary.artifacts.summaryJson = summaryPath;
    fs.writeFileSync(summaryPath, `${JSON.stringify(summary, null, 2)}\n`, 'utf8');

    await deleteInstanceSafely('gate completed');
    process.off('SIGINT', onSignal);
    process.off('SIGTERM', onSignal);
  }

  if (!summary.passed) {
    for (const failure of summary.failures) {
      failClosedError(failure);
    }
    if (summary.unexpectedExit?.info?.crash?.stackTrace) {
      console.error('--- ActivityManager Crash Stack Trace ---');
      console.error(summary.unexpectedExit.info.crash.stackTrace);
    }
    const tailLines = combinedLogText().split('\n').slice(-80).join('\n');
    if (tailLines.trim()) {
      console.error('--- Recent Limrun logcat tail ---');
      console.error(tailLines);
    }
    process.exit(1);
  }

  console.log(
    `PASS: ${PACKAGE} (pid ${summary.appPid}) verified on Limrun cloud Android ${summary.deviceProps.release} ` +
      `(${summary.deviceProps.abi}, pageSize=${summary.deviceProps.pageSize}): Cefpack ready, ` +
      `ov_tor SOCKS5=${summary.readiness.socksEndpoint}, Arti bootstrapped, ` +
      `${SKIP_ONION_SMOKE ? 'onion smoke skipped' : `isolated ${ONION_EXPECTED_ISOLATION} .onion loaded`}, ` +
      `3/3 OpenPGP TOTP rounds won (PRIZE="${summary.totpPrize.prizeBanner}"), ` +
      `zero crashes/tombstones/ANRs.`,
  );
  process.exit(0);
}

main().catch((err) => {
  failClosedError(err instanceof Error ? err.stack || err.message : String(err));
  process.exit(1);
});
