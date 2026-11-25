import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, dirname } from 'node:path';
import { metadata, sitemap, attrs } from './html.mjs';
import { decode } from './html.mjs';
import { parse } from 'yaml';

const root=dirname(fileURLToPath(new URL('../build.mjs',import.meta.url)));
const dist=join(root,'dist');
const inventory=JSON.parse(readFileSync(new URL('./fixtures/live-inventory.json',import.meta.url)));
// Run the real, unchanged Worker against a disk-backed ASSETS binding with
// html_handling:none and not_found_handling:404-page, as configured in Wrangler.
const worker=(await import(`data:text/javascript;base64,${readFileSync(join(root,'../cloudflare/worker.js')).toString('base64')}`)).default;
const base='/audio-blackbox';
const env={ASSETS:{async fetch(request) {
  const path=decodeURIComponent(new URL(request.url).pathname);
  assert.ok(path.startsWith(base+'/'),path);
  const file=join(dist,path.slice(base.length));
  if(existsSync(file)&&statSync(file).isFile()) return new Response(readFileSync(file));
  return new Response(readFileSync(join(dist,'404.html')),{status:404});
}}};
async function request(url) { return worker.fetch(new Request(url),env); }
function files(dir=dist) { return readdirSync(dir,{withFileTypes:true}).flatMap(f=>f.isDirectory()?files(join(dir,f.name)):[join(dir,f.name)]); }
const pages=files().filter(p=>p.endsWith('.html'));

for(const expected of inventory.urls) test(`live status, redirect and SEO: ${expected.url}`,async()=>{
  // Oracle: changing a URL mapping, redirect status/target, metadata, structured
  // data value, or social tag from the captured live response must fail here.
  const response=await request(expected.url);
  assert.equal(response.status,expected.status);
  assert.equal(response.headers.get('location'),expected.location);
  const actual=metadata(await response.text());
  for(const key of ['canonical','hreflang','title','description','jsonLdTypes','jsonLd','social']) assert.deepEqual(actual[key],expected[key],key);
});
test('sitemap URLs and all language alternates match live',()=>{
  // Oracle: removing a URL or alternate (including x-default) fails.
  assert.deepEqual(sitemap(readFileSync(join(dist,'sitemap.xml'),'utf8')),inventory.sitemap);
});
test('LLM and IndexNow files remain byte-identical to approved sources',()=>{
  for(const name of ['llms.txt','llms-full.txt','6e548543f5dfe595b4046686467f6f97.txt']) assert.deepEqual(readFileSync(join(dist,name)),readFileSync(join(root,'../docs',name)));
});
test('every emitted HTML page has one unified header/footer with all languages',()=>{
  // Oracle: any page bypassing the shared shell or losing a navigation item fails.
  const shells=new Map();
  for(const file of pages) {
    const html=readFileSync(file,'utf8');
    assert.equal((html.match(/data-site-header/g)||[]).length,1,file);
    assert.equal((html.match(/data-site-footer/g)||[]).length,1,file);
    const header=html.match(/<header[^>]*data-site-header[\s\S]*?<\/header>/)[0];
    const footer=html.match(/<footer[^>]*data-site-footer[\s\S]*?<\/footer>/)[0];
    for(const shell of [header,footer]) {
      assert.deepEqual([...shell.matchAll(/hreflang="([^"]+)"/g)].map(m=>m[1]).sort(),['de','en','es','fr','it','pt-BR']);
    }
    for(const anchor of ['features','pricing','faq','download']) assert.ok(header.includes(`#${anchor}"`),`${file}: ${anchor}`);
    for(const href of ['/release/privacy-policy','/release/terms','https://github.com/alexandre-machado/audio-blackbox','mailto:alexandre@machado.cc']) assert.ok(footer.includes(href),`${file}: ${href}`);
    const lang=html.match(/<html lang="([^"]+)"/)[1];
    if(shells.has(lang)) assert.deepEqual([header,footer],shells.get(lang),file);
    else shells.set(lang,[header,footer]);
    // Same element/attribute structure across translated shells.
    const structure=s=>s.replace(/>[^<]+</g,'><').replace(/="[^"]*"/g,'=""');
    assert.deepEqual([structure(header),structure(footer)],shells.get('en')?.map(structure)??[structure(header),structure(footer)]);
  }
});
test('no template placeholders or front matter leak into output',()=>{
  for(const file of files().filter(f=>/\.(html|css|txt|xml)$/.test(f))) {
    const source=readFileSync(file,'utf8');
    assert.doesNotMatch(source,/\{%|\{\{|^---\n/,file);
  }
});
test('every internal link, asset and fragment resolves through the Worker',async()=>{
  // Oracle: removing a file, breaking a relative path or renaming an anchor fails.
  for(const file of pages) {
    const html=readFileSync(file,'utf8');
    const path=file.slice(dist.length).replace(/\/index\.html$/,'/').replace(/(\/release\/(privacy-policy|terms))\.html$/,'$1');
    const url=`https://alexandre.machado.cc${base}${path}`;
    for(const match of html.matchAll(/<(?:a|img|script|link)\b[^>]*>/g)) {
      const a=attrs(match[0]); const href=a.href??a.src;
      if(!href) continue;
      let target=new URL(href,url);
      if(target.origin!=='https://alexandre.machado.cc') continue;
      assert.ok(target.pathname.startsWith(base+'/'),`${file}: outside site ${href}`);
      let response=await request(target.href);
      for(let n=0;response.status===301&&n<5;n++) { target=new URL(response.headers.get('location'));response=await request(target.href); }
      assert.equal(response.status,200,`${file}: ${href}`);
      if(target.hash) {
        const body=await response.text();
        const id=decodeURIComponent(target.hash.slice(1));
        assert.ok([...body.matchAll(/\bid="([^"]+)"/g)].some(m=>m[1]===id),`${file}: missing fragment ${href}`);
      }
    }
  }
});
test('approved bilingual legal main content is unchanged',()=>{
  for(const name of ['privacy-policy','terms']) {
    const main=s=>s.match(/<main\b[^>]*>[\s\S]*?<\/main>/)[0];
    assert.equal(main(readFileSync(join(dist,`release/${name}.html`),'utf8')),main(readFileSync(join(root,`../docs/release/${name}.html`),'utf8')));
  }
});
test('visible FAQ and JSON-LD both reproduce approved YAML in every locale',()=>{
  // Oracle: omitting/rewording a visible answer, or rendering the wrong locale,
  // fails independently of the historical inventory metadata.
  const languages=parse(readFileSync(join(root,'../docs/_data/languages.yml'),'utf8'));
  for(const language of languages) {
    const data=parse(readFileSync(join(root,`../docs/_data/landing/${language.lang.toLowerCase()}.yml`),'utf8'));
    const html=readFileSync(join(dist,language.path.slice(1),'index.html'),'utf8');
    assert.ok(html.includes(`<html lang="${language.lang}">`));
    const faq=html.match(/<section id="faq">([\s\S]*?)<\/section>/)[1];
    const visible=[...faq.matchAll(/<h3[^>]*>(.*?)<\/h3>\s*<p[^>]*>(.*?)<\/p>/gs)].map(m=>({q:decode(m[1]),a:decode(m[2])}));
    assert.deepEqual(visible,data.faq,language.lang);
    const structured=metadata(html).jsonLd.find(d=>d['@type']==='FAQPage');
    assert.deepEqual(structured.mainEntity.map(e=>({q:e.name,a:e.acceptedAnswer.text})),data.faq,language.lang);
  }
});
test('production analytics loaders are preserved exactly once',()=>{
  // Oracle: dropping analytics while removing Liquid control blocks fails.
  for(const file of pages.filter(p=>!p.endsWith('/design/model.html'))) {
    const html=readFileSync(file,'utf8');
    for(const loader of ['googletagmanager.com/gtag/js','clarity.ms/tag']) assert.equal(html.split(loader).length-1,1,`${file}: ${loader}`);
  }
});
