import { readFile, writeFile, mkdir, rm, cp, readdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { parse } from 'yaml';
import { marked } from 'marked';
import { relative, stripFrontMatter } from './lib.mjs';
import { layout } from './templates/layout.mjs';
import { documentHead } from './templates/document.mjs';
import { landingHead } from './templates/landingHead.mjs';
import { landingBody } from './templates/landingBody.mjs';
import { landingScript } from './templates/landingScript.mjs';

const root = dirname(fileURLToPath(import.meta.url));
const docs = join(root,'../docs');
const dist = join(root,'dist');
const read = path => readFile(join(docs,path),'utf8');
const config = parse(await read('_config.yml'));
const languages = parse(await read('_data/languages.yml'));
const translations = Object.fromEntries(await Promise.all(languages.map(async l => [l.lang,parse(await read(`_data/landing/${l.lang.toLowerCase()}.yml`))])));
const english = {t:translations.en,page:{...languages[0],url:'/'},languages};
async function emit(path, content) { const target=join(dist,path); await mkdir(dirname(target),{recursive:true}); await writeFile(target,content); }
// Preserve production analytics from the existing source, without a Liquid runtime.
function analytics(source) {
  const block=source.match(/\{% if jekyll.environment == 'production' %\}([\s\S]*?)\{% endif %\}\s*<\/head>/)?.[1] ?? '';
  return block.replace(/\{%[^%]*%\}/g,'').replace(/\{\{ site\.(google_analytics|clarity_id) \}\}/g,(_,key)=>config[key] ?? '');
}
function headOf(source) { return source.match(/<head>([\s\S]*?)<\/head>/)[1].replace(/\{% if jekyll.environment == 'production' %\}[\s\S]*/,analytics(source)); }
const landing = await read('_layouts/landing.html');
const defaultAnalytics = analytics(await read('_layouts/default.html'));
await rm(dist,{recursive:true,force:true});
await mkdir(dist,{recursive:true});
await cp(join(docs,'assets'),join(dist,'assets'),{recursive:true});
await emit('assets/css/style.css',stripFrontMatter(await read('assets/css/style.css')));
await emit('assets/css/landing.css',landing.match(/<style>([\s\S]*?)<\/style>/)[1]);
await emit('assets/css/tokens.css',(await read('design/index.html')).match(/:root\s*\{[^}]+\}/)[0]);
await emit('assets/css/shell.css',await readFile(join(root,'shell.css'),'utf8'));
for(const l of languages) {
  const context={t:translations[l.lang],page:{...l,url:l.path},languages};
  const body=landingBody(context).replace('<section id="download"','<span id="pricing"></span><section id="download"');
  await emit(`${l.path.slice(1)}index.html`,layout({context,head:landingHead(context)+`<link rel="stylesheet" href="${relative('/assets/css/landing.css')}">`+analytics(landing),body:`<main>${body}</main>`,scripts:landingScript(context)}));
}
for(const name of ['privacy-policy','terms']) {
  const source=await read(`release/${name}.html`);
  // Keep the approved bilingual body, language toggle and scripts verbatim.
  const body=source.match(/<body>([\s\S]*?)<\/body>/)[1].replace(/<nav>[\s\S]*?<\/nav>/,'').replace(/<footer>[\s\S]*?<\/footer>/,'');
  await emit(`release/${name}.html`,layout({context:english,head:headOf(source),body}));
}
async function markdownFiles(dir='') {
  const entries=await readdir(join(docs,dir),{withFileTypes:true});
  const results=await Promise.all(entries.map(e=>e.isDirectory()&&!e.name.startsWith('_') ? markdownFiles(join(dir,e.name)) : e.isFile()&&e.name.endsWith('.md') ? [join(dir,e.name)] : []));
  return results.flat();
}
for(const file of await markdownFiles()) {
  // Jekyll/GitHub Pages excludes README documents from public HTML routes.
  if(file.split('/').pop()==='README.md') continue;
  const markdown=await read(file);
  const path=`/${file.replace(/\.md$/,'.html')}`;
  const title=markdown.match(/^# (.+)$/m)?.[1] ?? '';
  // This existing repo-source link cannot resolve within the hosted site.
  const body=marked(markdown).replaceAll('../../app/src/','https://github.com/alexandre-machado/audio-blackbox/blob/main/app/src/')
    .replace(/href="([^"#?]+)\.md([#?][^"]*)?"/g,'href="$1.html$2"')
    .replace(/href="docs\//g,`href="${relative('/')}`);
  await emit(path.slice(1),layout({context:english,head:documentHead({title,path,config})+defaultAnalytics,body:`<main class="container markdown-body">${body}</main>`}));
}
const notFound=stripFrontMatter(await read('404.html')).replace("{{ '/' | relative_url }}",relative('/'));
await emit('404.html',layout({context:english,head:documentHead({title:'404',path:'/404.html',config,noindex:true})+defaultAnalytics,body:`<main class="container markdown-body">${notFound}</main>`}));
await cp(join(docs,'design'),join(dist,'design'),{recursive:true});
for(const name of ['index.html','model.html']) {
  const source=await read(`design/${name}`);
  const body=source.match(/<body>([\s\S]*?)<\/body>/)[1].replace(/<nav>[\s\S]*?<\/nav>/,'');
  await emit(`design/${name}`,layout({context:english,head:headOf(source),body}));
}
for(const file of ['sitemap.xml','llms.txt','llms-full.txt','6e548543f5dfe595b4046686467f6f97.txt']) await cp(join(docs,file),join(dist,file));
console.log('Built site/dist from approved docs sources (no Jekyll, no network).');
