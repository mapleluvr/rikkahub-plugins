import { SaxesParser } from 'saxes';

const emptyCounts = () => ({ tests: 0, failures: 0, errors: 0, skipped: 0 });

function checkSummary(attributes, actual) {
  for (const name of Object.keys(actual)) {
    const value = attributes[name];
    if (value === undefined && name !== 'tests') continue;
    if (!/^\d+$/.test(value ?? '') || Number(value) !== actual[name]) {
      throw new Error(`JUnit summary count mismatch for ${name}`);
    }
  }
}

/** Flat Gradle suites, optionally enclosed in a testsuites aggregate. */
export function parseJUnit(xml) {
  const parser = new SaxesParser();
  const cases = [];
  const totals = emptyCounts();
  let current;
  let suite;
  let root;
  let suiteCount = 0;
  parser.on('doctype', () => { throw new Error('JUnit reports must not contain a DTD'); });
  parser.on('opentag', tag => {
    if (tag.name === 'testsuites') {
      if (root || suite) throw new Error('Nested test aggregates are unsupported');
      root = tag.attributes;
    }
    if (tag.name === 'testsuite') {
      if (suite) throw new Error('Nested test suites are unsupported');
      suite = { attributes: tag.attributes, counts: emptyCounts() };
      suiteCount++;
    }
    if (tag.name === 'testcase') {
      if (!suite || current || !tag.attributes.classname || !tag.attributes.name) throw new Error('Invalid testcase');
      current = { id: `${tag.attributes.classname}#${tag.attributes.name}`, outcomes: new Set() };
    }
    if (['failure', 'error', 'skipped'].includes(tag.name)) {
      if (!current) throw new Error('Failure outside a test case');
      current.outcomes.add({ failure: 'failures', error: 'errors', skipped: 'skipped' }[tag.name]);
    }
  });
  parser.on('closetag', tag => {
    if (tag.name === 'testcase') {
      suite.counts.tests++;
      for (const outcome of current.outcomes) suite.counts[outcome]++;
      cases.push({ id: current.id, passed: current.outcomes.size === 0 });
      current = undefined;
    }
    if (tag.name === 'testsuite') {
      checkSummary(suite.attributes, suite.counts);
      for (const name of Object.keys(totals)) totals[name] += suite.counts[name];
      suite = undefined;
    }
    if (tag.name === 'testsuites') checkSummary(root, totals);
  });
  parser.write(xml).close();
  if (suiteCount === 0) throw new Error('JUnit report contains no suites');
  return cases;
}

export function requireCases(cases, required) {
  const ids = new Set();
  for (const item of cases) {
    if (ids.has(item.id)) throw new Error(`Duplicate test case: ${item.id}`);
    ids.add(item.id);
    if (!item.passed) throw new Error(`Test did not pass: ${item.id}`);
  }
  for (const id of required) {
    if (!ids.has(id)) throw new Error(`Missing executed test: ${id}`);
  }
  return { executed: cases.length, passed: cases.length };
}

/** AGP emits one XML per device. Every device must execute the entire contract. */
export function requireDevices(reports, required) {
  if (reports.length === 0) throw new Error('Missing device reports');
  const devices = new Set();
  return reports.map(({ device, xml }) => {
    if (!device || devices.has(device)) throw new Error(`Duplicate or missing device identity: ${device}`);
    devices.add(device);
    return { device, ...requireCases(parseJUnit(xml), required) };
  });
}
