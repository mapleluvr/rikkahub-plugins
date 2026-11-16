#!/usr/bin/env node
import { parseArgs } from 'node:util';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { execFileSync } from 'node:child_process';
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
      const checks = {};
      for (const [name, executable, args] of [
        ['git', 'git', ['--version']], ['java', 'java', ['-version']],
      ]) {
        try {
          checks[name] = { available: true, output: execFileSync(executable, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim() };
        } catch (error) { checks[name] = { available: false, error: error.message }; }
      }
      result = { profile: options.profile, node: process.versions.node, checks, expected: inputs.toolchain };
      if (Object.values(checks).some(check => !check.available)) process.exitCode = 1;
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
