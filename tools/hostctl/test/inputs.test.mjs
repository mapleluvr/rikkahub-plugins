import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { cleanCommit } from '../inputs.mjs';

// A filesystem-monitor cache is a performance hint, not release-input evidence.
test('cleanCommit checks actual changes even when a configured fsmonitor reports none', async () => {
  const repo = await mkdtemp(join(tmpdir(), 'hostctl-fsmonitor-'));
  const rawGit = args => execFileSync('git', ['-C', repo, ...args], { encoding: 'utf8' }).trim();
  try {
    rawGit(['init', '--quiet']);
    rawGit(['config', 'core.autocrlf', 'false']);
    rawGit(['config', 'user.name', 'Hostctl test']);
    rawGit(['config', 'user.email', 'hostctl@example.invalid']);
    const file = join(repo, 'input.txt');
    await writeFile(file, 'original\n');
    rawGit(['add', 'input.txt']);
    rawGit(['commit', '--quiet', '-m', 'initial']);
    const hook = join(repo, '.git', 'unchanged-monitor.mjs');
    await writeFile(hook, 'process.stdout.write("test-token\\0");\n');
    const command = [process.execPath, hook].map(path => JSON.stringify(path.replaceAll('\\', '/'))).join(' ');
    rawGit(['config', 'core.fsmonitor', command]);
    rawGit(['update-index', '--fsmonitor']);
    rawGit(['status', '--porcelain']);
    await writeFile(file, 'modified release input\n');
    assert.equal(rawGit(['status', '--porcelain']), '', 'Fixture must expose the monitor cache false-clean result');
    assert.throws(() => cleanCommit(repo), /clean and committed/);
  } finally {
    await rm(repo, { recursive: true, force: true });
  }
});
