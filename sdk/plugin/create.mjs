#!/usr/bin/env node
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { isMain } from './entry.mjs';

/** Scaffold with an explicit immutable toolkit ref and vendored SDK archive, never a floating npm download. */
export async function createPlugin(directory, archive, hostRef, id) {
  if (!/^[a-f0-9]{40}$/.test(hostRef)) throw new Error('Expected exact toolkit commit SHA');
  if (!/^[a-z][a-z0-9]*(\.[a-z][a-z0-9_-]*)+$/.test(id ?? '') || id.length > 120) throw new Error('Invalid plugin ID');
  const sdk = await readFile(archive);
  const root = resolve(directory);
  await mkdir(root); // Refuse an existing directory; no partial overwrite of a user's project.
  const files = {
    'vendor/plugin-sdk.tgz': sdk,
    '.gitignore': 'node_modules/\nrelease/\n',
    'pnpm-workspace.yaml': 'allowBuilds:\n  esbuild: true\n',
    'package.json': JSON.stringify({ name: id, version: '0.1.0', private: true, type: 'module',
      packageManager: 'pnpm@11.28.11', scripts: { test: 'node --test test/*.test.mjs' },
      devDependencies: { '@rikkahub/plugin-sdk': 'file:vendor/plugin-sdk.tgz' } }, null, 2) + '\n',
    'manifest.json': JSON.stringify({ id, name: 'Review report', version: '0.1.0', apiVersion: '1', runtime: 'quickjs-es2020',
      capabilities: ['context.read', 'artifacts.write', 'ui.actions', 'ui.overlay.app'], requiredFeatures: [],
      operations: [{ id: 'review', title: 'Review conversation' }],
      panels: [{ id: 'review', title: 'Review report', overlay: true }] }, null, 2) + '\n',
    'src/index.ts': `import { definePlugin } from '@rikkahub/plugin-sdk';
definePlugin(api => {
  api.registerOperation('review', async (_input, context) => {
    const messages = await context.readMessages();
    return { artifacts: [{ type: 'review', content: { messages: messages.length } }] };
  });
  api.registerPanel('review', () => ({ type: 'column', children: [
    { type: 'text', text: 'Publish a report without changing model context.' },
    { type: 'button', text: 'Review', operation: 'review' }
  ] }));
});
`,
    'test/plugin.test.mjs': `import test from 'node:test';
import assert from 'node:assert/strict';
import { runInNewContext } from 'node:vm';
import { fileURLToPath } from 'node:url';
import { unzipSync } from '@rikkahub/plugin-sdk/archive';
import { buildPackage } from '@rikkahub/plugin-sdk/build';
test('report counts messages without projecting or changing history', async () => {
  const pkg = await buildPackage(fileURLToPath(new URL('..', import.meta.url)));
  const world = {};
  runInNewContext(Buffer.from(unzipSync(pkg.archive)['main.js']).toString(), world, { timeout: 1000 });
  const handlers = {};
  world.rikkaPlugin({ registerOperation: (id, fn) => handlers[id] = fn, registerPanel: () => {} });
  const result = await handlers.review({}, { readMessages: async () => [{id:'1',role:'user',text:'Review me'}] });
  assert.equal(result.artifacts[0].content.messages, 1);
  assert.equal(result.projection, undefined);
});
`,
    '.github/workflows/release.yml': `name: Plugin checks and release
on:
  push:
    branches: [main]
    tags: ['v*']
  pull_request:
permissions:
  contents: write
jobs:
  package:
    uses: mapleluvr/rikkahub-plugins/.github/workflows/plugin-release.yml@${hostRef}
    with:
      host-ref: ${hostRef}
      release-tag: \${{ startsWith(github.ref, 'refs/tags/') && github.ref_name || 'v0.1.0' }}
      publish: \${{ startsWith(github.ref, 'refs/tags/') }}
    secrets:
      host-read-token: \${{ secrets.HOST_TOOLKIT_READ_TOKEN }}
      plugin-signing-key: \${{ secrets.PLUGIN_SIGNING_KEY }}
`,
    'README.md': `# ${id}

Requires Node 24.15.0 and pnpm 11.28.11. SDK bytes are in vendor/plugin-sdk.tgz; retain them and the generated pnpm-lock.yaml in Git.

Run pnpm install, pnpm test, then set RIKKA_RELEASE_TAG=v0.1.0 and run pnpm exec rikkaplug-release build . release.

The reusable workflow is pinned to ${hostRef}. Configure the plugin-release environment and restrict it to protected version tags. While the toolkit is private, HOST_TOOLKIT_READ_TOKEN needs read access to that repository; do not expose it to untrusted fork pull requests. Set PLUGIN_SIGNING_KEY to a P-256 PKCS#8 PEM only if signing is wanted. Signing keys are never included in the package.

Change package.json and manifest.json together; create the matching v-prefixed tag. Main-branch verification currently uses v0.1.0: update that fixed check input when changing versions. The workflow checks twice-built packages and real QuickJS registration before publishing a draft's verified assets.
`
  };
  for (const [path, bytes] of Object.entries(files)) {
    await mkdir(join(root, path, '..'), { recursive: true });
    await writeFile(join(root, path), bytes, { flag: 'wx' });
  }
}
if (isMain(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args.length !== 4) { console.error('Usage: rikkaplug-create <new-directory> <sdk.tgz> <toolkit-commit> <plugin-id>'); process.exitCode = 1; }
  else createPlugin(...args).catch(error => { console.error(error.message); process.exitCode = 1; });
}
