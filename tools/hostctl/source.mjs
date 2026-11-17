import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdir, readFile, writeFile, lstat, mkdtemp, rm, rename } from 'node:fs/promises';
import { join, resolve, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { git, cleanCommit, loadInputs } from './inputs.mjs';

async function assertAbsent(path) {
  try { await lstat(path); } catch (error) {
    if (error.code === 'ENOENT') return;
    throw error;
  }
  throw new Error(`Destination already exists: ${path}`);
}

async function describeArtifact(path, name) {
  const hash = createHash('sha256');
  let sizeBytes = 0;
  for await (const bytes of createReadStream(path)) { hash.update(bytes); sizeBytes += bytes.length; }
  return { name, sha256: hash.digest('hex'), sizeBytes };
}

/** An isolated bare clone overrides export attributes without altering the source checkout. */
async function archiveTree(repo, tree, prefix, output) {
  const scratch = await mkdtemp(join(tmpdir(), 'host-source-'));
  try {
    const archiveRepo = join(scratch, 'archive.git');
    git(scratch, ['clone', '--bare', '--shared', '--', repo, archiveRepo]);
    git(archiveRepo, ['config', 'core.autocrlf', 'false']);
    // Corresponding source must include files marked export-ignore and retain original blob bytes.
    await mkdir(join(archiveRepo, 'info'), { recursive: true });
    await writeFile(join(archiveRepo, 'info/attributes'), '* -export-ignore -export-subst -text -eol -filter -ident -working-tree-encoding\n');
    git(archiveRepo, ['archive', '--format=tar', '--mtime=1970-01-01T00:00:00Z',
      `--prefix=${prefix}/`, `--output=${output}`, tree]);
  } finally {
    await rm(scratch, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
}

/** Export only committed objects from a confirmed assembly, never filesystem-local build/signing inputs. */
export async function exportSource({ repo, workspace, out }) {
  if (!workspace || !out) throw new Error('--workspace and --out are required');
  out = resolve(out);
  await assertAbsent(out);
  const commit = cleanCommit(repo);
  const lock = JSON.parse(await readFile(join(workspace, '.git/hostctl.json'), 'utf8'));
  if (lock.schemaVersion !== 1 || lock.maintenanceCommit !== commit) throw new Error('Workspace has different maintenance inputs');
  const inputs = loadInputs(repo, lock.profile, commit);
  for (const key of ['profileDigest', 'patchsetDigest', 'sourceMapDigest', 'toolchainDigest']) {
    if (inputs[key] !== lock[key]) throw new Error(`Workspace provenance mismatch: ${key}`);
  }
  if (inputs.config.upstream.commit !== lock.upstreamCommit) throw new Error('Workspace upstream identity mismatch');
  if (git(workspace, ['status', '--porcelain', '--untracked-files=all'])) throw new Error('Workspace must be clean');
  if (git(workspace, ['rev-parse', 'HEAD']) !== lock.adapterTip ||
      git(workspace, ['rev-parse', 'HEAD^{tree}']) !== lock.sourceTree) throw new Error('Workspace has drifted from its assembly lock');
  await mkdir(dirname(out), { recursive: true });
  const stage = await mkdtemp(join(dirname(out), '.source-delivery-'));
  try {
    const artifacts = [];
    for (const [origin, tree, prefix, name] of [
      [workspace, lock.sourceTree, 'rikka', 'application-source.tar'],
      [repo, git(repo, ['rev-parse', `${commit}^{tree}`]), 'plugin-host', 'maintenance-source.tar'],
    ]) {
      const path = join(stage, name);
      await archiveTree(origin, tree, prefix, path);
      artifacts.push(await describeArtifact(path, name));
    }
    const provenance = {
      schemaVersion: 1, profile: lock.profile, maintenanceCommit: commit,
      upstreamCommit: lock.upstreamCommit, upstreamUrl: inputs.config.upstream.url,
      sourceTree: lock.sourceTree, profileDigest: lock.profileDigest, patchsetDigest: lock.patchsetDigest,
      sourceMapDigest: lock.sourceMapDigest, toolchainDigest: lock.toolchainDigest, artifacts,
    };
    await writeFile(join(stage, 'source-provenance.json'), JSON.stringify(provenance, null, 2) + '\n');
    await assertAbsent(out);
    await rename(stage, out);
    return provenance;
  } finally {
    await rm(stage, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
}
