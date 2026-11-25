import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, mkdir, writeFile, cp, readdir, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,relative} from 'node:path';
import {publishable} from '../publishable.mjs';
test('static copy excludes hidden, Jekyll source, backup and configured paths',async()=>{
  // Oracle: any excluded fixture copied into the public tree fails; public assets survive.
  const temp=await mkdtemp(join(tmpdir(),'absite-copy-'));
  try {
    const source=join(temp,'source'),target=join(temp,'dist');
    const fixtures=['assets/visible.png','assets/.env','assets/.hidden/key','design/_secret.xml','design/#draft','design/backup~','design/icon/README.md','design/private/data','assets/excluded.txt'];
    for(const file of fixtures){await mkdir(join(source,file,'..'),{recursive:true});await writeFile(join(source,file),'fixture');}
    await cp(source,target,{recursive:true,filter:path=>publishable(relative(source,path),['design/private','assets/*.txt'])});
    const files=await readdir(target,{recursive:true});
    assert.deepEqual(files.filter(p=>!['assets','design','design/icon'].includes(p)).sort(),['assets/visible.png']);
  } finally {await rm(temp,{recursive:true,force:true});}
});
