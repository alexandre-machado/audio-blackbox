const base = '/audio-blackbox';
const privacy = `${base}/release/privacy-policy`;

export default {
  async fetch(request, env) {
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
    if (response.status === 404 && path !== privacy) {
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
  },
};
