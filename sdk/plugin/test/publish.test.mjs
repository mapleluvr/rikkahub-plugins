import test from 'node:test';
import assert from 'node:assert/strict';
import { publishRelease } from '../publish.mjs';
import { signIndex } from '../release.mjs';
import { generateKeyPairSync } from 'node:crypto';

function apiFixture(initial, { hideDraftByTag = false, extraDrafts = [] } = {}) {
  let release = initial;
  const bytes = new Map();
  const calls = [];
  const api = async (method, path, body) => {
    calls.push({ method, path });
    if (path.endsWith('/tags/v1.0.0')) return { status: release && !(hideDraftByTag && release.draft) ? 200 : 404, body: release };
    if (method === 'GET' && path.includes('/releases?')) return { status: 200, body: [...(release ? [release] : []), ...extraDrafts] };
    if (method === 'POST' && path.endsWith('/releases')) {
      release = { id: 7, tag_name: 'v1.0.0', draft: true, prerelease: false, assets: [] };
      assert.equal(body.draft, true);
      return { status: 201, body: release };
    }
    if (method === 'POST' && path.includes('/assets?name=')) {
      const name = decodeURIComponent(path.split('=')[1]);
      const id = release.assets.length + 1;
      release.assets.push({ id, name, size: body.length });
      bytes.set(id, body);
      return { status: 201, body: { id } };
    }
    if (method === 'GET' && path.includes('/releases/assets/')) return { status: 200, body: bytes.get(Number(path.split('/').at(-1))) };
    if (method === 'PATCH') { release = { ...release, ...body }; return { status: 200, body: release }; }
    throw new Error(`Unexpected ${method} ${path}`);
  };
  return { api, calls, bytes, current: () => release };
}
test('rerunning ECDSA signing accepts an existing valid signature by the same key without replacing it', async () => {
  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const first = { ...assets, 'release.json.sig': signIndex(assets['release.json'], privateKey) };
  const fixture = apiFixture();
  await publishRelease(fixture.api, 'owner/repo', 'v1.0.0', first, false, 'a'.repeat(40));
  fixture.calls.length = 0;
  const second = { ...assets, 'release.json.sig': signIndex(assets['release.json'], privateKey) };
  await publishRelease(fixture.api, 'owner/repo', 'v1.0.0', second, false, 'a'.repeat(40));
  assert.ok(fixture.calls.every(call => call.method === 'GET'));
  const changed = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  await assert.rejects(publishRelease(fixture.api, 'owner/repo', 'v1.0.0',
    { ...assets, 'release.json.sig': signIndex(assets['release.json'], changed.privateKey) }, false, 'a'.repeat(40)), /conflict/i);
});
test('rerun finds an interrupted draft via release listing when tag lookup excludes drafts', async () => {
  const fixture = apiFixture(undefined, { hideDraftByTag: true });
  let interrupted = false;
  const interruptedApi = async (...args) => {
    if (!interrupted && args[0] === 'GET' && args[1].includes('/releases/assets/')) {
      interrupted = true;
      throw new Error('connection lost after asset upload');
    }
    return fixture.api(...args);
  };
  await assert.rejects(publishRelease(interruptedApi, 'owner/repo', 'v1.0.0', assets, false, 'a'.repeat(40)), /connection lost/);
  assert.equal(fixture.current().draft, true);
  fixture.calls.length = 0;
  await publishRelease(fixture.api, 'owner/repo', 'v1.0.0', assets, false, 'a'.repeat(40));
  assert.equal(fixture.current().draft, false);
  assert.equal(fixture.calls.filter(c => c.method === 'POST' && c.path.endsWith('/releases')).length, 0);
  assert.equal(fixture.calls.filter(c => c.method === 'POST' && c.path.includes('/assets?')).length, 1);
});
test('ambiguous interrupted drafts are rejected without writes', async () => {
  const draft = { id: 7, tag_name: 'v1.0.0', draft: true, prerelease: false, assets: [] };
  const fixture = apiFixture(draft, { hideDraftByTag: true, extraDrafts: [{ ...draft, id: 8 }] });
  await assert.rejects(publishRelease(fixture.api, 'owner/repo', 'v1.0.0', assets, false, 'a'.repeat(40)), /ambiguous/i);
  assert.ok(fixture.calls.every(c => c.method === 'GET'));
});
test('draft lookup follows pagination and refuses truncated discovery before writing', async () => {
  const draft = { id: 7, tag_name: 'v1.0.0', draft: true, prerelease: false, assets: [] };
  const unrelated = Array.from({ length: 100 }, (_, n) => ({ ...draft, id: n + 10, tag_name: 'other-' + n }));
  const fixture = apiFixture(draft, { hideDraftByTag: true });
  const paged = async (method, path, ...args) => {
    if (path.endsWith('per_page=100&page=1')) return { status: 200, body: unrelated };
    return fixture.api(method, path, ...args);
  };
  await publishRelease(paged, 'owner/repo', 'v1.0.0', assets, false, 'a'.repeat(40));
  assert.ok(fixture.calls.some(c => c.path.endsWith('page=2')));
  assert.ok(!fixture.calls.some(c => c.method === 'POST' && c.path.endsWith('/releases')));
  const calls = [];
  const truncated = async (method, path) => {
    calls.push(method);
    return path.includes('/tags/') ? { status: 404 } : { status: 200, body: unrelated };
  };
  await assert.rejects(publishRelease(truncated, 'owner/repo', 'v1.0.0', assets, false, 'a'.repeat(40)), /truncated/);
  assert.equal(calls.length, 11);
  assert.ok(calls.every(method => method === 'GET'));
});
const assets = { 'release.json': Buffer.from('index'), 'plugin.rikkaplug': Buffer.from('package') };
test('publication uploads and reads back every draft asset before making it visible, then reuses exact published release', async () => {
  const fixture = apiFixture();
  await publishRelease(fixture.api, 'owner/repo', 'v1.0.0', assets, false, 'a'.repeat(40));
  assert.equal(fixture.current().draft, false);
  assert.equal(fixture.calls.at(-1).method, 'PATCH');
  fixture.calls.length = 0;
  await publishRelease(fixture.api, 'owner/repo', 'v1.0.0', assets, false, 'a'.repeat(40));
  assert.ok(fixture.calls.every(call => call.method === 'GET'));
  fixture.bytes.set(1, Buffer.from('replaced'));
  await assert.rejects(publishRelease(fixture.api, 'owner/repo', 'v1.0.0', assets, false, 'a'.repeat(40)), /conflict/i);
  assert.ok(fixture.calls.every(call => call.method === 'GET'));
});
test('incomplete published releases and changed channels are conflicts, never overwritten', async () => {
  for (const existing of [{ id: 7, draft: false, prerelease: false, assets: [] }, { id: 7, draft: true, prerelease: true, assets: [] }]) {
    const fixture = apiFixture(existing);
    await assert.rejects(publishRelease(fixture.api, 'owner/repo', 'v1.0.0', assets, false, 'a'.repeat(40)), /conflict/i);
    assert.ok(fixture.calls.every(call => call.method === 'GET'));
  }
});
