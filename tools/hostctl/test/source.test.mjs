import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, rm, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { test } from 'node:test';
import { prepare } from '../workspace.mjs';
import { exportSource } from '../source.mjs';

const git = (cwd, ...args) => execFileSync('git', ['-C', cwd, ...args], { encoding: 'utf8' }).trim();
async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'host source '));
  t.after(() => rm(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 }));
  const source = join(root, 'upstream'), repo = join(root, 'maintenance');
  for (const dir of [source, repo]) {
    await mkdir(dir); git(dir, 'init', '-b', 'main');
    git(dir, 'config', 'user.name', 'Fixture'); git(dir, 'config', 'user.email', 'fixture@example.invalid');
    git(dir, 'config', 'core.autocrlf', 'false');
  }
  await writeFile(join(source, '.gitignore'), 'local.properties\n*.keystore\n');
  await writeFile(join(source, '.gitattributes'), 'required.kt export-ignore\n');
  await writeFile(join(source, 'required.kt'), 'complete application source\n');
  await mkdir(join(source, 'nested'));
  await writeFile(join(source, 'nested/.gitattributes'), '* text eol=crlf\nrequired.kt export-ignore export-subst\n');
  await writeFile(join(source, 'nested/required.kt'), 'unchanged $Format:%H$\n');
  git(source, 'add', '.'); git(source, 'commit', '-m', 'upstream');
  const upstream = git(source, 'rev-parse', 'HEAD');
  await mkdir(join(repo, 'profiles/test'), { recursive: true });
  await mkdir(join(repo, 'patches/test'), { recursive: true });
  const inputs = {
    'profiles/test/profile.json': { schemaVersion: 1, upstream: { url: source, commit: upstream }, series: 'patches/test/series.json', sourceMap: 'profiles/test/source-map.json', toolchain: 'profiles/test/toolchain.json' },
    'profiles/test/source-map.json': { schemaVersion: 1, imports: [] },
    'profiles/test/toolchain.json': { schemaVersion: 1 },
    'patches/test/series.json': { schemaVersion: 1, patches: [] },
  };
  for (const [path, data] of Object.entries(inputs)) await writeFile(join(repo, path), JSON.stringify(data));
  git(repo, 'add', '.'); git(repo, 'commit', '-m', 'maintenance inputs');
  const workspace = join(root, 'assembled');
  const lock = await prepare({ repo, source, out: workspace, profile: 'test' });
  return { root, repo, workspace, lock, out: join(root, 'delivery') };
}

test('source delivery contains the full tracked tree and toolkit, never ignored local secrets, with reproducible hashes', async t => {
  const f = await fixture(t);
  await writeFile(join(f.workspace, 'local.properties'), 'private signing configuration');
  await writeFile(join(f.workspace, 'local.keystore'), 'private key');
  const first = await exportSource(f);
  const second = await exportSource({ ...f, out: join(f.root, 'repeat') });
  assert.deepEqual(second, first);
  assert.equal(first.sourceTree, f.lock.sourceTree);
  assert.equal(first.maintenanceCommit, f.lock.maintenanceCommit);
  const archive = join(f.out, 'application-source.tar');
  const paths = execFileSync('tar', ['-tf', archive], { encoding: 'utf8' });
  assert.match(paths, /rikka\/required\.kt/);
  assert.doesNotMatch(paths, /local\.properties|\.keystore|\.git\//);
  assert.match(paths, /rikka\/nested\/required\.kt/);
  assert.equal(execFileSync('tar', ['-xOf', archive, 'rikka/nested/required.kt'], { encoding: 'utf8' }), 'unchanged $Format:%H$\n');
  assert.equal(execFileSync('tar', ['-xOf', archive, 'rikka/required.kt'], { encoding: 'utf8' }), 'complete application source\n');
  const maintenance = execFileSync('tar', ['-tf', join(f.out, 'maintenance-source.tar')], { encoding: 'utf8' });
  assert.match(maintenance, /plugin-host\/profiles\/test\/profile\.json/);
  assert.deepEqual(JSON.parse(await readFile(join(f.out, 'source-provenance.json'), 'utf8')), first);
});

for (const committed of [false, true]) {
  test(`source delivery refuses ${committed ? 'committed' : 'uncommitted'} assembly drift`, async t => {
    const f = await fixture(t);
    await writeFile(join(f.workspace, 'required.kt'), 'unrecorded edit');
    if (committed) { git(f.workspace, 'add', '.'); git(f.workspace, 'commit', '-m', 'unexported'); }
    await assert.rejects(exportSource(f), /drift|clean/);
    assert.ok(!(await readdir(f.root)).includes('delivery'));
  });
}

test('source delivery refuses stale maintenance identity and existing destinations without changing either', async t => {
  const f = await fixture(t);
  await mkdir(f.out); await writeFile(join(f.out, 'keep.txt'), 'keep');
  await assert.rejects(exportSource(f), /exists/);
  assert.equal(await readFile(join(f.out, 'keep.txt'), 'utf8'), 'keep');
  await writeFile(join(f.repo, 'new.txt'), 'new inputs');
  git(f.repo, 'add', '.'); git(f.repo, 'commit', '-m', 'next maintenance');
  await assert.rejects(exportSource({ ...f, out: join(f.root, 'fresh') }), /maintenance/i);
});
