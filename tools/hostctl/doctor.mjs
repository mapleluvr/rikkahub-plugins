import { spawnSync } from 'node:child_process';

export function probeVersion(binary, args, expected, pattern, run = spawnSync) {
  const result = run(binary, args, { encoding: 'utf8', windowsHide: true });
  const output = [result.stdout, result.stderr].filter(Boolean).join('\n').trim();
  const version = output.match(pattern)?.[1] ?? null;
  const available = !result.error && result.status === 0;
  return {
    available, version, expected: expected ?? null,
    compliance: !available ? 'unavailable' : !expected || !version ? 'unchecked' : version === expected ? 'match' : 'mismatch',
    output,
  };
}

export function doctor(expected) {
  return {
    node: { available: true, version: process.versions.node, expected: expected.node,
      compliance: !expected.node ? 'unchecked' : process.versions.node === expected.node ? 'match' : 'mismatch' },
    java: probeVersion('java', ['-version'], expected.java, /(?:java|openjdk) version "([^"]+)"/),
    git: probeVersion('git', ['--version'], undefined, /git version ([^\s]+)/),
    unverified: Object.keys(expected).filter(key => !['schemaVersion', 'node', 'java'].includes(key)),
  };
}
