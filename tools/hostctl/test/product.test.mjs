import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { loadInputs, git, digest } from '../inputs.mjs';
import { prepare } from '../workspace.mjs';
import { exportSource } from '../source.mjs';

const product = () => ({
  schemaVersion: 1, name: 'RikkaHub Plugins', applicationId: 'io.github.mapleluvr.rikkahub',
  deepLinkScheme: 'rikkahub-plugins', versionCode: 1, versionName: '3.3.0-plugins.1',
  updates: { repository: 'mapleluvr/rikkahub-plugin-host', assetName: 'host-release.json' },
  signingCertificateSha256: 'ab'.repeat(32),
});

async function fixture(t, value = product(), { mapped = true, configured = true } = {}) {
  const root = await mkdtemp(join(tmpdir(), 'hostctl product '));
  t.after(() => rm(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 }));
  const repo = join(root, 'maintenance');
  const source = join(root, 'source');
  for (const dir of [repo, source]) {
    await mkdir(dir);
    git(dir, ['init', '-b', 'main']);
    git(dir, ['config', 'core.autocrlf', 'false']);
    git(dir, ['config', 'user.name', 'Product fixture']);
    git(dir, ['config', 'user.email', 'fixture@example.invalid']);
  }
  await writeFile(join(source, 'entry.txt'), 'upstream\n');
  git(source, ['add', '.']);
  git(source, ['commit', '-m', 'upstream']);
  const put = async (path, data) => {
    const p = join(repo, path);
    await mkdir(join(p, '..'), { recursive: true });
    await writeFile(p, `${JSON.stringify(data, null, 2)}\n`);
  };
  await put('distribution/product.json', value);
  await put('profiles/test/profile.json', {
    schemaVersion: 1, upstream: { url: source, commit: git(source, ['rev-parse', 'HEAD']) },
    series: 'patches/test/series.json', toolchain: 'profiles/test/toolchain.json',
    sourceMap: 'profiles/test/source-map.json',
    ...(configured ? { product: 'distribution/product.json' } : {}),
  });
  await put('profiles/test/source-map.json', {
    schemaVersion: 1, imports: mapped ? [
      { from: 'distribution/product.json', to: 'vendor/rikka-plugin-host/distribution/product.json' },
    ] : [],
  });
  await put('profiles/test/toolchain.json', { schemaVersion: 1, node: process.versions.node });
  await put('patches/test/series.json', { schemaVersion: 1, patches: [] });
  git(repo, ['add', '.']);
  git(repo, ['commit', '-m', 'product inputs']);
  return { repo, source, root, profile: 'test', out: join(root, 'integration') };
}

test('configured distribution is validated, frozen, assembled and present in source provenance', async t => {
  const f = await fixture(t);
  const inputs = loadInputs(f.repo, f.profile);
  assert.deepEqual(inputs.product, product());
  const sha = digest(await readFile(join(f.repo, 'distribution/product.json')));
  assert.equal(inputs.productDigest, sha);
  const lock = await prepare(f);
  assert.equal(lock.productDigest, sha);
  assert.deepEqual(JSON.parse(await readFile(join(f.out, 'vendor/rikka-plugin-host/distribution/product.json'), 'utf8')), product());
  const provenance = await exportSource({ repo: f.repo, workspace: f.out, out: join(f.root, 'source-delivery') });
  assert.equal(provenance.productDigest, sha);
  lock.productDigest = 'cd'.repeat(32);
  await writeFile(join(f.out, '.git/hostctl.json'), JSON.stringify(lock));
  await assert.rejects(
    exportSource({ repo: f.repo, workspace: f.out, out: join(f.root, 'wrong-source') }),
    /provenance mismatch.*productDigest/i,
  );
});

for (const [label, change] of [
  ['official application identity', p => ({ ...p, applicationId: 'me.rerere.rikkahub' })],
  ['invalid application identity', p => ({ ...p, applicationId: 'io.github.bad-name.rikka' })],
  ['shared official deep-link scheme', p => ({ ...p, deepLinkScheme: 'rikkahub' })],
  ['malformed URI scheme', p => ({ ...p, deepLinkScheme: 'my app:' })],
  ['zero versionCode', p => ({ ...p, versionCode: 0 })],
  ['too large Android versionCode', p => ({ ...p, versionCode: 2_100_000_001 })],
  ['fractional versionCode', p => ({ ...p, versionCode: 1.5 })],
  ['versionName control character', p => ({ ...p, versionName: '3.3.0\nplugins' })],
  ['blank display name', p => ({ ...p, name: ' ' })],
  ['C0 display-name control character', p => ({ ...p, name: 'foo\u001fbar' })],
  ['C1 display-name control character', p => ({ ...p, name: 'foo\u0080bar' })],
  ['untrusted APK certificate', p => ({ ...p, signingCertificateSha256: '' })],
  ['malformed APK certificate', p => ({ ...p, signingCertificateSha256: 'aa:bb' })],
  ['plugin index reused for APK updates', p => ({ ...p, updates: { ...p.updates, assetName: 'release.json' } })],
  ['malformed update repository', p => ({ ...p, updates: { ...p.updates, repository: 'https://evil.example/repo' } })],
  ['unexpected top-level field', p => ({ ...p, storePassword: 'not-a-secret-fixture' })],
  ['unexpected update field', p => ({ ...p, updates: { ...p.updates, token: 'not-a-secret-fixture' } })],
  ['unsupported schema', p => ({ ...p, schemaVersion: 2 })],
]) {
  test(`distribution rejects ${label} before assembly`, async t => {
    const f = await fixture(t, change(product()));
    assert.throws(() => loadInputs(f.repo, f.profile), /Invalid product/i);
  });
}

test('a configured product must be explicitly mapped to the immutable assembly location', async t => {
  const f = await fixture(t, product(), { mapped: false });
  assert.throws(() => loadInputs(f.repo, f.profile), /Product.*source map/i);
});

test('mapping a distribution without the profile contract is rejected rather than silently bypassing validation', async t => {
  const f = await fixture(t, product(), { mapped: true, configured: false });
  assert.throws(() => loadInputs(f.repo, f.profile), /Product.*source map/i);
});

test('legacy profiles with no distribution contract keep their original source and lock shape', async t => {
  const f = await fixture(t, product(), { mapped: false, configured: false });
  const inputs = loadInputs(f.repo, f.profile);
  assert.equal(inputs.product, undefined);
  assert.equal(inputs.productDigest, undefined);
  const lock = await prepare(f);
  assert.equal('productDigest' in lock, false);
});
