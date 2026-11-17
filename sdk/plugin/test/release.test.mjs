import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, createPublicKey, verify } from 'node:crypto';
import { mkdtemp, mkdir, readFile, rm, writeFile, symlink } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { buildRelease, signIndex, verifyRelease } from '../release.mjs';

async function fixture(work) {
  const dir = await mkdtemp(join(tmpdir(), 'rikka-release-'));
  try {
    await mkdir(join(dir, 'src'));
    await writeFile(join(dir, 'src/index.ts'), 'globalThis.rikkaPlugin = () => {};');
    await writeFile(join(dir, 'manifest.json'), JSON.stringify({ id: 'dev.example.release', name: 'Example', version: '1.2.3', apiVersion: '1', runtime: 'quickjs-es2020' }));
    await writeFile(join(dir, 'package.json'), JSON.stringify({ name: 'example-plugin', version: '1.2.3', private: true }));
    await work(dir);
  } finally { await rm(dir, { recursive: true, force: true }); }
}

test('release index binds reproducible bytes and requires agreement between tag, package and manifest', () => fixture(async dir => {
  const first = await buildRelease(dir, 'v1.2.3');
  const second = await buildRelease(dir, 'v1.2.3');
  assert.deepEqual(first.index, second.index);
  assert.equal(first.prerelease, false);
  const entry = JSON.parse(first.index).packages[0];
  assert.equal(entry.version, '1.2.3');
  assert.equal(entry.sizeBytes, first.assets[entry.asset].length);
  assert.equal(verifyRelease(first.index, first.assets).packages[0].id, 'dev.example.release');
  await assert.rejects(buildRelease(dir, 'v1.2.4'), /version/i);
  await writeFile(join(dir, 'package.json'), JSON.stringify({ version: '1.2.4' }));
  await assert.rejects(buildRelease(dir, 'v1.2.3'), /version/i);
  const damaged = { ...first.assets, [entry.asset]: Buffer.from('replaced') };
  assert.throws(() => verifyRelease(first.index, damaged), /size|hash/i);
}));

test('ES256 signature verifies over exact index bytes and refuses a different curve', () => fixture(async dir => {
  const release = await buildRelease(dir, 'v1.2.3');
  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const envelope = signIndex(release.index, privateKey);
  const signed = JSON.parse(envelope);
  assert.equal(signed.algorithm, 'ES256');
  const publicKey = createPublicKey({ key: Buffer.from(signed.publicKey, 'base64'), format: 'der', type: 'spki' });
  assert.ok(verify('sha256', release.index, publicKey, Buffer.from(signed.signature, 'base64')));
  verifyRelease(release.index, release.assets, envelope);
  assert.throws(() => verifyRelease(Buffer.concat([release.index, Buffer.from(' ')]), release.assets, envelope), /signature/i);
  const other = generateKeyPairSync('ec', { namedCurve: 'secp384r1' });
  assert.throws(() => signIndex(release.index, other.privateKey), /P-256/);
}));

test('CLI launched through the package manager directory link produces real release assets', () => fixture(async dir => {
  const link = join(dir, 'linked-sdk');
  await symlink(fileURLToPath(new URL('..', import.meta.url)), link, 'junction');
  execFileSync(process.execPath, [join(link, 'release.mjs'), 'build', dir, join(dir, 'release')], {
    env: { ...process.env, RIKKA_RELEASE_TAG: 'v1.2.3' }, stdio: 'pipe',
  });
  const index = JSON.parse(await readFile(join(dir, 'release', 'release.json')));
  assert.equal(index.packages[0].version, '1.2.3');
  assert.equal((await readFile(join(dir, 'release', index.packages[0].asset))).length, index.packages[0].sizeBytes);
}));

test('strict SemVer and prerelease channel survive the build', () => fixture(async dir => {
  const manifest = JSON.parse(await readFile(join(dir, 'manifest.json')));
  for (const version of ['01.2.3', '1.2.3-01', '1.2.3-a..b']) {
    await writeFile(join(dir, 'manifest.json'), JSON.stringify({ ...manifest, version }));
    await writeFile(join(dir, 'package.json'), JSON.stringify({ version }));
    await assert.rejects(buildRelease(dir, `v${version}`), /version/i);
  }
  const version = '1.2.3-rc.1+build';
  await writeFile(join(dir, 'manifest.json'), JSON.stringify({ ...manifest, version }));
  await writeFile(join(dir, 'package.json'), JSON.stringify({ version }));
  assert.equal((await buildRelease(dir, `v${version}`)).prerelease, true);
}));
