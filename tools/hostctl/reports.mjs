import { SaxesParser } from 'saxes';

/** Deliberately accepts only the flat JUnit suites emitted by our Gradle tasks. */
export function parseJUnit(xml) {
  const parser = new SaxesParser();
  const cases = [];
  let current;
  let suite;
  parser.on('doctype', () => { throw new Error('JUnit reports must not contain a DTD'); });
  parser.on('opentag', tag => {
    if (tag.name === 'testsuite') {
      if (suite) throw new Error('Nested test suites are unsupported');
      suite = { expected: Number(tag.attributes.tests), start: cases.length };
    }
    if (tag.name === 'testcase') {
      if (!suite || current || !tag.attributes.classname || !tag.attributes.name) throw new Error('Invalid testcase');
      current = { id: `${tag.attributes.classname}#${tag.attributes.name}`, passed: true };
    }
    if (['failure', 'error', 'skipped'].includes(tag.name)) {
      if (!current) throw new Error('Failure outside a test case');
      current.passed = false;
    }
  });
  parser.on('closetag', tag => {
    if (tag.name === 'testcase') {
      cases.push(current);
      current = undefined;
    }
    if (tag.name === 'testsuite') {
      if (!Number.isInteger(suite.expected) || cases.length - suite.start !== suite.expected) {
        throw new Error('JUnit case count does not match suite count');
      }
      suite = undefined;
    }
  });
  parser.write(xml).close();
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
