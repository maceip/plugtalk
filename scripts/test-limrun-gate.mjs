#!/usr/bin/env node
/**
 * End-to-end integration test suite for `scripts/limrun-gate.sh` + `scripts/limrun-gate.mjs`.
 *
 * Boots an in-process HTTP + WebSocket test harness speaking the real `@limrun/api`
 * control-plane (`/v1/android_instances`, `/v1/assets`) and Android instance data-plane
 * (`/ws`, `/sync/state`, `/sync`, `/logcat`) protocols, then executes `scripts/limrun-gate.sh`
 * across three mandatory scenarios:
 *   1. Missing `LIM_API_KEY` / `LIMRUN_API_KEY` -> fails closed immediately (exit 1).
 *   2. Immediate launch crash (`appExit` + native `Fatal signal 6`) -> captures forensic
 *      artifacts, deletes the cloud instance cleanly, and fails closed (exit 1).
 *   3. Healthy boot + Arti Tor bootstrap + UI tap on DuckDuckGo `.onion` card + circuit
 *      isolation (`iso-duckduckgogg42xj`) -> writes screenshots/logs/summary, deletes
 *      the cloud instance cleanly, and exits 0.
 */

import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(SCRIPT_DIR, '..');
const GATE_SH = path.join(REPO_ROOT, 'scripts/limrun-gate.sh');

// 1x1 valid RGBA PNG bytes
const ONE_PX_PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==';

function ensureToolsDir() {
  if (
    process.env.LIMRUN_TOOLS_DIR &&
    fs.existsSync(path.join(process.env.LIMRUN_TOOLS_DIR, 'node_modules/@limrun/api/package.json'))
  ) {
    return path.resolve(process.env.LIMRUN_TOOLS_DIR);
  }
  const fallback = path.join(os.tmpdir(), 'trap-limrun-tools');
  fs.mkdirSync(fallback, { recursive: true });
  if (!fs.existsSync(path.join(fallback, 'node_modules/@limrun/api/package.json'))) {
    const pkgPath = path.join(fallback, 'package.json');
    if (!fs.existsSync(pkgPath)) {
      fs.writeFileSync(pkgPath, '{"name":"trap-limrun-tools","private":true}\n');
    }
    const res = spawnSync(
      'npm',
      ['install', '--no-audit', '--no-fund', '--save-exact', '@limrun/api@0.73.2', 'lim@0.57.3'],
      { cwd: fallback, stdio: 'inherit' },
    );
    assert.equal(res.status, 0, 'npm install @limrun/api lim failed');
  }
  return fallback;
}

function createMinimalValidZipApk(destPath) {
  // Minimal 22-byte End of Central Directory record prefixed by a valid local file header (PK\x03\x04)
  const fileName = Buffer.from('AndroidManifest.xml', 'utf8');
  const data = Buffer.from('trap-test-apk', 'utf8');

  const localHeader = Buffer.alloc(30);
  localHeader.writeUInt32LE(0x04034b50, 0); // signature PK\x03\x04
  localHeader.writeUInt16LE(20, 4); // version needed
  localHeader.writeUInt16LE(0, 8); // stored
  localHeader.writeUInt32LE(0, 14); // crc32 (unused for magic check)
  localHeader.writeUInt32LE(data.length, 18);
  localHeader.writeUInt32LE(data.length, 22);
  localHeader.writeUInt16LE(fileName.length, 26);

  const centralHeader = Buffer.alloc(46);
  centralHeader.writeUInt32LE(0x02014b50, 0);
  centralHeader.writeUInt16LE(20, 4);
  centralHeader.writeUInt16LE(20, 6);
  centralHeader.writeUInt32LE(data.length, 20);
  centralHeader.writeUInt32LE(data.length, 24);
  centralHeader.writeUInt16LE(fileName.length, 28);
  centralHeader.writeUInt32LE(0, 42);

  const cdOffset = localHeader.length + fileName.length + data.length;
  const cdSize = centralHeader.length + fileName.length;

  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(1, 8);
  eocd.writeUInt16LE(1, 10);
  eocd.writeUInt32LE(cdSize, 12);
  eocd.writeUInt32LE(cdOffset, 16);

  fs.writeFileSync(
    destPath,
    Buffer.concat([localHeader, fileName, data, centralHeader, fileName, eocd]),
  );
}

async function startMockLimrunServer(toolsDir, mode) {
  const req = createRequire(path.join(toolsDir, 'package.json'));
  const { WebSocketServer } = req('ws');

  const state = {
    createdInstances: [],
    deletedInstances: [],
    installedPackages: new Set(),
    launched: false,
    navigatedOnion: false,
    createPayloads: [],
  };

  const server = http.createServer((httpReq, httpRes) => {
    const url = new URL(httpReq.url, `http://${httpReq.headers.host}`);
    const chunks = [];
    httpReq.on('data', (c) => chunks.push(c));
    httpReq.on('end', () => {
      const rawBody = Buffer.concat(chunks);

      if (httpReq.method === 'POST' && url.pathname === '/v1/android_instances') {
        const parsed = JSON.parse(rawBody.toString('utf8') || '{}');
        state.createPayloads.push(parsed);
        const id = `android_test_${state.createdInstances.length + 1}`;
        state.createdInstances.push(id);
        const base = `http://127.0.0.1:${server.address().port}`;
        const wsBase = `ws://127.0.0.1:${server.address().port}`;
        httpRes.writeHead(200, { 'Content-Type': 'application/json' });
        httpRes.end(
          JSON.stringify({
            metadata: {
              id,
              createdAt: new Date().toISOString(),
              organizationId: 'org_test',
              displayName: parsed?.metadata?.displayName || 'test',
              labels: parsed?.metadata?.labels || {},
            },
            spec: {
              inactivityTimeout: parsed?.spec?.inactivityTimeout || '10m',
              hardTimeout: parsed?.spec?.hardTimeout || '20m',
              region: 'us-west1',
            },
            status: {
              state: 'ready',
              token: 'tok_instance_secret',
              apiUrl: `${base}/instance/${id}`,
              adbWebSocketUrl: `${wsBase}/instance/${id}/adb`,
            },
          }),
        );
        return;
      }

      if (httpReq.method === 'DELETE' && url.pathname.startsWith('/v1/android_instances/')) {
        const id = url.pathname.split('/').pop();
        state.deletedInstances.push(id);
        httpRes.writeHead(204);
        httpRes.end();
        return;
      }

      if (httpReq.method === 'PUT' && url.pathname === '/v1/assets') {
        const parsed = JSON.parse(rawBody.toString('utf8') || '{}');
        const base = `http://127.0.0.1:${server.address().port}`;
        httpRes.writeHead(200, { 'Content-Type': 'application/json' });
        httpRes.end(
          JSON.stringify({
            id: 'asset_1',
            name: parsed.name || 'app-debug.apk',
            kind: 'App',
            signedUploadUrl: `${base}/upload/asset_1`,
            signedDownloadUrl: `${base}/download/asset_1`,
          }),
        );
        return;
      }

      if (httpReq.method === 'PUT' && url.pathname === '/upload/asset_1') {
        httpRes.writeHead(200);
        httpRes.end('ok');
        return;
      }

      if (httpReq.method === 'GET' && url.pathname.endsWith('/sync/state')) {
        httpRes.writeHead(200, { 'Content-Type': 'application/json' });
        httpRes.end(JSON.stringify({ roots: [], seeds: [] }));
        return;
      }

      if (httpReq.method === 'POST' && url.pathname.endsWith('/sync')) {
        state.installedPackages.add('dev.onionvault');
        httpRes.writeHead(200, { 'Content-Type': 'application/json' });
        httpRes.end(JSON.stringify({ ok: true }));
        return;
      }

      if (httpReq.method === 'POST' && url.pathname.endsWith('/logcat')) {
        httpRes.writeHead(200, { 'Content-Type': 'application/json' });
        httpRes.end(JSON.stringify({ id: 'logrun_1' }));
        return;
      }

      if (httpReq.method === 'DELETE' && url.pathname.includes('/logcat/')) {
        httpRes.writeHead(204);
        httpRes.end();
        return;
      }

      if (httpReq.method === 'GET' && url.pathname.includes('/logcat/')) {
        httpRes.writeHead(200, {
          'Content-Type': 'text/event-stream',
          'Cache-Control': 'no-cache',
          Connection: 'keep-alive',
        });
        httpRes.write(': connected\n\n');
        return;
      }

      httpRes.writeHead(404, { 'Content-Type': 'text/plain' });
      httpRes.end(`Unhandled ${httpReq.method} ${url.pathname}`);
    });
  });

  const wss = new WebSocketServer({ noServer: true });
  server.on('upgrade', (reqUp, socket, head) => {
    wss.handleUpgrade(reqUp, socket, head, (ws) => {
      wss.emit('connection', ws, reqUp);
    });
  });

  const b64 = (s) => Buffer.from(s, 'utf8').toString('base64');

  wss.on('connection', (ws) => {
    ws.on('message', (raw) => {
      const msg = JSON.parse(raw.toString('utf8'));
      const { type, id } = msg;

      if (type === 'asset') {
        state.installedPackages.add('dev.onionvault');
        ws.send(JSON.stringify({ type: 'assetResult', url: msg.url, result: 'success' }));
        return;
      }

      if (type === 'launchApp') {
        state.launched = true;
        ws.send(
          JSON.stringify({
            type: 'launchAppResult',
            id,
            payload: { packageName: msg.packageName },
          }),
        );
        if (mode === 'crash_on_launch' && msg.execId) {
          setImmediate(() => {
            if (ws.readyState === ws.OPEN) {
              ws.send(
                JSON.stringify({
                  type: 'appExit',
                  execId: msg.execId,
                  packageName: msg.packageName,
                  reason: 'crash',
                  crash: {
                    processName: 'dev.onionvault',
                    pid: 8123,
                    shortMsg: 'Native crash',
                    longMsg: 'Fatal signal 6 (SIGABRT), code -1 in tid 8123 (dev.onionvault)',
                    stackTrace:
                      '*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***\n' +
                      'Build fingerprint: Android/sdk_gphone64/emu64a:15\n' +
                      '      #00 pc 000000000412abcd  /data/app/dev.onionvault/lib/arm64/libcef.so',
                    timeMillis: Date.now(),
                  },
                  logs: [
                    '11-15 03:00:01.100  8123  8123 F libc    : Fatal signal 6 (SIGABRT), code -1 in tid 8123 (dev.onionvault)',
                  ],
                }),
              );
            }
          });
        }
        return;
      }

      if (type === 'tap') {
        state.navigatedOnion = true;
        ws.send(JSON.stringify({ type: 'tapResult', id, payload: { x: msg.x ?? 200, y: msg.y ?? 400 } }));
        return;
      }

      if (type === 'screenshot') {
        ws.send(
          JSON.stringify({
            type: 'screenshotResult',
            id,
            payload: { dataUri: `data:image/png;base64,${ONE_PX_PNG_BASE64}` },
          }),
        );
        return;
      }

      if (type === 'getElementTree') {
        const navActionDesc =
          mode === 'premature_onion_still_loading' ? 'Stop loading' : 'Reload';
        const xml =
          mode === 'crash_on_launch'
            ? '<hierarchy><node package="com.android.launcher3" text="Home" /></hierarchy>'
            : state.navigatedOnion
              ? '<hierarchy>' +
                '<node package="dev.onionvault" content-desc="Back" />' +
                '<node package="dev.onionvault" content-desc="Tor security status" />' +
                '<node package="dev.onionvault" text="https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/" />' +
                `<node package="dev.onionvault" content-desc="${navActionDesc}" />` +
                '<node package="dev.onionvault" text="Go" content-desc="Go" />' +
                '</hierarchy>'
              : '<hierarchy>' +
                '<node package="dev.onionvault" text="Trap" />' +
                '<node package="dev.onionvault" text="Tor ready" />' +
                '<node package="dev.onionvault" content-desc="Tor security status" />' +
                '<node package="dev.onionvault" text="Enter address" />' +
                '<node package="dev.onionvault" text="Go" content-desc="Go" />' +
                '</hierarchy>';

        const nodes = state.navigatedOnion
          ? [
              { packageName: 'dev.onionvault', contentDesc: 'Back' },
              { packageName: 'dev.onionvault', contentDesc: 'Tor security status' },
              {
                packageName: 'dev.onionvault',
                text: 'https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/',
              },
              { packageName: 'dev.onionvault', contentDesc: navActionDesc },
              { packageName: 'dev.onionvault', text: 'Go', contentDesc: 'Go' },
            ]
          : [
              { packageName: 'dev.onionvault', text: 'Trap' },
              { packageName: 'dev.onionvault', text: 'Tor ready' },
              { packageName: 'dev.onionvault', contentDesc: 'Tor security status' },
              { packageName: 'dev.onionvault', text: 'Enter address' },
              { packageName: 'dev.onionvault', text: 'Go', contentDesc: 'Go' },
            ];

        ws.send(
          JSON.stringify({
            type: 'getElementTreeResult',
            id,
            payload: { xml, nodes: mode === 'crash_on_launch' ? [] : nodes },
          }),
        );
        return;
      }

      if (type === 'adbShell') {
        const cmd = msg.command || '';
        let out = '';
        let exitCode = 0;

        if (cmd.includes('sys.boot_completed')) {
          out = '1\n';
        } else if (cmd.includes("'pm' 'path' 'android'")) {
          out = 'package:/system/framework/framework-res.apk\n';
        } else if (cmd.includes("'pm' 'path' 'dev.onionvault'")) {
          if (state.installedPackages.has('dev.onionvault')) {
            out = 'package:/data/app/dev.onionvault-1/base.apk\n';
          } else {
            exitCode = 1;
          }
        } else if (cmd.includes('ro.product.cpu.abi') && !cmd.includes('abilist')) {
          out = 'x86_64\n';
        } else if (cmd.includes('ro.product.cpu.abilist')) {
          out = 'x86_64,arm64-v8a\n';
        } else if (cmd.includes('ro.build.version.sdk')) {
          out = '35\n';
        } else if (cmd.includes('ro.build.version.release')) {
          out = '15\n';
        } else if (cmd.includes('ro.product.model')) {
          out = 'Limrun Cloud Phone\n';
        } else if (cmd.includes('ro.build.fingerprint')) {
          out = 'google/sdk_gphone64_x86_64/emu64xa:15/AE3A/1234:userdebug/dev-keys\n';
        } else if (cmd.includes('PAGESIZE')) {
          out = '4096\n';
        } else if (cmd.includes("'am' 'start'") && cmd.includes('duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion')) {
          state.navigatedOnion = true;
          out = 'Status: ok\nLaunchState: HOT\n';
        } else if (cmd.includes("'logcat' '-d'")) {
          if (mode === 'crash_on_launch') {
            out = [
              '11-15 03:00:01.100  8123  8123 F libc    : Fatal signal 6 (SIGABRT), code -1 in tid 8123 (dev.onionvault)',
              '11-15 03:00:01.150  8129  8129 F DEBUG   : *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***',
            ].join('\n');
          } else if (mode === 'mte_sigtrap_child_process') {
            // Reproduces exact v0.1.27 ARMv9 MTE renderer crash (#65): main browser PID 9001 stays alive
            // while :sandboxed_process0 hits SetHeapTaggingLevel failure + Fatal signal 5 (SIGTRAP).
            out = [
              '11-15 03:00:01.010  9001  9001 I Trap    : the engine is ready',
              '11-15 03:00:01.250  9001  9024 I ov_tor  : SOCKS5 listener bound to 127.0.0.1:41955',
              '11-15 03:00:01.600  9140  9140 E libc    : SetHeapTaggingLevel: set_tcf_on_all_threads failed',
              '11-15 03:00:01.602  9140  9140 F libc    : Fatal signal 5 (SIGTRAP), code 1 (TRAP_BRKPT) in tid 9140 (sandboxed_process0)',
            ].join('\n');
          } else {
            const lines = [
              '11-15 03:00:01.010  9001  9001 I Trap    : the engine is ready',
              '11-15 03:00:01.250  9001  9024 I ov_tor  : SOCKS5 listener bound to 127.0.0.1:41955',
              '11-15 03:00:01.400  9001  9001 I Cefrium : loadUrl: about:blank',
              '11-15 03:00:01.750  9001  9001 I Cefrium : OnLoadEnd: status 0, isMain=1',
              '11-15 03:00:03.400  9001  9025 I ov_tor  : Tor client bootstrapped',
            ];
            if (state.navigatedOnion) {
              lines.push(
                '11-15 03:00:04.050  9001  9001 I Cefrium : loadUrl: https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/',
                '11-15 03:00:04.100  9001  9026 I ov_tor  : created isolated Tor circuit group iso-duckduckgogg42xj',
              );
              if (mode !== 'premature_onion_still_loading') {
                lines.push(
                  '11-15 03:00:18.450  9001  9001 I Cefrium : OnLoadEnd: status 200, isMain=1',
                );
              }
            }
            out = `${lines.join('\n')}\n`;
          }
        } else if (cmd.includes("'pidof' 'dev.onionvault'")) {
          if (mode === 'crash_on_launch') {
            out = '';
            exitCode = 1;
          } else {
            out = '9001\n';
          }
        }

        ws.send(
          JSON.stringify({
            type: 'adbShellResult',
            id,
            payload: {
              stdout: b64(out),
              stderr: '',
              exitCode,
              truncated: false,
            },
          }),
        );
      }
    });
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return {
    baseURL: `http://127.0.0.1:${server.address().port}`,
    state,
    close: () =>
      new Promise((resolve) => {
        for (const client of wss.clients) {
          client.terminate();
        }
        wss.close(() => {
          server.closeAllConnections?.();
          server.close(resolve);
        });
      }),
  };
}

function runGateAsync(args, env) {
  return new Promise((resolve, reject) => {
    const child = spawn('bash', [GATE_SH, ...args], { env });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => {
      stdout += d.toString('utf8');
    });
    child.stderr.on('data', (d) => {
      stderr += d.toString('utf8');
    });
    child.on('error', reject);
    child.on('close', (status) => {
      resolve({ status, stdout, stderr });
    });
  });
}

async function runTests() {
  const toolsDir = ensureToolsDir();
  const workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'trap-limrun-gate-test-'));
  const apkPath = path.join(workDir, 'app-debug.apk');
  createMinimalValidZipApk(apkPath);

  console.log('== Test 1: Missing LIM_API_KEY / LIMRUN_API_KEY fails closed immediately ==');
  {
    const outDir = path.join(workDir, 'out-missing-key');
    const cleanEnv = { ...process.env };
    delete cleanEnv.LIM_API_KEY;
    delete cleanEnv.LIMRUN_API_KEY;
    const res = await runGateAsync([apkPath, outDir], cleanEnv);
    assert.notEqual(res.status, 0, 'Gate must exit non-zero when LIM_API_KEY is missing');
    assert.match(res.stderr, /::error::Missing Limrun API key/);
    console.log('  PASS: Missing API key failed closed with exit code', res.status);
  }

  console.log('== Test 2: Immediate launch crash fails closed and deletes cloud instance ==');
  {
    const mock = await startMockLimrunServer(toolsDir, 'crash_on_launch');
    const outDir = path.join(workDir, 'out-crash');
    try {
      const res = await runGateAsync([apkPath, outDir], {
        ...process.env,
        LIMRUN_API_KEY: 'lim_test_key_alias',
        LIM_API_KEY: '',
        LIMRUN_BASE_URL: mock.baseURL,
        LIMRUN_TOOLS_DIR: toolsDir,
        LIMRUN_INSTALL_MODE: 'asset',
        BOOT_WAIT_SECONDS: '6',
        ONION_WAIT_SECONDS: '4',
        STABILITY_SECONDS: '0',
        POLL_INTERVAL_MS: '200',
      });
      assert.notEqual(res.status, 0, 'Gate must exit non-zero when app crashes on launch');
      assert.deepEqual(
        mock.state.deletedInstances,
        mock.state.createdInstances,
        'Created Limrun Android instance must be deleted in finally even on crash',
      );
      const summary = JSON.parse(fs.readFileSync(path.join(outDir, 'limrun-summary.json'), 'utf8'));
      assert.equal(summary.passed, false);
      assert.equal(summary.unexpectedExit?.info?.reason, 'crash');
      assert.ok(
        summary.failures.some((f) => /Native fatal signal|ActivityManager reported/.test(f)),
        `Expected crash failure in summary.failures, got: ${JSON.stringify(summary.failures)}`,
      );
      assert.ok(fs.existsSync(path.join(outDir, 'limrun-logcat.txt')));
      console.log('  PASS: Launch crash failed closed, saved forensics, and deleted', mock.state.deletedInstances[0]);
    } finally {
      await mock.close();
    }
  }

  console.log('== Test 3: Healthy boot + Tor bootstrap + live .onion UI tap passes and deletes instance ==');
  {
    const mock = await startMockLimrunServer(toolsDir, 'healthy');
    const outDir = path.join(workDir, 'out-healthy');
    try {
      const res = await runGateAsync([apkPath, outDir], {
        ...process.env,
        LIM_API_KEY: 'lim_test_key_primary',
        LIMRUN_BASE_URL: mock.baseURL,
        LIMRUN_TOOLS_DIR: toolsDir,
        BOOT_WAIT_SECONDS: '10',
        ONION_WAIT_SECONDS: '10',
        STABILITY_SECONDS: '1',
        POLL_INTERVAL_MS: '200',
        GITHUB_REPOSITORY: 'maceip/trap',
        GITHUB_SHA: 'abcdef1234567890',
        GITHUB_RUN_ID: '99999',
        GITHUB_RUN_ATTEMPT: '1',
      });
      if (res.status !== 0) {
        console.error('STDOUT:\n', res.stdout);
        console.error('STDERR:\n', res.stderr);
      }
      assert.equal(res.status, 0, 'Healthy gate run must exit 0');
      assert.deepEqual(
        mock.state.deletedInstances,
        mock.state.createdInstances,
        'Healthy gate run must delete its Limrun Android instance on completion',
      );
      const summary = JSON.parse(fs.readFileSync(path.join(outDir, 'limrun-summary.json'), 'utf8'));
      assert.equal(summary.passed, true);
      assert.equal(summary.installMethod, 'syncApp');
      assert.equal(summary.readiness.engineReady, true);
      assert.equal(summary.readiness.socksEndpoint, '127.0.0.1:41955');
      assert.equal(summary.readiness.torBootstrapped, true);
      assert.equal(summary.onionSmoke.triggerMethod, 'am-start-action-view');
      assert.equal(summary.onionSmoke.isolationCreatedInLogcat, true);
      assert.equal(summary.onionSmoke.onionHostVisibleInUi, true);
      assert.equal(summary.onionSmoke.pageLoadedInLogcat, true);
      assert.equal(summary.onionSmoke.mainFrameHttpStatus, 200);
      assert.equal(summary.onionSmoke.onionTitleRenderedInUi, true);
      assert.equal(summary.appPid, '9001');
      assert.ok(fs.statSync(path.join(outDir, 'limrun-launch.png')).size > 0);
      assert.ok(fs.statSync(path.join(outDir, 'limrun-onion.png')).size > 0);
      assert.ok(fs.statSync(path.join(outDir, 'limrun-logcat.txt')).size > 0);
      assert.ok(fs.statSync(path.join(outDir, 'limrun-ui-tree.xml')).size > 0);
      console.log('  PASS: Healthy gate verified syncApp + launchApp + Tor + .onion UI smoke + cleanup');
    } finally {
      await mock.close();
    }
  }

  console.log('== Test 4: v0.1.27 ARMv9 MTE child-process SIGTRAP (Fatal signal 5) fails closed ==');
  {
    const mock = await startMockLimrunServer(toolsDir, 'mte_sigtrap_child_process');
    const outDir = path.join(workDir, 'out-mte-sigtrap');
    try {
      const res = await runGateAsync([apkPath, outDir], {
        ...process.env,
        LIM_API_KEY: 'lim_test_key_mte',
        LIMRUN_BASE_URL: mock.baseURL,
        LIMRUN_TOOLS_DIR: toolsDir,
        BOOT_WAIT_SECONDS: '6',
        ONION_WAIT_SECONDS: '4',
        STABILITY_SECONDS: '0',
        POLL_INTERVAL_MS: '200',
      });
      assert.notEqual(
        res.status,
        0,
        'Gate must fail closed on child-process Fatal signal 5 (SIGTRAP) / MTE fault even if browser PID stays alive',
      );
      assert.deepEqual(mock.state.deletedInstances, mock.state.createdInstances);
      const summary = JSON.parse(fs.readFileSync(path.join(outDir, 'limrun-summary.json'), 'utf8'));
      assert.equal(summary.passed, false);
      assert.ok(
        summary.failures.some((f) => /Native fatal signal/.test(f)),
        `Expected Fatal signal 5 detection in failures: ${JSON.stringify(summary.failures)}`,
      );
      assert.ok(
        summary.failures.some((f) => /ARMv9 MTE \/ PartitionAlloc/.test(f)),
        `Expected MTE SetHeapTaggingLevel detection in failures: ${JSON.stringify(summary.failures)}`,
      );
      console.log('  PASS: Child-process Fatal signal 5 (SIGTRAP) + MTE fault caught and instance deleted');
    } finally {
      await mock.close();
    }
  }

  console.log('== Test 5: Unrendered .onion tab (stale about:blank header + no HTTP 2xx/3xx OnLoadEnd) fails closed ==');
  {
    const mock = await startMockLimrunServer(toolsDir, 'premature_onion_still_loading');
    const outDir = path.join(workDir, 'out-premature-onion');
    try {
      const res = await runGateAsync([apkPath, outDir], {
        ...process.env,
        LIM_API_KEY: 'lim_test_key_premature',
        LIMRUN_BASE_URL: mock.baseURL,
        LIMRUN_TOOLS_DIR: toolsDir,
        BOOT_WAIT_SECONDS: '6',
        ONION_WAIT_SECONDS: '3',
        STABILITY_SECONDS: '0',
        POLL_INTERVAL_MS: '200',
      });
      assert.notEqual(
        res.status,
        0,
        'Gate must fail closed if .onion URL is only in address bar while viewport is still about:blank',
      );
      assert.deepEqual(mock.state.deletedInstances, mock.state.createdInstances);
      const summary = JSON.parse(fs.readFileSync(path.join(outDir, 'limrun-summary.json'), 'utf8'));
      assert.equal(summary.passed, false);
      assert.equal(summary.onionSmoke.isolationCreatedInLogcat, true);
      assert.equal(summary.onionSmoke.onionHostVisibleInUi, true);
      assert.equal(summary.onionSmoke.pageLoadedInLogcat, false);
      assert.equal(summary.onionSmoke.mainFrameHttpStatus, null);
      assert.equal(summary.onionSmoke.onionTitleRenderedInUi, false);
      assert.ok(
        summary.failures.some((f) => /OnLoadEnd/.test(f)),
        `Expected missing OnLoadEnd failure in summary.failures: ${JSON.stringify(summary.failures)}`,
      );
      assert.ok(
        summary.failures.some((f) => /loading \/ stale 'about:blank' state/.test(f)),
        `Expected unfinished loading / stale about:blank failure in summary.failures: ${JSON.stringify(summary.failures)}`,
      );
      console.log('  PASS: Unrendered .onion still-loading state failed closed and deleted instance');
    } finally {
      await mock.close();
    }
  }

  fs.rmSync(workDir, { recursive: true, force: true });
  console.log('ALL 5 LIMRUN GATE E2E TESTS PASSED');
}

runTests().catch((err) => {
  console.error(err);
  process.exit(1);
});
