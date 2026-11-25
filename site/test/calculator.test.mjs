import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import {parse} from 'yaml';

const root = new URL('../', import.meta.url);
const locales = {en:'', 'pt-br':'pt-br/', de:'de/', es:'es/', fr:'fr/', it:'it/'};
for (const [locale, path] of Object.entries(locales)) {
  test(locale + ': saved-file translations are present and rendered', () => {
    // Oracle: a missing/empty translation or omitted calculator note fails.
    const t = parse(readFileSync(new URL('../docs/_data/landing/' + locale + '.yml', root), 'utf8'));
    const html = readFileSync(new URL('dist/' + path + 'index.html', root), 'utf8');
    for (const key of ['saved_file_label','saved_file_sizes','opus_availability_note','size_unit_note']) {
      assert.equal(typeof t[key], 'string', key);
      assert.ok(t[key].length > 0, key);
      assert.ok(html.includes(t[key]), key);
    }
    assert.match(t.saved_file_sizes, /{aac}.*{opus}/);
  });
}
for (const [preset, aac, opus, ram] of [
  ['16000:1','4.6','1.7','18.3'],
  ['32000:1','4.6','2.3','36.6'],
  ['44100:2','9.2','6.9','100.9'],
]) {
  test(preset + ': 10-minute saved-file sizes and unchanged PCM RAM', () => {
    // Oracle: actual generated browser script must produce these independently
    // calculated 10-minute payload estimates (binary MB), not a test-side formula.
    const html = readFileSync(new URL('dist/index.html', root), 'utf8');
    assert.ok(html.includes('value="' + preset + '"'), 'preset must exist in real controls');
    const script = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].find(m => m[1].includes('function updateMemoryCalc'))[1];
    const nodes = new Map();
    const document = {
      documentElement: {lang:'en-US'},
      getElementById(id) {
        if (id.startsWith('nav-')) return null;
        if (!nodes.has(id)) nodes.set(id, {value:id === 'calc-slider' ? '10' : preset, appendChild(){}});
        return nodes.get(id);
      },
      createElement() {return {};},
    };
    runInNewContext(script, {document, Intl, setInterval(){}});
    assert.equal(nodes.get('calc-saved-file').innerText, 'AAC ≈ ' + aac + ' MB · Opus (.ogg) ≈ ' + opus + ' MB');
    assert.equal(nodes.get('calc-ram-mb').innerText, ram + ' MB');
  });
}
