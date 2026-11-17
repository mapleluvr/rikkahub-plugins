import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { unzipSync } from 'fflate';
import { buildPackage } from '../build.mjs';

const root = fileURLToPath(new URL('../../../', import.meta.url));

for (const example of ['context-reduce', 'review-report']) {
  test(`SDK builds ${example} reproducibly and matches the device fixture`, async () => {
    const directory = join(root, 'examples', example);
    const first = await buildPackage(directory);
    const second = await buildPackage(directory);
    assert.deepEqual(first.archive, second.archive);
    assert.equal(first.sha256, second.sha256);
    assert.deepEqual(Buffer.from(first.archive), await readFile(join(root,
      'adapters/rikka/app/src/androidTest/assets/plugins', `${example}.rikkaplug`)));
    const files = unzipSync(first.archive);
    assert.deepEqual(Object.keys(files).sort(), ['main.js', 'manifest.json']);
    assert.match(Buffer.from(files['main.js']).toString('utf8'), /rikkaPlugin/);
  });
}

test('packager refuses resource traversal and unsupported Node dependencies', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'rikkaplug-build-'));
  try {
    await mkdir(join(directory, 'src'));
    await writeFile(join(directory, 'src/index.ts'), 'globalThis.rikkaPlugin = () => {};');
    const manifest = { id: 'dev.example.invalid', version: '0.1.0', resources: ['../outside.txt'] };
    await writeFile(join(directory, 'manifest.json'), JSON.stringify(manifest));
    await assert.rejects(buildPackage(directory), /paths/);
    await writeFile(join(directory, 'manifest.json'), JSON.stringify({ ...manifest, resources: [] }));
    await writeFile(join(directory, 'src/index.ts'), 'import fs from "node:fs"; globalThis.rikkaPlugin = () => fs.readFileSync("secret");');
    await assert.rejects(buildPackage(directory), /Could not resolve "node:fs"/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
