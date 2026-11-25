// Optional developer check: mutate production templates, require a specific
// parity failure, then restore the source and rebuild even if the check fails.
import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync, spawnSync } from 'node:child_process';
const root=new URL('../',import.meta.url);
const mutations=[
  {file:'publishable.mjs',from:'return false;',to:'return true;',test:'test/publishable.test.mjs',failure:'static copy excludes hidden'},
  {file:'templates/layout.mjs',from:'escape(t.nav_pro)',to:"'Pro'",failure:'approved navigation/footer labels: pt-BR'},
  {file:'templates/layout.mjs',from:'escape(t.license_link)',to:"''",failure:'approved navigation/footer labels: en'},
  {file:'templates/document.mjs',from:"meta('robots','noindex')",to:"meta('robots','index')",failure:'live status, redirect and SEO: https://alexandre.machado.cc/audio-blackbox/404'},

  {file:'templates/landingHead.mjs',from:'rel="canonical"',to:'rel="broken-canonical"',failure:'live status, redirect and SEO: https://alexandre.machado.cc/audio-blackbox/'},
  {file:'templates/layout.mjs',from:'${footer(context)}',to:'',failure:'every emitted HTML page has one unified header/footer'},
  {file:'templates/layout.mjs',from:"relative('/assets/icon.png')",to:"relative('/assets/missing.png')",failure:'every internal link, asset and fragment resolves'},
];
for(const mutation of mutations) {
  const file=new URL(mutation.file,root);
  const original=readFileSync(file,'utf8');
  if(!original.includes(mutation.from)) throw new Error(`Mutation target missing: ${mutation.file}`);
  try {
    writeFileSync(file,original.replace(mutation.from,mutation.to));
    execFileSync(process.execPath,['build.mjs'],{cwd:root});
    const result=spawnSync(process.execPath,['--test','--test-reporter=spec',mutation.test ?? 'test/parity.test.mjs'],{cwd:root,encoding:'utf8'});
    const output=result.stdout+result.stderr;
    if(result.status!==1||!output.split('\n').some(line=>line.includes('✖')&&line.includes(mutation.failure))) throw new Error(`Mutation did not produce expected test failure:\n${output}`);
    console.log(`Verified mutation: ${mutation.file} -> ${mutation.failure}`);
  } finally {
    writeFileSync(file,original);
    execFileSync(process.execPath,['build.mjs'],{cwd:root});
  }
}
