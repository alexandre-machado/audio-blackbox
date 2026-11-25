import { escape, relative } from '../lib.mjs';

// One shell for every page. Only approved labels and locale destinations vary.
export function header({t, page, languages}) {
  return `<header class="site-header" data-site-header>
  <div class="site-shell">
    <a class="site-brand" href="${relative(page.path)}"><img src="${relative('/assets/icon.png')}" width="36" height="36" alt="">Audio Blackbox</a>
    <nav class="site-nav" aria-label="${escape(t.menu_label)}">
      <a href="${relative(page.path)}#features">${escape(t.nav_features)}</a>
      <a href="${relative(page.path)}#pricing">Pro</a>
      <a href="${relative(page.path)}#faq">${escape(t.nav_faq)}</a>
      <a href="${relative(page.path)}#download">${escape(t.nav_download)}</a>
    </nav>
    <div class="site-languages" role="navigation" aria-label="${escape(t.language_label)}">${languageLinks(languages)}</div>
  </div>
</header>`;
}
function languageLinks(languages) {
  return languages.map(l=>`<a href="${relative(l.path)}" lang="${l.lang}" hreflang="${l.lang}">${escape(l.name)}</a>`).join(' ');
}
export function footer({t, languages}) {
  return `<footer class="site-footer" data-site-footer><div class="site-shell">
  <a class="site-brand" href="${relative('/')}">Audio Blackbox</a>
  <div class="site-footer-links">
    <a href="${relative('/release/privacy-policy')}">${escape(t.privacy_link)}</a>
    <a href="${relative('/release/terms')}">${escape(t.terms_link)}</a>
    <a href="https://github.com/alexandre-machado/audio-blackbox">${escape(t.repo_link)}</a>
    <a href="mailto:alexandre@machado.cc">alexandre@machado.cc</a>
  </div>
  <div class="site-languages" role="navigation" aria-label="${escape(t.language_label)}">${languageLinks(languages)}</div>
  <p>${t.copyright}</p><p>${t.consent}</p>
  </div></footer>`;
}
export function layout({head,body,scripts='',context}) {
  return `<!DOCTYPE html>\n<html lang="${context.page.lang}"><head>${head}
<link rel="stylesheet" href="${relative('/assets/css/tokens.css')}">
<link rel="stylesheet" href="${relative('/assets/css/shell.css')}"></head>
<body>${header(context)}${body}${footer(context)}${scripts}</body></html>\n`;
}
