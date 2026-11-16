import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { verifyRuntimeReports } from '../runtime-reports.mjs';

test('runtime evidence requires a completed invocation and explicit instrumentation success', async t => {
  const root = await mkdtemp(join(tmpdir(), 'runtime reports '));
  t.after(() => rm(root, { recursive: true, force: true }));
  const receipt = { schemaVersion: 1, id: 'invocation', startedAt: 1, completedAt: 2, reports: [] };
  for (const state of [
    { exitCode: 1, instrumentationExitCode: 0 },
    { exitCode: 0, instrumentationExitCode: 1 },
    { exitCode: 0 },
  ]) {
    await writeFile(join(root, 'invocation.json'), JSON.stringify({ ...receipt, ...state }));
    await assert.rejects(verifyRuntimeReports(root), /not complete/);
  }
});

test('changed invocation reports cannot be silently replaced with another successful run', async t => {
  const root = await mkdtemp(join(tmpdir(), 'runtime reports '));
  t.after(() => rm(root, { recursive: true, force: true }));
  await writeFile(join(root, 'one.xml'), '<testsuite tests="0"/>');
  await writeFile(join(root, 'invocation.json'), JSON.stringify({
    schemaVersion: 1, id: 'invocation', startedAt: 1, completedAt: 2, exitCode: 0, instrumentationExitCode: 0,
    reports: [{ path: 'one.xml', kind: 'jvm', sha256: 'wrong' }],
  }));
  await assert.rejects(verifyRuntimeReports(root), /digest mismatch/);
});
