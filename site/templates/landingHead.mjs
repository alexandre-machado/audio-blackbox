// Ported from docs/_layouts/landing.html; approved copy remains in docs/_data/landing.
import {escape, json, stripHtml, relative, absolute} from '../lib.mjs';
export function landingHead({t,page,languages}) {
return `<meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${escape(t.title)}</title>
  <meta name="description" content="${escape(t.description)}">
  <link rel="icon" type="image/png" href="${relative('/assets/icon.png')}">
  <link rel="canonical" href="${absolute(page.url)}">
  ${languages.map((language, index) => { const forloop = {last:index === languages.length - 1}; return `
  <link rel="alternate" hreflang="${language.lang}" href="${absolute(language.path)}">
  `; }).join('')}
  <link rel="alternate" hreflang="x-default" href="${absolute('/')}">
  <link rel="sitemap" type="application/xml" href="${relative('/sitemap.xml')}">
  <link rel="alternate" type="text/plain" title="llms.txt" href="${absolute('/llms.txt')}">
  <link rel="alternate" type="text/plain" title="llms-full.txt" href="${absolute('/llms-full.txt')}">

  <!-- Open Graph / Meta -->
  <meta property="og:type" content="website">
  <meta property="og:url" content="${absolute(page.url)}">
  <meta property="og:site_name" content="Audio Blackbox">
  <meta property="og:locale" content="${page.og_locale}">
  ${languages.map((language, index) => { const forloop = {last:index === languages.length - 1}; return `${(language.og_locale != page.og_locale) ? `
  <meta property="og:locale:alternate" content="${language.og_locale}">
  ` : ''}`; }).join('')}
  <meta property="og:title" content="${escape(t.title)}">
  <meta property="og:description" content="${escape(t.social_description)}">
  <meta property="og:image" content="https://alexandre.machado.cc/audio-blackbox/assets/feature_graphic.png">
  <meta property="og:image:width" content="1024">
  <meta property="og:image:height" content="500">
  <meta property="og:image:alt" content="${escape(t.image_alt)}">

  <!-- Twitter Card -->
  <meta name="twitter:card" content="summary_large_image">
  <meta name="twitter:title" content="${escape(t.title)}">
  <meta name="twitter:description" content="${escape(t.social_description)}">
  <meta name="twitter:image" content="https://alexandre.machado.cc/audio-blackbox/assets/feature_graphic.png">

  <!-- Structured Data: MobileApplication (a schema.org subtype of SoftwareApplication) -->
  <script type="application/ld+json">
{
  "@context": "https://schema.org",
  "@type": "MobileApplication",
  "name": "Audio Blackbox",
  "alternateName": ${json(t.title)},
  "url": ${json(absolute(page.url))},
  "description": ${json(stripHtml(t.overview_intro))},
  "applicationCategory": "UtilitiesApplication",
  "operatingSystem": "Android 10+",
  "isAccessibleForFree": true,
  "permissions": "RECORD_AUDIO, POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, RECEIVE_BOOT_COMPLETED, INTERNET, ACCESS_NETWORK_STATE, com.android.vending.BILLING",
  "installUrl": "https://play.google.com/store/apps/details?id=cc.machado.audioblackbox",
  "downloadUrl": "https://play.google.com/store/apps/details?id=cc.machado.audioblackbox",
  "image": "https://alexandre.machado.cc/audio-blackbox/assets/icon.png",
  "screenshot": [
    "https://alexandre.machado.cc/audio-blackbox/assets/screenshot_dashboard.png",
    "https://alexandre.machado.cc/audio-blackbox/assets/screenshot_gallery.png",
    "https://alexandre.machado.cc/audio-blackbox/assets/screenshot_settings.png"
  ],
  "inLanguage": [
    "en",
    "pt-BR",
    "es",
    "fr",
    "de",
    "it"
  ],
  "featureList": [${json(stripHtml(t.feature_lookback_desc))}, ${json(stripHtml(t.feature_network_desc))}, ${json(stripHtml(t.feature_capture_desc))}, ${json(stripHtml(t.feature_interrupt_desc))}, ${json(stripHtml(t.feature_battery_desc))}, ${json(stripHtml(t.feature_player_desc))}],
  "author": {
    "@type": "Person",
    "name": "Alexandre Machado",
    "url": "https://github.com/alexandre-machado"
  },
  "license": "https://github.com/alexandre-machado/audio-blackbox/blob/main/LICENSE",
  "sameAs": [
    "https://github.com/alexandre-machado/audio-blackbox",
    "https://play.google.com/store/apps/details?id=cc.machado.audioblackbox"
  ],
  "offers": {
    "@type": "Offer",
    "price": "0",
    "priceCurrency": "USD"
  }
}
</script>

  <!-- Structured Data: FAQPage (mirrors the visible #faq section verbatim) -->
  <script type="application/ld+json">
  {"@context": "https://schema.org", "@type": "FAQPage", "inLanguage": ${json(page.lang)}, "mainEntity": [
    ${t.faq.map((faq, index) => { const forloop = {last:index === t.faq.length - 1}; return `
    {"@type": "Question", "name": ${json(faq.q)}, "acceptedAnswer": {"@type": "Answer", "text": ${json(faq.a)}}}${!(forloop.last) ? `,` : ''}
    `; }).join('')}
  ]}
  </script>

  <!-- Fonts -->
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">

`;
}
