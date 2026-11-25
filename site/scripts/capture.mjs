import {createServer} from 'node:http';
import {readFile, mkdir} from 'node:fs/promises';
import {resolve, extname, sep} from 'node:path';
import {fileURLToPath} from 'node:url';
import {chromium} from 'playwright';

const root = fileURLToPath(new URL('../dist/', import.meta.url));
const output = fileURLToPath(new URL('../captures/', import.meta.url));
const types = {'.html':'text/html; charset=utf-8','.css':'text/css','.js':'text/javascript','.png':'image/png','.svg':'image/svg+xml','.webp':'image/webp','.woff2':'font/woff2'};
const server = createServer(async (req, res) => {
  try {
    let path = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
    if (!path.startsWith('/audio-blackbox/')) {res.writeHead(404).end(); return;}
    path = path.slice('/audio-blackbox/'.length);
    if (path.endsWith('/') || !path) path += 'index.html';
    if (/^release\/(privacy-policy|terms)$/.test(path)) path += '.html';
    const file = resolve(root, path);
    if (!file.startsWith(root.endsWith(sep) ? root : root + sep)) {res.writeHead(403).end(); return;}
    const body = await readFile(file);
    res.writeHead(200, {'Content-Type':types[extname(file)] || 'application/octet-stream'}).end(body);
  } catch {res.writeHead(404).end();}
});
await mkdir(output, {recursive:true});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
let browser;
try {
  browser = await chromium.launch({headless:true});
  const origin = 'http://127.0.0.1:' + server.address().port;
  const pages = [['landing-en',''],['landing-pt-br','pt-br/'],['privacy-policy','release/privacy-policy'],['terms','release/terms'],['docs','features/01-capture-and-memory-core.html']];
  for (const [width,height] of [[390,844],[1366,900]]) {
    const context = await browser.newContext({viewport:{width,height}, reducedMotion:'reduce'});
    // Keep screenshots local and reproducible; do not send analytics during review.
    await context.route('**/*', route => route.request().url().startsWith(origin) ? route.continue() : route.abort());
    const page = await context.newPage();
    for (const [name,path] of pages) {
      const response = await page.goto(origin + '/audio-blackbox/' + path, {waitUntil:'networkidle'});
      if (response.status() !== 200) throw new Error(name + ': HTTP ' + response.status());
      await page.evaluate(() => document.fonts.ready);
      const file = resolve(output, name + '-' + width + 'x' + height + '.png');
      await page.screenshot({path:file, fullPage:true, animations:'disabled'});
      console.log(file);
    }
    await context.close();
  }
} finally {
  await browser?.close();
  await new Promise(resolve => server.close(resolve));
}
