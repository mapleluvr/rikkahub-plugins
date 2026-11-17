import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { resolve, isAbsolute } from 'node:path';

export function git(cwd, args, { binary = false, input } = {}) {
  try {
    // Release checks must observe the working tree, not a stale or misconfigured monitor cache.
    const result = execFileSync('git', ['-c', 'core.fsmonitor=false', '-C', cwd, ...args], {
      encoding: binary ? undefined : 'utf8', input,
      maxBuffer: 64 * 1024 * 1024, stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...process.env, GIT_TERMINAL_PROMPT: '0' },
    });
    return binary ? result : result.trim();
  } catch (error) {
    throw new Error(`git ${args[0]} failed: ${String(error.stderr ?? error.message).trim()}`, { cause: error });
  }
}

export const digest = bytes => createHash('sha256').update(bytes).digest('hex');

export function safePath(path) {
  if (typeof path !== 'string' || !path || isAbsolute(path) || path.includes('\\') ||
      /[:\x00-\x1f]/.test(path) || path.split('/').some(part =>
        !part || part === '.' || part === '..' || part.toLowerCase() === '.git' || /[. ]$/.test(part) ||
        /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\.|$)/i.test(part))) {
    throw new Error(`Unsafe repository path: ${path}`);
  }
  return path;
}

export function cleanCommit(repo) {
  if (git(repo, ['status', '--porcelain', '--untracked-files=all'])) {
    throw new Error('Maintenance inputs must be clean and committed');
  }
  return git(repo, ['rev-parse', 'HEAD']);
}

export function blob(repo, commit, path) {
  safePath(path);
  const entry = git(repo, ['ls-tree', commit, '--', path]);
  if (!/^100(644|755) blob /.test(entry)) throw new Error(`Expected a regular tracked file: ${path}`);
  return git(repo, ['show', `${commit}:${path}`], { binary: true });
}

export function loadInputs(repo, profile, commit = cleanCommit(repo)) {
  if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(profile)) throw new Error('Invalid profile name');
  const json = path => JSON.parse(blob(repo, commit, path).toString('utf8'));
  const profilePath = `profiles/${profile}/profile.json`;
  const config = json(profilePath);
  if (config.schemaVersion !== 1 || !/^[a-f0-9]{40}$/.test(config.upstream?.commit) ||
      typeof config.upstream?.url !== 'string') throw new Error('Invalid upstream profile');
  const series = json(config.series);
  const sourceMap = json(config.sourceMap);
  const toolchain = json(config.toolchain);
  if (series.schemaVersion !== 1 || !Array.isArray(series.patches) ||
      sourceMap.schemaVersion !== 1 || !Array.isArray(sourceMap.imports)) throw new Error('Invalid series or source map');
  const patchIds = new Set();
  const patches = series.patches.map(patch => {
    if (!patch.id || patchIds.has(patch.id)) throw new Error('Duplicate or missing patch ID');
    patchIds.add(patch.id);
    const bytes = blob(repo, commit, patch.file);
    if (digest(bytes) !== patch.sha256) throw new Error(`Patch checksum mismatch: ${patch.file}`);
    return { ...patch, bytes };
  });
  const imports = sourceMap.imports.map(mapping => {
    safePath(mapping.to);
    if (!mapping.to.startsWith('vendor/rikka-plugin-host/')) throw new Error('Import path must be under vendor/rikka-plugin-host/');
    const bytes = blob(repo, commit, mapping.from);
    return { ...mapping, bytes, sha256: digest(bytes) };
  });
  return {
    repo: resolve(repo), profile, commit, config, patches, imports, toolchain,
    profileDigest: digest(blob(repo, commit, profilePath)),
    patchsetDigest: digest(blob(repo, commit, config.series)),
    sourceMapDigest: digest(blob(repo, commit, config.sourceMap)),
    toolchainDigest: digest(blob(repo, commit, config.toolchain)),
  };
}
