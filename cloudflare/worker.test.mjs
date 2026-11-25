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

// Oracle: sitemap directory URLs keep their slash; legal pages keep no slash or extension.
test('canonical slash normalization preserves the exact sitemap URL forms', async () => {
  for (const [input, expected] of [
    [base, `${base}/`],
    [`${base}/index.html`, `${base}/`],
    [`${base}/pt-br`, `${base}/pt-br/`],
    [`${base}/pt-br/index.html`, `${base}/pt-br/`],
    [`${base}/release/privacy-policy/`, `${base}/release/privacy-policy`],
    [`${base}/release/privacy-policy.html`, `${base}/release/privacy-policy`],
    [`${base}/release/terms/`, `${base}/release/terms`],
    [`${base}/release/terms.html`, `${base}/release/terms`],
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
  const result = await invoke(path, assets({ [path]: response }));
  assert.equal(await result.text(), 'feature');
  assert.equal(result.headers.get('X-Test'), 'preserved');
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

// Oracle: internal rewrites serve real index/legal HTML at the public URL with no redirect.
test('directory and legal canonical URLs resolve to their rendered files', async () => {
  for (const [path, file] of [
    [`${base}/`, `${base}/index.html`],
    [`${base}/pt-br/`, `${base}/pt-br/index.html`],
    [`${base}/release/privacy-policy`, `${base}/release/privacy-policy.html`],
    [`${base}/release/terms`, `${base}/release/terms.html`],
  ]) {
    const response = new Response(file);
    const result = await invoke(path, assets({ [file]: response }));
    assert.equal(result.status, 200);
    assert.equal(result.headers.get('Location'), null);
    assert.equal(await result.text(), file);
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
  const originFetch = t.mock.method(globalThis, 'fetch', async () => response.clone());
  for (const path of ['/', '/about/', '/audio-blackbox-other']) {
    const request = new Request(`http://alexandre.machado.cc${path}`);
    assert.equal(await (await worker.fetch(request, assets())).text(), 'GitHub user site');
    assert.equal(originFetch.mock.calls.at(-1).arguments[0], request);
  }
});

// Oracle: a missing page remains 404, not a homepage fallback or an invented redirect.
test('unknown pages preserve 404', async () => {
  assert.equal((await invoke(`${base}/missing`)).status, 404);
});

// Oracle: every response path carries all four framing/content/referrer protections.
test('security headers cover assets, redirects, custom 404 and origin passthrough', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => new Response('origin'));
  const custom404 = '<h1>Custom missing page</h1>';
  for (const [path, status] of [[`${base}/ok`, 200], [base, 301], [`${base}/missing`, 404], ['/audio-blackbox-other', 200]]) {
    const env = { ASSETS: { fetch: async () => new Response(status === 404 ? custom404 : 'asset', { status: status === 404 ? 404 : 200 }) } };
    const result = await invoke(path, env);
    assert.equal(result.status, status);
    for (const [name, value] of Object.entries({
      'X-Content-Type-Options': 'nosniff',
      'Referrer-Policy': 'strict-origin-when-cross-origin',
      'X-Frame-Options': 'DENY',
      'Content-Security-Policy': "frame-ancestors 'none'",
    })) assert.equal(result.headers.get(name), value, `${path}: ${name}`);
    assert.equal(result.headers.get('Strict-Transport-Security'), null);
    if (status === 404) assert.equal(await result.text(), custom404);
  }
});

// Oracle: internal privacy rewrites preserve HEAD at the asset binding and return no body.
test('privacy HEAD preserves method and empty body', async () => {
  const env = { ASSETS: { fetch: async (request) => {
    assert.equal(request.method, 'HEAD');
    assert.equal(new URL(request.url).pathname, `${base}/release/privacy-policy.html`);
    return new Response(null, { headers: { 'Content-Type': 'text/html' } });
  } } };
  const result = await worker.fetch(new Request(`${origin}${base}/release/privacy-policy`, { method: 'HEAD' }), env);
  assert.equal(result.status, 200);
  assert.equal(await result.text(), '');
});

// Oracle: successful static files receive the intended TTL/type; errors never get asset TTLs.
test('worker applies cache and text content-type rules', async () => {
  for (const extension of ['png', 'webp', 'jpg', 'svg', 'ico', 'woff', 'woff2']) {
    const path = `${base}/assets/file.${extension}`;
    const result = await invoke(path, assets({ [path]: new Response('asset') }));
    assert.equal(result.headers.get('Cache-Control'), `public, max-age=${extension.startsWith('woff') ? 604800 : 86400}`);
    assert.equal((await invoke(path)).headers.get('Cache-Control'), null);
  }
  for (const file of ['llms.txt', 'llms-full.txt', '6e548543f5dfe595b4046686467f6f97.txt']) {
    const path = `${base}/${file}`;
    assert.equal((await invoke(path, assets({ [path]: new Response('text') }))).headers.get('Content-Type'), 'text/plain; charset=utf-8');
  }
});

// Oracle: the production staging script must retain the prefix and root 404;
// the real Worker must serve legal URLs and return that fallback on a miss.
test('generated asset staging preserves prefix, legal routes and root 404', async () => {
  const {mkdtemp, mkdir, writeFile, readFile, rm} = await import('node:fs/promises');
  const {tmpdir} = await import('node:os');
  const {join} = await import('node:path');
  const {execFileSync} = await import('node:child_process');
  const temporary = await mkdtemp(join(tmpdir(), 'absite-stage-'));
  try {
    await mkdir(join(temporary, 'scripts/ci'), {recursive:true});
    await mkdir(join(temporary, 'site/dist/release'), {recursive:true});
    // In isolated workflow copies only cloudflare/ is available; carry the actual
    // staging script into those copies via the workflow before running tests.
    const script = new URL('./stage-cloudflare-site.sh', import.meta.url);
    let source;
    try {source = await readFile(script);} catch (error) {
      if (error.code !== 'ENOENT') throw error;
      source = await readFile(new URL('../scripts/ci/stage-cloudflare-site.sh', import.meta.url));
    }
    await writeFile(join(temporary, 'scripts/ci/stage-cloudflare-site.sh'), source);
    for (const [file, body] of [['index.html','home'],['404.html','custom missing'],['release/privacy-policy.html','privacy'],['release/terms.html','terms']]) {
      await writeFile(join(temporary, 'site/dist', file), body);
    }
    execFileSync('bash', [join(temporary, 'scripts/ci/stage-cloudflare-site.sh')]);
    const staged = join(temporary, 'cloudflare/.staged');
    // Oracle: the relocated script used by both CI jobs stages beside Wrangler,
    // never relative to an unrelated ancestor of the temporary work directory.
    const isolated = join(temporary, 'isolated');
    await mkdir(isolated);
    await writeFile(join(isolated, 'wrangler.jsonc'), '{}');
    await writeFile(join(isolated, 'stage-cloudflare-site.sh'), source);
    execFileSync('bash', [join(isolated, 'stage-cloudflare-site.sh'), join(temporary, 'site/dist')]);
    assert.equal(await readFile(join(isolated, '.staged/404.html'), 'utf8'), 'custom missing');
    assert.equal(await readFile(join(isolated, '.staged/audio-blackbox/index.html'), 'utf8'), 'home');
    assert.equal(await readFile(join(staged, '404.html'), 'utf8'), 'custom missing');
    const env = {ASSETS:{fetch:async request => {
      try {return new Response(await readFile(join(staged, new URL(request.url).pathname)));}
      catch (error) {
        if (error.code !== 'ENOENT' && error.code !== 'EISDIR') throw error;
        return new Response(await readFile(join(staged, '404.html')), {status:404});
      }
    }}};
    for (const [path, body, status] of [['/','home',200],['/release/privacy-policy','privacy',200],['/release/terms','terms',200],['/missing','custom missing',404]]) {
      const response = await invoke(base + path, env);
      assert.equal(response.status, status);
      assert.equal(await response.text(), body);
    }
  } finally {await rm(temporary, {recursive:true, force:true});}
});
