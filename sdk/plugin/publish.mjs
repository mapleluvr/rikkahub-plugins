#!/usr/bin/env node
import { timingSafeEqual, createPublicKey, verify } from 'node:crypto';
import { isMain } from './entry.mjs';
import { parseVersion, readRelease } from './release.mjs';

const same = (a, b) => Buffer.isBuffer(a) && a.length === b.length && timingSafeEqual(a, b);
function equivalentSignature(remote, expected, index) {
  try {
    if (remote.length > 4096 || expected.length > 4096) return false;
    const a = JSON.parse(remote), b = JSON.parse(expected);
    if (a.algorithm !== 'ES256' || b.algorithm !== 'ES256' || a.publicKey !== b.publicKey) return false;
    const key = createPublicKey({ key: Buffer.from(a.publicKey, 'base64'), format: 'der', type: 'spki' });
    if (key.asymmetricKeyDetails?.namedCurve !== 'prime256v1') return false;
    return [a, b].every(envelope => verify('sha256', index, key, Buffer.from(envelope.signature, 'base64')));
  } catch { return false; }
}
const accepted = (response, statuses) => {
  if (!statuses.includes(response.status)) throw new Error(`GitHub publication failed: HTTP ${response.status}`);
  return response.body;
};

/** GitHub's tag endpoint excludes drafts. Exhaust the bounded list before deciding a tag is absent. */
async function findDraft(api, base, tag) {
  const matches = [];
  for (let page = 1; page <= 10; page++) {
    const releases = accepted(await api('GET', `${base}?per_page=100&page=${page}`), [200]);
    if (!Array.isArray(releases) || releases.length > 100) throw new Error('Invalid release listing');
    matches.push(...releases.filter(release => release.tag_name === tag));
    if (matches.length > 1) throw new Error('Ambiguous releases for tag; reconcile drafts before publishing');
    if (releases.length < 100) return matches[0];
  }
  throw new Error('Release listing truncated; cannot safely create a new draft');
}

/** Existing releases are immutable. A draft may only be completed with identical existing assets. */
export async function publishRelease(api, repository, tag, files, prerelease, commit) {
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository) || !/^[a-f0-9]{40}$/.test(commit)) throw new Error('Invalid publication target');
  const base = `repos/${repository}/releases`;
  const lookup = await api('GET', `${base}/tags/${encodeURIComponent(tag)}`);
  let release = lookup.status === 404 ? await findDraft(api, base, tag) : accepted(lookup, [200]);
  if (!release) {
    release = accepted(await api('POST', base, { tag_name: tag, target_commitish: commit, name: tag, draft: true, prerelease,
      body: 'Plugin package, compatibility index and source at the release tag.' }), [201]);
  }
  if (release.prerelease !== prerelease || !Number.isSafeInteger(release.id)) throw new Error('Release identity or channel conflict');
  const assets = new Map((release.assets ?? []).map(asset => [asset.name, asset]));
  if (assets.size !== (release.assets ?? []).length || [...assets.keys()].some(name => !(name in files))) throw new Error('Release asset conflict');
  if (!release.draft && assets.size !== Object.keys(files).length) throw new Error('Published release is incomplete: conflict');
  for (const [name, bytes] of Object.entries(files)) {
    let asset = assets.get(name);
    if (!asset) {
      if (!release.draft) throw new Error('Published release asset conflict');
      asset = accepted(await api('POST', `${base}/${release.id}/assets?name=${encodeURIComponent(name)}`, bytes), [201]);
    }
    const remote = accepted(await api('GET', `${base}/assets/${asset.id}`, undefined, true), [200]);
    if (!same(remote, bytes) && !(name === 'release.json.sig' && equivalentSignature(remote, bytes, files['release.json']))) throw new Error(`Release asset conflict: ${name}`);
  }
  if (release.draft) accepted(await api('PATCH', `${base}/${release.id}`, { draft: false }), [200]);
  return release.id;
}

export function githubApi(token) {
  return async (method, path, body, binary = false) => {
    const upload = method === 'POST' && Buffer.isBuffer(body);
    let url = new URL(path, upload ? 'https://uploads.github.com/' : 'https://api.github.com/');
    for (let redirects = 0; redirects < 6; redirects++) {
      const headers = { 'User-Agent': 'RikkaHub-PluginPublisher', Accept: binary ? 'application/octet-stream' : 'application/vnd.github+json' };
      if (['api.github.com', 'uploads.github.com'].includes(url.host)) {
        headers.Authorization = `Bearer ${token}`;
        headers['X-GitHub-Api-Version'] = '2022-11-28';
      }
      if (body) headers['Content-Type'] = upload ? 'application/octet-stream' : 'application/json';
      const response = await fetch(url, { method, headers, body: body && (upload ? body : JSON.stringify(body)), redirect: 'manual', signal: AbortSignal.timeout(30000) });
      if ([301, 302, 303, 307, 308].includes(response.status)) {
        const location = response.headers.get('location');
        await response.body?.cancel();
        if (!binary || method !== 'GET' || !location) throw new Error('Unexpected GitHub redirect');
        const next = new URL(location, url);
        if (next.protocol !== 'https:' || next.username || next.password || next.port || !['api.github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com', 'github.com'].includes(next.hostname)) throw new Error('Untrusted GitHub redirect');
        url = next;
        continue;
      }
      const chunks = []; let size = 0;
      for await (const chunk of response.body ?? []) {
        size += chunk.length;
        if (size > 8 * 1024 * 1024) throw new Error('GitHub response exceeds size limit');
        chunks.push(chunk);
      }
      const bytes = Buffer.concat(chunks);
      let parsed = bytes;
      if (!binary) parsed = bytes.length ? JSON.parse(bytes) : null;
      return { status: response.status, body: parsed };
    }
    throw new Error('Too many GitHub redirects');
  };
}

async function main([directory, ...extra]) {
  if (!directory || extra.length) throw new Error('Usage: rikkaplug-publish <verified-release-directory>');
  const { GITHUB_REPOSITORY: repository, GITHUB_SHA: commit, RIKKA_RELEASE_TAG: tag, GH_TOKEN: token } = process.env;
  if (!repository || !commit || !tag || !token) throw new Error('Repository, commit, tag and host GitHub credential are required');
  const release = await readRelease(directory);
  const entries = JSON.parse(release.index).packages;
  const version = entries[0].version;
  if (entries.some(entry => entry.version !== version) || tag !== `v${version}`) throw new Error('Tag and package versions disagree');
  const api = githubApi(token);
  const tagged = accepted(await api('GET', `repos/${repository}/commits/${encodeURIComponent(tag)}`), [200]);
  if (tagged.sha !== commit) throw new Error('Tag commit changed');
  const files = { 'release.json': release.index, ...release.assets };
  if (release.signature) files['release.json.sig'] = release.signature;
  const id = await publishRelease(api, repository, tag, files, parseVersion(version).prerelease, commit);
  console.log(`Verified release ${repository}#${id}`);
}
if (isMain(import.meta.url)) {
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
}
