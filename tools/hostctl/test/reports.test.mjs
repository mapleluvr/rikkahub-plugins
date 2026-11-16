import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseJUnit, requireCases, requireDevices } from '../reports.mjs';

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

test('suite and root aggregate failures cannot hide behind successful children', () => {
  for (const xml of [
    '<testsuite tests="0" errors="1"/>',
    '<testsuite tests="1" failures="1"><testcase classname="T" name="x"/></testsuite>',
    '<testsuites tests="1" errors="1"><testsuite tests="1"><testcase classname="T" name="x"/></testsuite></testsuites>',
    '<testsuite tests="1" skipped="1"><testcase classname="T" name="x"/></testsuite>',
  ]) assert.throws(() => parseJUnit(xml), /count|summary/i);
});

test('each device independently satisfies the contract without combining old or failed reports', () => {
  const xml = '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="T" name="x"/></testsuite>';
  assert.equal(requireDevices([{ device: 'one', xml }, { device: 'two', xml }], ['T#x']).length, 2);
  assert.throws(() => requireDevices([{ device: 'old', xml }, {
    device: 'current', xml: '<testsuite tests="0" errors="1"/>',
  }], ['T#x']), /summary|count/i);
  assert.throws(() => requireDevices([{ device: 'one', xml }, {
    device: 'two', xml: '<testsuite tests="0"/>',
  }], ['T#x']), /Missing/);
});

test('malformed, deceptive counts and duplicate cases fail explicitly', () => {
  assert.throws(() => parseJUnit('<testsuite>'), /unexpected|unclosed/i);
  assert.throws(() => parseJUnit('<testsuite tests="6"/>'), /count/i);
  const duplicate = parseJUnit('<testsuite tests="2"><testcase classname="T" name="x"/><testcase classname="T" name="x"/></testsuite>');
  assert.throws(() => requireCases(duplicate, ['T#x']), /Duplicate/);
});
