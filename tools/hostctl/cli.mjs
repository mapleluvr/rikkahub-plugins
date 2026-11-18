#!/usr/bin/env node
import { parseArgs } from 'node:util';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { doctor } from './doctor.mjs';
import { exportSource } from './source.mjs';
import { loadInputs } from './inputs.mjs';
import { assertApkIdentity, assertApkProduct, inspectApk } from './apk.mjs';
import { writeHostReleaseIndex } from './host-release.mjs';
import { prepare, verifyReplay, exportSeries, observeUpstream } from './workspace.mjs';

const repo = fileURLToPath(new URL('../../', import.meta.url));
const { values, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    ...Object.fromEntries(['profile', 'source', 'out', 'expected', 'workspace', 'apk', 'aapt2', 'java', 'apksigner-jar', 'apk-dir', 'provenance', 'notes', 'branch']
      .map(name => [name, { type: 'string' }])),
    debug: { type: 'boolean' },
  },
});
const [command] = positionals;
const options = { repo, profile: values.profile ?? 'rikka-bfac2440', ...values };
for (const key of ['source', 'out', 'expected', 'workspace']) {
  if (options[key]) options[key] = resolve(options[key]);
}

try {
  let result;
  switch (command) {
    case 'doctor': {
      const inputs = loadInputs(repo, options.profile);
      const checks = doctor(inputs.toolchain);
      result = { profile: options.profile, checks, expected: inputs.toolchain };
      if (['node', 'java', 'git'].some(key =>
        ['unavailable', 'mismatch'].includes(checks[key].compliance))) process.exitCode = 1;
      break;
    }
    case 'source': result = await exportSource(options); break;
    case 'inspect-unsigned-apk':
    case 'verify-apk': {
      const signed = command === 'verify-apk';
      if (!signed && values.debug) throw new Error('Unsigned inspection is release-only');
      for (const key of ['apk', 'aapt2', ...(signed ? ['java', 'apksigner-jar'] : [])]) {
        if (!values[key]) throw new Error(`--${key} is required`);
      }
      const inputs = loadInputs(repo, options.profile);
      if (!inputs.product) throw new Error('Profile has no distribution product');
      const apk = inspectApk(resolve(values.apk), {
        aapt2: resolve(values.aapt2), signed,
        ...(signed ? { java: resolve(values.java), apksignerJar: resolve(values['apksigner-jar']) } : {}),
      });
      const assertProduct = signed ? assertApkProduct : assertApkIdentity;
      assertProduct(apk, inputs.product, { debug: values.debug, minSdk: inputs.toolchain.minSdk });
      result = { profile: inputs.profile, productDigest: inputs.productDigest, apk };
      break;
    }
    case 'host-release-index': {
      for (const key of ['apk-dir', 'provenance', 'notes', 'out', 'aapt2', 'java', 'apksigner-jar']) {
        if (!values[key]) throw new Error(`--${key} is required`);
      }
      result = writeHostReleaseIndex({
        inputs: loadInputs(repo, options.profile),
        apkDir: resolve(values['apk-dir']),
        provenance: resolve(values.provenance),
        notesPath: resolve(values.notes),
        out: options.out,
        tools: {
          aapt2: resolve(values.aapt2), java: resolve(values.java),
          apksignerJar: resolve(values['apksigner-jar']),
        },
      });
      break;
    }
    case 'observe-upstream': result = await observeUpstream(options); break;
    case 'prepare': result = await prepare(options); break;
    case 'verify-replay':
      if (!options.expected) throw new Error('--expected is required');
      result = await verifyReplay(options); break;
    case 'export':
      if (!options.workspace) throw new Error('--workspace is required');
      result = await exportSeries(options); break;
    default: throw new Error('Usage: hostctl doctor|prepare|verify-replay|export|source|inspect-unsigned-apk|verify-apk|host-release-index|observe-upstream [--profile NAME]');
  }
  console.log(JSON.stringify(result, null, 2));
} catch (error) {
  console.error(`hostctl: ${error.message}`);
  process.exitCode = 1;
}
