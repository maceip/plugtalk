#!/usr/bin/env node
/**
 * Limrun Cloud Android Runtime & UI Smoke Gate for Trap (`dev.onionvault`).
 *
 * Provisions an isolated cloud Android instance via the official `@limrun/api` SDK,
 * syncs & installs `app-debug.apk` (`syncApp` with `assets.getOrUpload` + `sendAsset`
 * fallback), launches `dev.onionvault` under a live ActivityManager `onExit` crash/ANR
 * watch, and verifies end-to-end:
 *   1. `TrapApplication` initializes Cefrium (`"the engine is ready"`),
 *   2. `ov_tor` binds its loopback SOCKS5 listener (`127.0.0.1:<port>`),
 *   3. Embedded Arti bootstraps against the live Tor network (`"Tor client bootstrapped"`
 *      / `"Tor ready"` in UI),
 *   4. Compose `StartPage` renders cleanly (zero `OvCrashScreen`),
 *   5. Live `.onion` navigation (`https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/`)
 *      binds per-service circuit isolation (`iso-duckduckgogg42xj`) and renders without error,
 *   6. Zero Java `FATAL EXCEPTION`s, ANRs, native signals/tombstones, DropBox crashes,
 *      `CrashReporter` files, or JNI/DEX linkage errors,
 *   7. Captures `limrun-launch.png`, `limrun-onion.png`, `limrun-logcat.txt`,
 *      `limrun-ui-tree.xml`, and `limrun-summary.json`,
 *   8. Always deletes the cloud Android instance in `finally` / `SIGINT` / `SIGTERM`.
 *
 * Fails closed immediately if `LIM_API_KEY` (or `LIMRUN_API_KEY`) is missing or empty.
 */

import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(SCRIPT_DIR, '..');

const DEFAULT_APK = path.join(REPO_ROOT, 'android/app/build/outputs/apk/debug/app-debug.apk');
const DEFAULT_OUT_DIR = path.join(REPO_ROOT, 'dist/limrun');

const PACKAGE = process.env.PACKAGE || 'dev.onionvault';
const ACTIVITY = process.env.ACTIVITY || 'dev.onionvault/.MainActivity';
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
const LIMRUN_HARD_TIMEOUT = process.env.LIMRUN_HARD_TIMEOUT || '20m';
const LIMRUN_INACTIVITY_TIMEOUT = process.env.LIMRUN_INACTIVITY_TIMEOUT || '10m';
const LIMRUN_INSTALL_MODE = (process.env.LIMRUN_INSTALL_MODE || 'auto').trim();
const INSTANCE_ID_FILE = (process.env.LIMRUN_INSTANCE_ID_FILE || '').trim() || null;

const BOOT_WAIT_SECONDS = Number(process.env.BOOT_WAIT_SECONDS ?? 150);
const ONION_WAIT_SECONDS = Number(process.env.ONION_WAIT_SECONDS ?? 120);
const STABILITY_SECONDS = Number(process.env.STABILITY_SECONDS ?? 12);
const POLL_INTERVAL_MS = Number(process.env.POLL_INTERVAL_MS ?? 2000);
const SKIP_ONION_SMOKE = process.env.LIMRUN_SKIP_ONION_SMOKE === '1';

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

function decodeScreenshotDataUri(dataUri) {
  if (typeof dataUri !== 'string' || !dataUri.startsWith('data:image/')) {
    throw new Error('Limrun screenshot response did not contain a valid image dataUri');
  }
  const commaIdx = dataUri.indexOf(',');
  if (commaIdx < 0) {
    throw new Error('Malformed dataUri in Limrun screenshot response');
  }
  const buf = Buffer.from(dataUri.slice(commaIdx + 1), 'base64');
  if (buf.length < PNG_MAGIC.length || !buf.subarray(0, PNG_MAGIC.length).equals(PNG_MAGIC)) {
    throw new Error(`Decoded screenshot is not a valid non-empty PNG (${buf.length} bytes)`);
  }
  return buf;
}

async function saveScreenshot(client, targetPath) {
  const shot = await client.screenshot();
  const pngBytes = decodeScreenshotDataUri(shot.dataUri);
  fs.mkdirSync(path.dirname(targetPath), { recursive: true });
  fs.writeFileSync(targetPath, pngBytes);
  return pngBytes.length;
}

function detectFatalLogIssues(logText) {
  const issues = [];
  if (/FATAL EXCEPTION/.test(logText)) {
    issues.push('Java FATAL EXCEPTION detected in logcat');
  }
  if (new RegExp(`ANR in ${PACKAGE.replace('.', '\\.')}`).test(logText)) {
    issues.push(`Application Not Responding (ANR in ${PACKAGE}) detected in logcat`);
  }
  if (/Fatal signal (?:11|6|4|7|8)\b|\*\*\* \*\*\* \*\*\*/.test(logText)) {
    issues.push('Native fatal signal / tombstone header detected in logcat');
  }
  if (/UnsatisfiedLinkError|ClassNotFoundException|NoSuchMethodError/.test(logText)) {
    issues.push('JNI/DEX linkage error (UnsatisfiedLinkError / ClassNotFoundException / NoSuchMethodError) detected');
  }
  if (/Trap startup failed|Cefrium initialization failed|Failed to load libonion_vault\.so/.test(logText)) {
    issues.push('TrapApplication / MainActivity logged a fatal engine or native startup error');
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

async function main() {
  const apkPath = path.resolve(process.argv[2] || DEFAULT_APK);
  const outDir = path.resolve(process.argv[3] || DEFAULT_OUT_DIR);

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
  try {
    apkBytes = assertValidApk(apkPath);
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
    package: PACKAGE,
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
      isolationCreatedInLogcat: false,
      isolationVisibleInUi: false,
      onionHostVisibleInUi: false,
      elapsedSeconds: null,
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
      `== [1/7] Creating Limrun cloud Android instance (Android ${LIMRUN_OS_VERSION}, model=${LIMRUN_MODEL}) ==`,
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

    console.log('== [2/7] Connecting InstanceClient & inspecting cloud Android device ==');
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
      `== [3/7] Syncing & installing ${path.basename(apkPath)} (${(apkBytes / (1024 * 1024)).toFixed(2)} MiB) ==`,
    );
    const installStarted = Date.now();
    if (LIMRUN_INSTALL_MODE !== 'asset') {
      try {
        await client.syncApp(apkPath, {
          install: true,
          watch: false,
          launchMode: 'None',
        });
        summary.installMethod = 'syncApp';
      } catch (syncErr) {
        if (LIMRUN_INSTALL_MODE === 'sync') {
          throw syncErr;
        }
        console.warn(
          `  syncApp fallback to assets.getOrUpload + sendAsset: ${
            syncErr instanceof Error ? syncErr.message : String(syncErr)
          }`,
        );
      }
    }
    if (!summary.installMethod) {
      const asset = await limrun.assets.getOrUpload({
        path: apkPath,
        name: path.basename(apkPath),
        ttl: '2h',
      });
      await client.sendAsset(asset.signedDownloadUrl, 300_000);
      summary.installMethod = 'sendAsset';
    }

    const installedPkg = await client.adbShell('pm', ['path', PACKAGE]);
    if (installedPkg.exitCode !== 0 || !installedPkg.stdout.trim().startsWith('package:')) {
      throw new Error(
        `Package ${PACKAGE} was not installed after ${summary.installMethod}: ${installedPkg.stdout} ${installedPkg.stderr}`,
      );
    }
    console.log(
      `  Installed ${PACKAGE} via ${summary.installMethod} in ${((Date.now() - installStarted) / 1000).toFixed(1)}s`,
    );

    await client
      .adbShell('sh', ['-c', `run-as ${PACKAGE} rm -f files/crash-java.txt files/crash-native.txt 2>/dev/null || true`])
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

    console.log(`== [4/7] Launching ${PACKAGE} under ActivityManager crash/ANR watch ==`);
    await client.launchApp(PACKAGE, {
      mode: 'RelaunchIfRunning',
      onExit: (logs, info) => {
        summary.unexpectedExit = {
          at: new Date().toISOString(),
          info,
          recentLogs: Array.isArray(logs) ? logs.slice(-80) : [],
        };
        console.error(
          `::error::${PACKAGE} exited unexpectedly during gate (reason=${info?.reason}): ` +
            `${info?.crash?.shortMsg || info?.anr?.processName || 'process exited'}`,
        );
      },
    });

    console.log(
      `== [5/7] Waiting up to ${BOOT_WAIT_SECONDS}s for Cefrium engine + ov_tor SOCKS5 + Arti Tor bootstrap ==`,
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
      if (/the engine is ready/.test(logText)) {
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

      if (!summary.readiness.torBootstrapped && summary.readiness.engineReady && summary.readiness.socksEndpoint) {
        const treeProbe = await client.getElementTree({ waitForIdleTimeoutMs: 1000 }).catch(() => null);
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

    const startTree = await client.getElementTree({ waitForIdleTimeoutMs: 2500 }).catch(() => null);
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
    } else {
      summary.failures.push('Failed to inspect UIAutomator element tree after launch');
    }

    if (!summary.readiness.engineReady) {
      summary.failures.push("Cefrium never logged 'the engine is ready'");
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
      console.log(`== [6/7] Navigating live over Tor to ${ONION_TEST_URL} ==`);
      const onionStarted = Date.now();

      let tappedCard = false;
      if (startTree && Array.isArray(startTree.nodes)) {
        const ddgNode = startTree.nodes.find((n) =>
          String(n.text || '').includes('DuckDuckGo (.onion v3)'),
        );
        if (ddgNode?.parsedBounds) {
          try {
            await client.tap({
              x: ddgNode.parsedBounds.centerX,
              y: ddgNode.parsedBounds.centerY,
            });
            tappedCard = true;
            summary.onionSmoke.triggerMethod = 'ui-tap-start-card';
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

        finalTree = await client.getElementTree({ waitForIdleTimeoutMs: 1500 }).catch(() => null);
        if (finalTree) {
          const allText = [
            finalTree.xml || '',
            ...(finalTree.nodes || []).map((n) => `${n.text || ''} ${n.contentDesc || ''}`),
          ].join('\n');

          if (allText.includes(ONION_EXPECTED_ISOLATION)) {
            summary.onionSmoke.isolationVisibleInUi = true;
          }
          if (
            !allText.includes('about:blank · zero-JS') &&
            (allText.includes(ONION_EXPECTED_HOST) || /DuckDuckGo/i.test(allText))
          ) {
            summary.onionSmoke.onionHostVisibleInUi = true;
          }
          if (/Navigation failed/i.test(allText)) {
            summary.failures.push(`Browser reported navigation error while loading ${ONION_TEST_URL}`);
            break;
          }
        }

        if (
          (summary.onionSmoke.isolationCreatedInLogcat || summary.onionSmoke.isolationVisibleInUi) &&
          summary.onionSmoke.onionHostVisibleInUi
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

      finalTree = (await client.getElementTree({ waitForIdleTimeoutMs: 2000 }).catch(() => null)) || finalTree;
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

    console.log('== [7/7] Running final forensic crash, tombstone, DropBox & PID audit ==');
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
      `(${summary.deviceProps.abi}, pageSize=${summary.deviceProps.pageSize}): Cefrium ready, ` +
      `ov_tor SOCKS5=${summary.readiness.socksEndpoint}, Arti bootstrapped, ` +
      `${SKIP_ONION_SMOKE ? 'onion smoke skipped' : `isolated ${ONION_EXPECTED_ISOLATION} .onion loaded`}, ` +
      `zero crashes/tombstones/ANRs.`,
  );
  process.exit(0);
}

main().catch((err) => {
  failClosedError(err instanceof Error ? err.stack || err.message : String(err));
  process.exit(1);
});
