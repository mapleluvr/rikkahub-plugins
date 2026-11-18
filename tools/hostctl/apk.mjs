import fs from 'node:fs';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';

const MAX_APK_BYTES = 256 * 1024 * 1024;
const digest = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');

/** Parse trusted SDK tool output, not an APK's claimed release index. */
export function parseApkBadging(output) {
  const lines = output.split(/\r?\n/);
  const packages = lines.filter(line => line.startsWith('package: '));
  if (packages.length !== 1) throw new Error('APK must have one package declaration');
  const fields = Object.fromEntries([...packages[0].matchAll(/\b(\w+)='([^']*)'/g)].map(match => [match[1], match[2]]));
  const versionCode = Number(fields.versionCode);
  if (!fields.name || !fields.versionName || !Number.isSafeInteger(versionCode) || versionCode < 1 || versionCode > 2100000000) {
    throw new Error('APK package/version metadata is invalid');
  }
  const minSdk = Number(lines.find(line => line.startsWith('minSdkVersion:'))?.match(/^minSdkVersion:'(\d+)'$/)?.[1]);
  if (!Number.isSafeInteger(minSdk) || minSdk < 1) throw new Error('APK minimum SDK is missing or invalid');
  const label = lines.find(line => line.startsWith('application-label:'))?.match(/^application-label:'(.*)'$/)?.[1];
  if (label === undefined) throw new Error('APK default application label is missing');
  const native = lines.find(line => line.startsWith('native-code:'));
  const abis = native ? [...native.matchAll(/'([^']+)'/g)].map(match => match[1]) : [];
  return { applicationId: fields.name, versionCode, versionName: fields.versionName, minSdk, label, abis };
}

/** Caller must also prove the apksigner process exited successfully. */
export function parseApkCertificates(output) {
  if (!/^Verifies\r?$/m.test(output) || !/^Verified using v[234][^\r\n]*: true\r?$/m.test(output)) {
    throw new Error('APK lacks a verified modern signing scheme');
  }
  const counts = [...output.matchAll(/^Number of signers: (\d+)\r?$/gm)];
  const matches = [...output.matchAll(/^Signer #(\d+) certificate SHA-256 digest: ([a-fA-F0-9]{64})\r?$/gm)];
  if (counts.length !== 1 || counts[0][1] !== '1' || matches.length !== 1 || matches[0][1] !== '1') {
    throw new Error('APK must have exactly one signing certificate');
  }
  return matches[0][2].toLowerCase();
}

export function assertApkIdentity(apk, product, { debug = false, minSdk = 26 } = {}) {
  const expectedId = product.applicationId + (debug ? '.debug' : '');
  if (apk.applicationId !== expectedId) throw new Error('APK application ID differs from the product');
  if (apk.versionCode !== product.versionCode || apk.versionName !== product.versionName) throw new Error('APK version differs from the product');
  if (apk.label !== product.name) throw new Error('APK label differs from the product');
  if (apk.minSdk !== minSdk) throw new Error('APK minimum SDK differs from the supported contract');
  return apk;
}

export function assertApkProduct(apk, product, options = {}) {
  assertApkIdentity(apk, product, options);
  if (!apk.certificateSha256) throw new Error('APK has not passed signature verification');
  if (!options.debug && apk.certificateSha256 !== product.signingCertificateSha256) throw new Error('APK signing certificate differs from the product pin');
  return apk;
}

export function inspectApk(file, { aapt2, java, apksignerJar, signed = true }) {
  const stat = fs.lstatSync(file);
  if (!stat.isFile() || stat.size < 1 || stat.size > MAX_APK_BYTES) throw new Error('APK must be a bounded regular file');
  const sha256 = digest(file);
  const options = { encoding: 'utf8', windowsHide: true, timeout: 60000, maxBuffer: 1024 * 1024 };
  const metadata = parseApkBadging(execFileSync(aapt2, ['dump', 'badging', file], options));
  const certificateSha256 = signed
    ? parseApkCertificates(execFileSync(java, ['-jar', apksignerJar, 'verify', '--verbose', '--print-certs', file], options))
    : null;
  const finalStat = fs.lstatSync(file);
  if (!finalStat.isFile() || finalStat.size !== stat.size || digest(file) !== sha256) throw new Error('APK changed during inspection');
  return { ...metadata, size: stat.size, sha256, certificateSha256 };
}
