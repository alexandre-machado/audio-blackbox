export const base = '/audio-blackbox';
export const origin = 'https://alexandre.machado.cc';
export const relative = path => base + path;
export const absolute = path => new URL(relative(path),origin).href;
export const escape = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[c]);
export const json = value => JSON.stringify(value).replace(/</g, '\\u003c');
export const stripHtml = value => value.replace(/<[^>]*>/g, '');
export const stripFrontMatter = value => value.replace(/^---\r?\n[\s\S]*?\r?\n---\r?\n/, '').replace(/^---\r?\n---\r?\n/, '');
