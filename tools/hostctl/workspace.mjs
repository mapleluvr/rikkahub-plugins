import { mkdir, readFile, writeFile, lstat, mkdtemp, rm, rename, readdir } from 'node:fs/promises';
import { join, resolve, dirname, basename } from 'node:path';
import { tmpdir } from 'node:os';
import { git, digest, loadInputs, safePath, cleanCommit } from './inputs.mjs';

const commitArgs = ['-c', 'user.name=Host assembly', '-c', 'user.email=host-assembly@example.invalid'];

async function exists(path) {
  try { await lstat(path); return true; } catch (error) {
    if (error.code === 'ENOENT') return false;
    throw error;
  }
}

async function readWorkspace(workspace) {
  const metadata = JSON.parse(await readFile(join(workspace, '.git/hostctl.json'), 'utf8'));
  if (metadata.schemaVersion !== 1) throw new Error('Unsupported workspace metadata');
  return metadata;
}

/** Creates an owned, disposable clone. Failures deliberately preserve evidence. */
async function assemble(inputs, { source, out }) {
  out = resolve(out);
  if (await exists(out)) throw new Error(`Destination already exists: ${out}`);
  await mkdir(dirname(out), { recursive: true });
  git(dirname(out), ['clone', '--no-hardlinks', '--no-checkout', '--', source ?? inputs.config.upstream.url, out]);
  git(out, ['config', 'core.autocrlf', 'false']);
  git(out, ['checkout', '--detach', inputs.config.upstream.commit]);
  git(out, ['config', 'user.name', 'Host assembly']);
  git(out, ['config', 'user.email', 'host-assembly@example.invalid']);
  // Reserve the namespace before writing any imports. A future upstream collision is explicit.
  if (await exists(join(out, 'vendor/rikka-plugin-host'))) throw new Error('Imported vendor path collision');
  const occupied = new Set(git(out, ['ls-tree', '-r', '--name-only', 'HEAD']).split('\n').map(p => p.toLowerCase()));
  for (const entry of inputs.imports) {
    const path = entry.to.toLowerCase();
    if ([...occupied].some(p => p === path || p.startsWith(`${path}/`) || path.startsWith(`${p}/`))) {
      throw new Error(`Source import path collision: ${entry.to}`);
    }
    occupied.add(path);
  }
  for (const entry of inputs.imports) {
    const target = join(out, entry.to);
    await mkdir(dirname(target), { recursive: true });
    await writeFile(target, entry.bytes);
    git(out, ['add', '--', entry.to]);
  }
  git(out, [...commitArgs, 'commit', '--allow-empty', '-m', 'Import frozen plugin host sources']);
  const adapterBase = git(out, ['rev-parse', 'HEAD']);
  for (const patch of inputs.patches) {
    // stdin avoids line-ending rewriting and shell expansion of patch bytes.
    try {
      git(out, ['am', '--3way'], { input: patch.bytes });
    } catch (error) {
      const conflicts = git(out, ['diff', '--name-only', '--diff-filter=U']);
      const files = conflicts ? `\nConflicted files:\n${conflicts}` : '';
      throw new Error(`Patch ${patch.id} failed against ${inputs.config.upstream.commit} in ${out}${files}\n${error.message}`, { cause: error });
    }
  }
  const lock = {
    schemaVersion: 1, profile: inputs.profile, maintenanceCommit: inputs.commit,
    upstreamCommit: inputs.config.upstream.commit, upstreamUrl: inputs.config.upstream.url,
    profileDigest: inputs.profileDigest, patchsetDigest: inputs.patchsetDigest,
    sourceMapDigest: inputs.sourceMapDigest, toolchainDigest: inputs.toolchainDigest,
    ...(inputs.productDigest ? { productDigest: inputs.productDigest } : {}),
    imports: inputs.imports.map(({ from, to, sha256 }) => ({ from, to, sha256 })),
    adapterBase, adapterTip: git(out, ['rev-parse', 'HEAD']),
    sourceTree: git(out, ['rev-parse', 'HEAD^{tree}']),
  };
  await writeFile(join(out, '.git/hostctl.json'), JSON.stringify(lock, null, 2) + '\n');
  return lock;
}

/** Observe the branch without changing any release input. */
export async function observeUpstream({ repo, profile, source, branch = 'master' }) {
  const inputs = loadInputs(repo, profile);
  const ref = `refs/heads/${branch}`;
  git(repo, ['check-ref-format', ref]);
  const url = source ?? inputs.config.upstream.url;
  let response;
  try {
    response = git(repo, ['ls-remote', '--exit-code', '--refs', '--', url, ref]);
  } catch (error) {
    throw new Error(`Cannot observe upstream branch ${branch}: ${error.message}`, { cause: error });
  }
  const [observedCommit, observedRef] = response.split('\t');
  if (!/^[a-f0-9]{40}$/.test(observedCommit) || observedRef !== ref) {
    throw new Error(`Unexpected upstream response for ${ref}`);
  }
  const pinnedCommit = inputs.config.upstream.commit;
  return {
    profile, maintenanceCommit: inputs.commit, ref, pinnedCommit, observedCommit,
    status: observedCommit === pinnedCommit ? 'current' : 'changed',
  };
}

export async function prepare({ repo, profile, source, out }) {
  if (!out) throw new Error('--out is required');
  return assemble(loadInputs(repo, profile), { source, out });
}

export async function verifyReplay({ expected, ...options }) {
  const original = await readWorkspace(expected);
  if (original.profile !== options.profile) throw new Error('Expected workspace has a different profile');
  if (git(expected, ['status', '--porcelain', '--untracked-files=all'])) throw new Error('Expected workspace must be clean');
  if (git(expected, ['rev-parse', 'HEAD']) !== original.adapterTip ||
      git(expected, ['rev-parse', 'HEAD^{tree}']) !== original.sourceTree) {
    throw new Error('Expected workspace has drifted from its assembly lock; export and re-prepare first');
  }
  if (original.maintenanceCommit !== cleanCommit(options.repo)) throw new Error('Expected workspace has different maintenance inputs');
  const lock = await prepare(options);
  if (lock.sourceTree !== original.sourceTree) throw new Error('Replayed source tree differs');
  return lock;
}

/** A series directory is exclusively owned; refuse before touching any unrelated local file. */
async function ownedSeriesDirectory(inputs) {
  const relative = dirname(inputs.config.series).replaceAll('\\', '/');
  if (!relative.startsWith('patches/')) throw new Error('Series must own a directory below patches/');
  const allowed = new Set([basename(inputs.config.series)]);
  for (const patch of inputs.patches) {
    if (dirname(patch.file).replaceAll('\\', '/') !== relative) throw new Error('Patch outside its managed series directory');
    allowed.add(basename(patch.file));
  }
  let directory = inputs.repo;
  for (const part of relative.split('/')) {
    directory = join(directory, part);
    const stat = await lstat(directory);
    if (!stat.isDirectory() || stat.isSymbolicLink()) throw new Error('Series directory must not be a link');
  }
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (!allowed.has(entry.name) || !entry.isFile()) throw new Error(`Unmanaged series entry: ${entry.name}`);
  }
  return directory;
}

/** Exports host-only commits, replays in isolation, then swaps the series directory. */
export async function exportSeries({ repo, workspace }) {
  const metadata = await readWorkspace(workspace);
  if (git(workspace, ['status', '--porcelain', '--untracked-files=all'])) throw new Error('Export workspace must be clean');
  const inputs = loadInputs(repo, metadata.profile);
  if (inputs.commit !== metadata.maintenanceCommit) throw new Error('Workspace maintenance commit is stale; re-prepare before exporting');
  const seriesDir = await ownedSeriesDirectory(inputs);
  git(workspace, ['merge-base', '--is-ancestor', metadata.adapterBase, 'HEAD']);
  const commits = git(workspace, ['rev-list', '--reverse', `${metadata.adapterBase}..HEAD`]).split('\n').filter(Boolean);
  const expectedTree = git(workspace, ['rev-parse', 'HEAD^{tree}']);
  const stage = await mkdtemp(join(tmpdir(), 'host-export-'));
  try {
    const patches = [];
    for (const [index, commit] of commits.entries()) {
      const parents = git(workspace, ['rev-list', '--parents', '-n', '1', commit]).split(' ');
      if (parents.length !== 2) throw new Error('Export requires a linear patch series');
      const paths = git(workspace, ['diff-tree', '--no-commit-id', '--name-only', '-r', commit]).split('\n').filter(Boolean);
      if (paths.some(path => path.startsWith('vendor/'))) throw new Error('Edit imported vendor sources in the maintenance repository');
      const message = git(workspace, ['show', '-s', '--format=%B', commit]);
      const id = message.match(/^Patch-Id: ([a-z0-9][a-z0-9-]*)$/m)?.[1];
      if (!id || patches.some(p => p.id === id)) throw new Error('Each patch needs a unique Patch-Id trailer');
      const filename = `${String(index + 1).padStart(4, '0')}-${id}.patch`;
      const bytes = git(workspace, ['format-patch', '-1', '--stdout', '--full-index', '--binary', '--no-signature', commit], { binary: true });
      const file = `${dirname(inputs.config.series).replaceAll('\\', '/')}/${filename}`;
      safePath(file);
      patches.push({ id, file, sha256: digest(bytes), paths, bytes });
    }
    const replay = await assemble({ ...inputs, patches }, { source: workspace, out: join(stage, 'replay') });
    if (replay.sourceTree !== expectedTree) throw new Error('Export replay differs from the edited checkout');
    const manifest = { schemaVersion: 1, patches: patches.map(({ bytes: _bytes, ...patch }) => patch) };
    // Recheck after replay, immediately before publishing the replacement.
    await ownedSeriesDirectory(inputs);
    const backup = `${seriesDir}.previous`;
    if (await exists(backup)) throw new Error(`Previous export recovery directory exists: ${backup}`);
    const replacement = await mkdtemp(`${seriesDir}.next-`);
    try {
      for (const patch of patches) await writeFile(join(replacement, basename(patch.file)), patch.bytes);
      await writeFile(join(replacement, basename(inputs.config.series)), JSON.stringify(manifest, null, 2) + '\n');
      await rename(seriesDir, backup);
      try { await rename(replacement, seriesDir); } catch (error) {
        await rename(backup, seriesDir);
        throw error;
      }
      await rm(backup, { recursive: true });
    } finally {
      await rm(replacement, { recursive: true, force: true });
    }
    return { ...manifest, sourceTree: expectedTree };
  } finally {
    await rm(stage, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
}
