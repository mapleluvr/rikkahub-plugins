#!/usr/bin/env node
import { parseArgs } from 'node:util';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { doctor } from './doctor.mjs';
import { loadInputs } from './inputs.mjs';
import { prepare, verifyReplay, exportSeries } from './workspace.mjs';

const repo = fileURLToPath(new URL('../../', import.meta.url));
const { values, positionals } = parseArgs({
  allowPositionals: true,
  options: Object.fromEntries(['profile', 'source', 'out', 'expected', 'workspace'].map(name => [name, { type: 'string' }])),
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
    case 'prepare': result = await prepare(options); break;
    case 'verify-replay':
      if (!options.expected) throw new Error('--expected is required');
      result = await verifyReplay(options); break;
    case 'export':
      if (!options.workspace) throw new Error('--workspace is required');
      result = await exportSeries(options); break;
    default: throw new Error('Usage: hostctl doctor|prepare|verify-replay|export [--profile NAME] [--source PATH] [--out PATH] [--expected PATH] [--workspace PATH]');
  }
  console.log(JSON.stringify(result, null, 2));
} catch (error) {
  console.error(`hostctl: ${error.message}`);
  process.exitCode = 1;
}
