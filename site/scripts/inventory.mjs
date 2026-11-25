// Explicit opt-in network capture. Serial GETs only; never follows redirects implicitly.
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, mkdtempSync, rmSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';
import { metadata, sitemap } from '../test/html.mjs';
const base = 'https://alexandre.machado.cc/audio-blackbox';
const temp = mkdtempSync(`${tmpdir()}/blackbox-inventory-`);
const records = [];
function get(url) {
  const status = Number(execFileSync('curl', ['--silent','--show-error','--max-time','40','--request','GET','--user-agent','AudioBlackbox-Parity-Inventory/1.0','--dump-header',`${temp}/headers`,'--output',`${temp}/body`,'--write-out','%{http_code}',url], {encoding:'utf8'}));
  const headers = readFileSync(`${temp}/headers`,'utf8');
  const body = readFileSync(`${temp}/body`,'utf8');
  const location = headers.match(/^location:\s*(.+)$/im)?.[1].trim() ?? null;
  if(status===0||status>=500) throw new Error(`Capture failed: ${status} ${url}`);
  records.push({url,status,location,bodySha256:createHash('sha256').update(readFileSync(`${temp}/body`)).digest('hex'),...metadata(body)});
  console.log(status, url, location ?? '');
  return body;
}
try {
  const xml = get(`${base}/sitemap.xml`);
  const entries = sitemap(xml);
  if(entries.length===0) throw new Error('Live sitemap contained no URLs');
  const paths = ['', '/index.html','/404','/404/','/404.html','/inventory-missing-page','/llms.txt','/llms-full.txt','/6e548543f5dfe595b4046686467f6f97.txt','/design','/design/index.html','/design/model.html'];
  function markdownPaths(dir='') {
    for(const e of readdirSync(new URL(`../../docs/${dir}`,import.meta.url),{withFileTypes:true})) {
      if(e.isDirectory()&&!e.name.startsWith('_')) markdownPaths(`${dir}${e.name}/`);
      else if(e.isFile()&&e.name.endsWith('.md')) paths.push(`/${dir}${e.name.replace(/\.md$/,'.html')}`);
    }
  }
  markdownPaths();
  for (const locale of ['', 'pt-br','de','es','fr','it']) {
    const p = locale ? `/${locale}` : '';
    paths.push(`${p}/`,`${p}/index.html`);
    if(locale) paths.push(p,`${p}.html`);
  }
  for(const slug of ['privacy-policy','terms']) paths.push(`/release/${slug}`,`/release/${slug}/`,`/release/${slug}.html`,`/release/${slug}/index.html`);
  const urls = new Set([...entries.map(e=>e.url),...paths.map(p=>new URL(base+p).href)]);
  for(const url of urls) { await new Promise(r=>setTimeout(r,200)); get(url); }
  mkdirSync(new URL('../test/fixtures/',import.meta.url),{recursive:true});
  writeFileSync(new URL('../test/fixtures/live-inventory.json',import.meta.url), JSON.stringify({capturedAt:new Date().toISOString(),method:'GET',base,sitemap:entries,urls:records},null,2)+'\n');
} finally { rmSync(temp,{recursive:true,force:true}); }
