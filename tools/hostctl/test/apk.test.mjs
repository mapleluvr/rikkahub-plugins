import test from 'node:test';
import assert from 'node:assert/strict';
import { assertApkProduct, parseApkBadging, parseApkCertificates } from '../apk.mjs';

const applicationId = 'io.github.mapleluvr.rikkahub';
const name = 'RikkaHub Plugins';
const versionName = '3.3.0-plugins.1';
const signingCertificateSha256 = 'ab'.repeat(32);
const product = { applicationId, name, versionCode: 1, versionName, signingCertificateSha256 };
const badging = [
  `package: name='${applicationId}' versionCode='1' versionName='${versionName}'`,
  "minSdkVersion:'26'",
  `application-label:'${name}'`,
  "native-code: 'arm64-v8a' 'x86_64'",
].join('\r\n');
const verified = [
  'Verifies',
  'Verified using v1 scheme (JAR signing): false',
  'Verified using v2 scheme (APK Signature Scheme v2): true',
  'Number of signers: 1',
  `Signer #1 certificate SHA-256 digest: ${signingCertificateSha256.toUpperCase()}`,
].join('\r\n');

test('Android SDK output supplies the verified package metadata and certificate', () => {
  const apk = { ...parseApkBadging(badging), certificateSha256: parseApkCertificates(verified) };
  assert.deepEqual(apk, {
    applicationId, versionCode: 1, versionName, label: name, minSdk: 26,
    abis: ['arm64-v8a', 'x86_64'], certificateSha256: signingCertificateSha256,
  });
  assert.equal(assertApkProduct(apk, product), apk);
});

test('invalid, legacy-only and ambiguous signatures are rejected', () => {
  for (const value of [
    verified.replace('Verifies', 'DOES NOT VERIFY'),
    verified.replace('v2 scheme (APK Signature Scheme v2): true', 'v2 scheme (APK Signature Scheme v2): false'),
    verified.replace('Number of signers: 1', 'Number of signers: 2'),
    `${verified}\r\nSigner #2 certificate SHA-256 digest: ${'cd'.repeat(32)}`,
    verified.replace('Number of signers: 1\r\n', ''),
  ]) assert.throws(() => parseApkCertificates(value), /verified|certificate/);
});

test('an upstream APK, downgraded metadata, and a wrong certificate cannot pass the release gate', () => {
  const apk = { ...parseApkBadging(badging), certificateSha256: signingCertificateSha256 };
  for (const [change, reason] of [
    [{ applicationId: 'me.rerere.rikkahub' }, /application ID/],
    [{ applicationId: `${applicationId}.debug` }, /application ID/],
    [{ versionCode: 2 }, /version/],
    [{ versionName: '3.3.0' }, /version/],
    [{ label: 'RikkaHub' }, /label/],
    [{ minSdk: 27 }, /minimum SDK/],
    [{ certificateSha256: 'cd'.repeat(32) }, /certificate/],
    [{ certificateSha256: null }, /signature verification/],
  ]) assert.throws(() => assertApkProduct({ ...apk, ...change }, product), reason);
  const debug = { ...apk, applicationId: `${applicationId}.debug`, certificateSha256: 'cd'.repeat(32) };
  assert.equal(assertApkProduct(debug, product, { debug: true }), debug);
  assert.throws(() => assertApkProduct(apk, product, { debug: true }), /application ID/);
});
