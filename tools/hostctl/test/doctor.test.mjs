import assert from 'node:assert/strict';
import { test } from 'node:test';
import { probeVersion } from '../doctor.mjs';

test('doctor reads stderr-only Java version and checks the declared profile', () => {
  const run = () => ({ status: 0, stdout: '', stderr: 'openjdk version "21.0.3" 2024-04-16' });
  const pattern = /version "([^"]+)"/;
  assert.equal(probeVersion('java', ['-version'], '21.0.3', pattern, run).compliance, 'match');
  assert.equal(probeVersion('java', ['-version'], '17.0.1', pattern, run).compliance, 'mismatch');
  assert.equal(probeVersion('java', ['-version'], undefined, pattern, run).compliance, 'unchecked');
  assert.equal(probeVersion('java', [], '21.0.3', pattern, () => ({ status: 1, stderr: 'failed' })).available, false);
});
