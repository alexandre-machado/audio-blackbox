import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {parse} from 'yaml';
const source=readFileSync(new URL('../../.github/workflows/site.yml',import.meta.url),'utf8');
const workflow=parse(source);
test('PR workflow is read-only, credential-free and runs the complete Node gate',()=>{
  // Oracle: adding a credential/deploy capability or dropping a validation step fails.
  assert.ok('pull_request' in workflow.on);
  const job=workflow.jobs['test-pr'];
  assert.equal(job.if,"github.event_name == 'pull_request'");
  assert.deepEqual(job.permissions,{contents:'read'});
  const steps=JSON.stringify(job.steps);
  assert.doesNotMatch(steps,/secrets\.|environment|deploy-pages/);
  for(const command of ['npm test --prefix site','npm test --prefix cloudflare','bash scripts/ci/stage-cloudflare-site.sh','wrangler deploy --dry-run']) assert.ok(steps.includes(command),command);
  for(const [id,other] of Object.entries(workflow.jobs)) if(id!=='test-pr') assert.ok(other.if.includes("github.event_name != 'pull_request'"),id);
  for(const path of ['site/**','scripts/ci/stage-cloudflare-site.sh']) assert.ok(workflow.on.push.paths.includes(path));
});
test('deployment stages with the tested script and checks files before upload',()=>{
  // Oracle: reverting to inline staging or removing the pre-upload guard fails.
  for(const id of ['validate-cloudflare','deploy-cloudflare']){
    const steps=workflow.jobs[id].steps.map(step=>step.run??'').join('\n');
    assert.ok(steps.includes('bash "$WORK_DIR/stage-cloudflare-site.sh" "$ARTIFACT_DIR"'));
    assert.doesNotMatch(steps,/cp .*404\.html/);
  }
  const steps=workflow.jobs['build-cloudflare'].steps;
  const guard=steps.findIndex(s=>s.run?.includes('find site/dist ! -type f ! -type d'));
  assert.ok(guard>=0&&guard<steps.findIndex(s=>s.uses?.startsWith('actions/upload-artifact@')));
  for(const job of Object.values(workflow.jobs)) for(const step of job.steps) {
    for(const match of (step.run??'').matchAll(/npm ci([^\n&]*)/g)) assert.ok(match[1].includes('--ignore-scripts'));
  }
});
