import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { test } from 'node:test';
import { prepare, verifyReplay, exportSeries } from '../workspace.mjs';

function git(cwd, ...args) {
  return execFileSync('git', ['-C', cwd, ...args], { encoding: 'utf8' }).trim();
}
async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'hostctl test '));
  t.after(() => rm(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 }));
  const source = join(root, 'source');
  const repo = join(root, 'maintenance');
  for (const dir of [source, repo]) {
    await mkdir(dir);
    git(dir, 'init', '-b', 'main');
    git(dir, 'config', 'user.name', 'Fixture');
    git(dir, 'config', 'user.email', 'fixture@example.invalid');
    git(dir, 'config', 'core.autocrlf', 'false');
  }
  await writeFile(join(source, 'entry.txt'), 'upstream\n');
  git(source, 'add', '.');
  git(source, 'commit', '-m', 'upstream');
  const upstream = git(source, 'rev-parse', 'HEAD');
  const put = async (path, data) => {
    const target = join(repo, path);
    await mkdir(join(target, '..'), { recursive: true });
    await writeFile(target, typeof data === 'string' ? data : JSON.stringify(data));
  };
  await put('profiles/test/profile.json', { schemaVersion: 1, upstream: { url: source, commit: upstream }, series: 'patches/test/series.json', sourceMap: 'profiles/test/source-map.json', toolchain: 'profiles/test/toolchain.json' });
  await put('profiles/test/source-map.json', { schemaVersion: 1, imports: [{ from: 'platform/example.txt', to: 'vendor/rikka-plugin-host/example.txt' }] });
  await put('platform/example.txt', 'platform\n');
  await put('profiles/test/toolchain.json', { schemaVersion: 1, node: process.versions.node });
  await put('patches/test/series.json', { schemaVersion: 1, patches: [] });
  const commit = () => { git(repo, 'add', '.'); git(repo, 'commit', '-m', 'inputs'); };
  commit();
  const options = { repo, profile: 'test', source, out: join(root, 'integration') };
  return { root, repo, source, upstream, put, commit, options };
}

test('assembles fixed input and reproduces the same source tree in another checkout', async t => {
  const f = await fixture(t);
  await writeFile(join(f.source, 'entry.txt'), 'uncommitted user work\n');
  const lock = await prepare(f.options);
  assert.equal(await readFile(join(f.options.out, 'entry.txt'), 'utf8'), 'upstream\n');
  assert.equal(await readFile(join(f.options.out, 'vendor/rikka-plugin-host/example.txt'), 'utf8'), 'platform\n');
  assert.equal(lock.upstreamCommit, f.upstream);
  assert.equal(lock.maintenanceCommit, git(f.repo, 'rev-parse', 'HEAD'));
  const replay = await verifyReplay({ ...f.options, out: join(f.root, 'replay'), expected: f.options.out });
  assert.equal(replay.sourceTree, lock.sourceTree);
});

test('exports actual host changes and verifies their tree before replacing the series', async t => {
  const f = await fixture(t);
  await prepare(f.options);
  await writeFile(join(f.options.out, 'entry.txt'), 'host seam\n');
  git(f.options.out, 'add', 'entry.txt');
  git(f.options.out, 'commit', '-m', 'Expose host seam', '-m', 'Patch-Id: host-seam');
  const result = await exportSeries({ repo: f.repo, workspace: f.options.out });
  assert.equal(result.patches.length, 1);
  assert.equal(result.sourceTree, git(f.options.out, 'rev-parse', 'HEAD^{tree}'));
  f.commit();
  const lock = await prepare({ ...f.options, out: join(f.root, 'replayed') });
  assert.equal(lock.sourceTree, result.sourceTree);
  assert.equal(await readFile(join(f.root, 'replayed/entry.txt'), 'utf8'), 'host seam\n');
});

test('refuses an existing destination without touching its contents', async t => {
  const f = await fixture(t);
  await mkdir(f.options.out);
  await writeFile(join(f.options.out, 'user.txt'), 'keep');
  await assert.rejects(prepare(f.options), /exists/);
  assert.equal(await readFile(join(f.options.out, 'user.txt'), 'utf8'), 'keep');
});

test('rejects dirty maintenance inputs instead of mislabelling the build', async t => {
  const f = await fixture(t);
  await f.put('platform/example.txt', 'dirty');
  await assert.rejects(prepare(f.options), /clean/);
});

for (const target of ['../escape', 'C:/outside', 'vendor/../entry.txt', '.git/config', 'entry.txt']) {
  test(`rejects unsafe or colliding import target ${target}`, async t => {
    const f = await fixture(t);
    await f.put('profiles/test/source-map.json', { schemaVersion: 1, imports: [{ from: 'platform/example.txt', to: target }] });
    f.commit();
    await assert.rejects(prepare(f.options), /path|collision|reserved|vendor/i);
  });
}

test('rejects changes to imported platform files during patch export', async t => {
  const f = await fixture(t);
  await prepare(f.options);
  await writeFile(join(f.options.out, 'vendor/rikka-plugin-host/example.txt'), 'wrong editor\n');
  git(f.options.out, 'add', '.');
  git(f.options.out, 'commit', '-m', 'Wrong source', '-m', 'Patch-Id: wrong');
  await assert.rejects(exportSeries({ repo: f.repo, workspace: f.options.out }), /imported|vendor/);
});
