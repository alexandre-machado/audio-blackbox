import { escape, absolute, relative } from '../lib.mjs';
export function documentHead({title, path, config, noindex=false}) {
  const meta = (name, content, property=false)=>`<meta ${property?'property':'name'}="${name}" content="${escape(content)}">`;
  const seoTitle=title||config.title;
  return `<meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${title ? `${escape(title)} - ` : ''}${escape(config.title)}</title>
${meta('description',config.description)}
<link rel="icon" type="image/png" href="${relative('/assets/icon.png')}">
${noindex ? meta('robots','noindex') : `<link rel="canonical" href="${absolute(path)}">`}
${meta('og:title',seoTitle,true)}${meta('og:description',config.description,true)}${meta('og:type','website',true)}
${noindex ? '' : meta('og:url',absolute(path),true)}${meta('og:site_name',config.title,true)}
${meta('twitter:card','summary_large_image')}${meta('twitter:title',seoTitle)}${meta('twitter:description',config.description)}
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;600;700;800&display=swap" rel="stylesheet">
<link rel="stylesheet" href="${relative('/assets/css/style.css')}">`;
}
