import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseJUnit, requireCases } from '../reports.mjs';

test('requires observed, successful test cases rather than successful task status', () => {
  const report = parseJUnit('<testsuite tests="1"><testcase classname="RuntimeTest" name="closes"/></testsuite>');
  assert.deepEqual(report, [{ id: 'RuntimeTest#closes', passed: true }]);
  requireCases(report, ['RuntimeTest#closes']);
  assert.throws(() => requireCases([], ['RuntimeTest#closes']), /Missing/);
});

test('failure and skipped cases cannot satisfy the verification gate', () => {
  for (const status of ['failure', 'error', 'skipped']) {
    const report = parseJUnit(`<testsuite tests="1"><testcase classname="T" name="x"><${status}/></testcase></testsuite>`);
    assert.throws(() => requireCases(report, ['T#x']), /not pass/);
  }
});

test('malformed, deceptive counts and duplicate cases fail explicitly', () => {
  assert.throws(() => parseJUnit('<testsuite>'), /unexpected|unclosed/i);
  assert.throws(() => parseJUnit('<testsuite tests="6"/>'), /count/i);
  const duplicate = parseJUnit('<testsuite tests="2"><testcase classname="T" name="x"/><testcase classname="T" name="x"/></testsuite>');
  assert.throws(() => requireCases(duplicate, ['T#x']), /Duplicate/);
});
