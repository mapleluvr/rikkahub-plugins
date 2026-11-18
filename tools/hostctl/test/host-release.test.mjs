import test from 'node:test';
import assert from 'node:assert/strict';
import { buildHostReleaseIndex } from '../host-release.mjs';
import { digest } from '../inputs.mjs';

function fixture() {
  const sha256 = 'ab'.repeat(32);
  const upstream = { commit: 'ab'.repeat(20), url: 'https://github.com/rikkahub/rikkahub.git' };
  const product = {
    name: 'RikkaHub Plugins',
    applicationId: 'io.github.mapleluvr.rikkahub',
    signingCertificateSha256: sha256,
    versionName: '3.3.0-plugins.1',
    versionCode: 1,
  };
  const hashes = Object.fromEntries(
    ['profileDigest', 'patchsetDigest', 'sourceMapDigest', 'toolchainDigest', 'productDigest']
      .map(key => [key, sha256]),
  );
  const inputs = {
    profile: 'rikka-bfac2440', commit: 'cd'.repeat(20), config: { upstream }, product,
    toolchain: { minSdk: 26 }, ...hashes,
  };
  const provenance = {
    schemaVersion: 1, profile: inputs.profile, maintenanceCommit: inputs.commit,
    upstreamCommit: upstream.commit, upstreamUrl: upstream.url, sourceTree: 'ef'.repeat(20), ...hashes,
  };
  const apk = {
    applicationId: product.applicationId, versionCode: product.versionCode, versionName: product.versionName,
    label: product.name, certificateSha256: sha256, minSdk: 26, sha256, size: 1678,
  };
  const apks = [
    { name: 'universal.apk', apk: { ...apk, abis: ['x86_64', 'arm64-v8a'] } },
    { name: 'x86_64.apk', apk: { ...apk, abis: ['x86_64'] } },
    { name: 'arm64.apk', apk: { ...apk, abis: ['arm64-v8a'] } },
  ];
  return { inputs, provenanceBytes: Buffer.from(JSON.stringify(provenance)), notes: 'Release notes', apks };
}

test('host release index binds final APKs and exact source provenance', () => {
  const release = fixture();
  const index = buildHostReleaseIndex(release);
  assert.equal(index.applicationId, release.inputs.product.applicationId);
  assert.equal(index.sourceProvenanceSha256, digest(release.provenanceBytes));
  assert.deepEqual(index.apks.map(artifact => [artifact.abi, artifact.name, artifact.sizeBytes]), [
    ['arm64-v8a', 'arm64.apk', 1678], ['x86_64', 'x86_64.apk', 1678], ['universal', 'universal.apk', 1678],
  ]);
});

test('stale source, mismatched APK identity and ambiguous ABIs cannot produce a host index', () => {
  const stale = fixture();
  stale.inputs.commit = '00'.repeat(20);
  assert.throws(() => buildHostReleaseIndex(stale), /provenance/);
  const badSignature = fixture();
  badSignature.apks[0].apk.certificateSha256 = '00'.repeat(32);
  assert.throws(() => buildHostReleaseIndex(badSignature), /certificate/);
  const wrongVersion = fixture();
  wrongVersion.apks[0].apk.versionCode++;
  assert.throws(() => buildHostReleaseIndex(wrongVersion), /version/);
  const duplicateAbi = fixture();
  duplicateAbi.apks[0].apk.abis = ['x86_64'];
  assert.throws(() => buildHostReleaseIndex(duplicateAbi), /exactly one APK/);
});
