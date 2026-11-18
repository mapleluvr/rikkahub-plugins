import { readFileSync, lstatSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { assertApkProduct, inspectApk } from './apk.mjs';
import { digest } from './inputs.mjs';

const digestPattern = /^[a-f0-9]{64}$/;
const abiOrder = ['arm64-v8a', 'x86_64', 'universal'];
const manifestDigests = ['profileDigest', 'patchsetDigest', 'sourceMapDigest', 'toolchainDigest', 'productDigest'];

function boundedFile(path, limit) {
  const stat = lstatSync(path);
  if (!stat.isFile() || stat.size > limit) throw new Error(`Expected a bounded regular file: ${path}`);
  const bytes = readFileSync(path);
  if (bytes.length > limit) throw new Error(`File changed size: ${path}`);
  return bytes;
}

function releaseAbi(abis) {
  const ordered = [...abis].sort();
  if (ordered.length === 1 && abiOrder.slice(0, 2).includes(ordered[0])) return ordered[0];
  if (ordered.join(',') === 'arm64-v8a,x86_64') return 'universal';
  throw new Error('Host APK has an unexpected ABI set');
}

/** Metadata is from the SDK inspection of the final signed APKs. */
export function buildHostReleaseIndex({ inputs, provenanceBytes, notes, apks }) {
  const { product } = inputs;
  const provenance = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(provenanceBytes));
  if (!product || provenance.schemaVersion !== 1 || provenance.profile !== inputs.profile ||
      provenance.maintenanceCommit !== inputs.commit ||
      provenance.upstreamCommit !== inputs.config.upstream.commit ||
      provenance.upstreamUrl !== inputs.config.upstream.url ||
      !/^[a-f0-9]{40}$/.test(provenance.sourceTree) ||
      manifestDigests.some(key => !digestPattern.test(provenance[key] ?? '') || provenance[key] !== inputs[key])) {
    throw new Error('Source provenance does not match the frozen release inputs');
  }
  if (typeof notes !== 'string' || notes.length > 16000) throw new Error('Host release notes are too long');
  const artifacts = apks.map(({ name, apk }) => {
    assertApkProduct(apk, product, { minSdk: inputs.toolchain.minSdk });
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,95}\.apk$/.test(name) ||
        !Number.isSafeInteger(apk.size) || apk.size < 1 || apk.size > 256 * 1024 * 1024 ||
        !digestPattern.test(apk.sha256)) throw new Error('Host APK asset has invalid metadata');
    return { name, abi: releaseAbi(apk.abis), sizeBytes: apk.size, sha256: apk.sha256 };
  });
  if (artifacts.length !== abiOrder.length ||
      abiOrder.some(abi => artifacts.filter(artifact => artifact.abi === abi).length !== 1) ||
      new Set(artifacts.map(artifact => artifact.name.toLowerCase())).size !== artifacts.length) {
    throw new Error('Release needs exactly one APK of each supported ABI and one universal APK');
  }
  return {
    schemaVersion: 1,
    applicationId: product.applicationId,
    certificateSha256: product.signingCertificateSha256,
    versionCode: product.versionCode,
    versionName: product.versionName,
    sourceProvenanceSha256: digest(provenanceBytes),
    notes,
    apks: abiOrder.map(abi => artifacts.find(artifact => artifact.abi === abi)),
  };
}

export function writeHostReleaseIndex({ inputs, apkDir, provenance, notesPath, out, tools }) {
  const names = readdirSync(apkDir).filter(file => file.toLowerCase().endsWith('.apk'));
  if (names.length !== abiOrder.length) throw new Error('The signed APK directory must contain exactly three APK files');
  const apks = names.map(name => ({ name, apk: inspectApk(join(apkDir, name), tools) }));
  const index = buildHostReleaseIndex({
    inputs, apks,
    provenanceBytes: boundedFile(provenance, 64 * 1024),
    notes: new TextDecoder('utf-8', { fatal: true }).decode(boundedFile(notesPath, 64 * 1024)),
  });
  const bytes = Buffer.from(JSON.stringify(index, null, 2) + '\n');
  if (bytes.length > 64 * 1024) throw new Error('Host release index exceeds client size limit');
  writeFileSync(out, bytes, { flag: 'wx' });
  return {
    repository: inputs.product.updates.repository,
    tag: `host-v${index.versionCode}`,
    indexAssetName: inputs.product.updates.assetName,
    indexSha256: digest(bytes),
    apks: index.apks,
  };
}
