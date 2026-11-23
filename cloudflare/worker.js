const base = '/audio-blackbox';
const privacy = `${base}/release/privacy-policy`;

async function route(request, env) {
  const url = new URL(request.url);
  // The zone route also matches /audio-blackbox-other. Leave the user site alone.
  if (url.pathname !== base && !url.pathname.startsWith(`${base}/`)) {
    return fetch(request);
  }
  const redirect = (pathname) => {
    const target = new URL(url);
    target.protocol = 'https:';
    target.pathname = pathname;
    return Response.redirect(target.toString(), 301);
  };
  let path = url.pathname;
  if (path === base) path += '/';
  if (path.endsWith('/index.html')) path = path.slice(0, -10);
  if (path === `${privacy}/` || path === `${privacy}.html`) path = privacy;
  if (url.protocol === 'http:' || path !== url.pathname) return redirect(path);

  const asset = async (pathname) => {
    const target = new URL(url);
    target.pathname = pathname;
    return env.ASSETS.fetch(new Request(target, request));
  };
  let response = await asset(path === privacy ? `${privacy}.html` :
    path.endsWith('/') ? `${path}index.html` : path);
  if (response.status === 404) {
    const stem = path.replace(/\/$/, '');
    if (!stem.split('/').pop().includes('.')) {
      const document = await asset(`${stem}.html`);
      if (document.status === 200) return redirect(`${stem}.html`);
      if (!path.endsWith('/')) {
        const directory = await asset(`${path}/index.html`);
        if (directory.status === 200) return redirect(`${path}/`);
      }
    }
  }
  if (response.status === 307 && response.headers.has('Location')) {
    const headers = new Headers(response.headers);
    headers.set('Location', new URL(headers.get('Location'), url).toString());
    return new Response(null, { status: 301, headers });
  }
  return response;
}

export default {
  async fetch(request, env) {
    const response = await route(request, env);
    const headers = new Headers(response.headers);
    headers.set('X-Content-Type-Options', 'nosniff');
    headers.set('Referrer-Policy', 'strict-origin-when-cross-origin');
    headers.set('X-Frame-Options', 'DENY');
    headers.set('Content-Security-Policy', "frame-ancestors 'none'");
    const path = new URL(request.url).pathname;
    if (path.startsWith(`${base}/`) && response.status === 200) {
      if (/\.(png|webp|jpg|svg|ico)$/.test(path)) headers.set('Cache-Control', 'public, max-age=86400');
      if (/\.woff2?$/.test(path)) headers.set('Cache-Control', 'public, max-age=604800');
      if (/\/(llms[^/]*|6e548543f5dfe595b4046686467f6f97)\.txt$/.test(path)) {
        headers.set('Content-Type', 'text/plain; charset=utf-8');
      }
    }
    return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
  },
};
