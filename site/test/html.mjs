export function decode(s = '') {
  return s.replace(/&#(x[0-9a-f]+|\d+);/gi, (_, n) => String.fromCodePoint(n[0].toLowerCase() === 'x' ? parseInt(n.slice(1),16) : Number(n))).replace(/&(amp|quot|apos|lt|gt);/g, (_, n) => ({amp:'&',quot:'"',apos:"'",lt:'<',gt:'>'})[n]);
}
export function attrs(tag) {
  return Object.fromEntries([...tag.matchAll(/([\w:-]+)\s*=\s*(["'])(.*?)\2/gs)].map(m => [m[1], decode(m[3])]));
}
export function metadata(html) {
  const links = [...html.matchAll(/<link\b[^>]*>/gi)].map(m => attrs(m[0]));
  const metas = [...html.matchAll(/<meta\b[^>]*>/gi)].map(m => attrs(m[0]));
  const jsonLd = [...html.matchAll(/<script\b[^>]*type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi)].map(m => JSON.parse(m[1]));
  const types = new Set();
  function visit(value) { if (!value || typeof value !== 'object') return; if(value['@type']) for(const t of [value['@type']].flat()) types.add(t); Object.values(value).forEach(visit); }
  jsonLd.forEach(visit);
  return { canonical: links.find(l => l.rel === 'canonical')?.href ?? null,
    hreflang: links.filter(l => l.hreflang).map(l => ({lang:l.hreflang,href:l.href})).sort((a,b)=>a.lang.localeCompare(b.lang)),
    title: decode(html.match(/<title>([\s\S]*?)<\/title>/i)?.[1] ?? ''),
    robots: metas.find(m => m.name === 'robots')?.content ?? null,
    description: metas.find(m => m.name === 'description')?.content ?? null,
    jsonLdTypes: [...types].sort(), jsonLd,
    social: metas.filter(m => /^(og:|twitter:)/.test(m.property ?? m.name ?? '')).sort((a,b)=>JSON.stringify(a).localeCompare(JSON.stringify(b))) };
}
export function sitemap(xml) {
  return [...xml.matchAll(/<url>([\s\S]*?)<\/url>/g)].map(([,body]) => ({url:decode(body.match(/<loc>(.*?)<\/loc>/)[1]),alternates:[...body.matchAll(/<xhtml:link\b[^>]*>/g)].map(m=>attrs(m[0])).sort((a,b)=>a.hreflang.localeCompare(b.hreflang))})).sort((a,b)=>a.url.localeCompare(b.url));
}
