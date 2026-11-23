import test from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.js';

const origin = 'https://alexandre.machado.cc';
const base = '/audio-blackbox';
function assets(files = {}) {
  return { ASSETS: { fetch: async (request) => files[new URL(request.url).pathname] ?? new Response('404', { status: 404 }) } };
}
const invoke = (path, env = assets()) => worker.fetch(new Request(origin + path), env);

// Oracle: HTTP must return a permanent HTTPS location, retaining the query.
test('HTTP upgrades to HTTPS with 301', async () => {
  const result = await worker.fetch(new Request(`http://alexandre.machado.cc${base}/?a=1`), assets());
  assert.equal(result.status, 301);
  assert.equal(result.headers.get('Location'), `${origin}${base}/?a=1`);
});

// Oracle: sitemap directory URLs keep their slash; privacy keeps no slash or extension.
test('canonical slash normalization preserves the exact sitemap URL forms', async () => {
  for (const [input, expected] of [
    [base, `${base}/`],
    [`${base}/index.html`, `${base}/`],
    [`${base}/pt-br`, `${base}/pt-br/`],
    [`${base}/pt-br/index.html`, `${base}/pt-br/`],
    [`${base}/release/privacy-policy/`, `${base}/release/privacy-policy`],
    [`${base}/release/privacy-policy.html`, `${base}/release/privacy-policy`],
  ]) {
    const result = await invoke(`${input}?a=1`, assets({ [`${base}/pt-br/index.html`]: new Response('locale') }));
    assert.equal(result.status, 301, input);
    assert.equal(result.headers.get('Location'), `${origin}${expected}?a=1`, input);
  }
});

// Oracle: existing .html canonical URLs must serve directly, never lose their suffix.
test('200 assets pass through unchanged, including feature .html', async () => {
  const path = `${base}/features/01-capture-and-memory-core.html`;
  const response = new Response('feature', { headers: { 'X-Test': 'preserved' } });
  assert.equal(await invoke(path, assets({ [path]: response })), response);
});

// Oracle: document aliases normalize to .html, unlike the directory slash convention.
test('document aliases retain the existing .html canonical suffix', async () => {
  const path = `${base}/features/01-capture-and-memory-core`;
  for (const alias of [path, `${path}/`]) {
    const result = await invoke(alias, assets({ [`${path}.html`]: new Response('feature') }));
    assert.equal(result.status, 301);
    assert.equal(result.headers.get('Location'), `${origin}${path}.html`);
  }
});

// Oracle: internal rewrites serve real index/privacy HTML at the public URL with no redirect.
test('directory and privacy canonical URLs resolve to their rendered files', async () => {
  for (const [path, file] of [
    [`${base}/`, `${base}/index.html`],
    [`${base}/pt-br/`, `${base}/pt-br/index.html`],
    [`${base}/release/privacy-policy`, `${base}/release/privacy-policy.html`],
  ]) {
    const response = new Response(file);
    assert.equal(await invoke(path, assets({ [file]: response })), response);
  }
});

// Oracle: temporary asset redirects become permanent and relative locations become absolute.
test('asset 307 becomes 301 with an absolute Location and preserved headers', async () => {
  const result = await invoke(`${base}/file.txt`, assets({
    [`${base}/file.txt`]: new Response(null, { status: 307, headers: { Location: './other.txt', 'X-Test': 'kept' } }),
  }));
  assert.equal(result.status, 301);
  assert.equal(result.headers.get('Location'), `${origin}${base}/other.txt`);
  assert.equal(result.headers.get('X-Test'), 'kept');
});

// Oracle: the broad zone-route prefix must not hijack root-site paths or lookalike prefixes.
test('outside the exact site prefix goes to origin without a Worker redirect', async (t) => {
  const response = new Response('GitHub user site');
  const originFetch = t.mock.method(globalThis, 'fetch', async () => response);
  for (const path of ['/', '/about/', '/audio-blackbox-other']) {
    const request = new Request(`http://alexandre.machado.cc${path}`);
    assert.equal(await worker.fetch(request, assets()), response);
    assert.equal(originFetch.mock.calls.at(-1).arguments[0], request);
  }
});

// Oracle: a missing page remains 404, not a homepage fallback or an invented redirect.
test('unknown pages preserve 404', async () => {
  assert.equal((await invoke(`${base}/missing`)).status, 404);
});
