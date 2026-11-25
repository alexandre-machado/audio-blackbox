// Ported from docs/_layouts/landing.html; approved copy remains in docs/_data/landing.
import {escape, json, stripHtml, relative, absolute} from '../lib.mjs';
export function landingScript({t,page,languages}) {
return `  <script>
    // VU Meter Animation
    const vuContainer = document.getElementById('vu-meter-container');
    const vuDbText = document.getElementById('vu-db-text');
    const totalBars = 18;
    const bars = [];

    for (let i = 0; i < totalBars; i++) {
      const bar = document.createElement('div');
      bar.className = 'vu-bar';
      vuContainer.appendChild(bar);
      bars.push(bar);
    }

    function updateVuMeter() {
      // Simulate natural speech ambient fluctuations
      const baseLevel = 0.35 + Math.sin(Date.now() / 400) * 0.25 + (Math.random() * 0.2);
      const activeCount = Math.floor(baseLevel * totalBars);
      const db = Math.round(-45 + (baseLevel * 42));
      vuDbText.innerText = \`\${db} dB\`;

      bars.forEach((bar, idx) => {
        if (idx < activeCount) {
          const heightPct = 25 + Math.min(75, (idx / totalBars) * 100);
          bar.style.height = \`\${heightPct}%\`;
          if (idx < 10) bar.className = 'vu-bar active-low';
          else if (idx < 14) bar.className = 'vu-bar active-mid';
          else if (idx < 16) bar.className = 'vu-bar active-high';
          else bar.className = 'vu-bar active-peak';
        } else {
          bar.style.height = '15%';
          bar.className = 'vu-bar';
        }
      });
    }

    setInterval(updateVuMeter, 100);

    const labels = { minutes: ${json(t.minutes)}, mono: ${json(t.mono)}, stereo: ${json(t.stereo)}, pcm: ${json(t.pcm)}, mb: ${json(t.mb)}, throughput: ${json(t.throughput)} };
    const decimal = new Intl.NumberFormat(document.documentElement.lang, {
      minimumFractionDigits: 1, maximumFractionDigits: 1
    });

    const savedFileSizes = ${json(t.saved_file_sizes)};
    // App: AacPayloadEncoder.BIT_RATE_PER_CHANNEL_BPS; StreamingOpusWriter.OpusFormat.
    // Voice encoder input is 16 kHz, others 48 kHz; Ogg granule timing is 48 kHz.
    const opusBitrates = { "16000:1": 24000, "32000:1": 32000, "44100:2": 96000 };

    // Memory Calculator
    function updateMemoryCalc() {
      const minutes = parseInt(document.getElementById('calc-slider').value, 10);
      // Presets mirror QualityPreset.kt: "<sampleRateHz>:<channelCount>".
      const [sampleRate, channels] = document.getElementById('calc-rate-select').value
        .split(':').map(v => parseInt(v, 10));

      document.getElementById('calc-min-val').innerText = \`\${minutes} \${labels.minutes}\`;
      document.getElementById('calc-format-label').innerText =
        \`\${labels.pcm} · \${channels === 2 ? labels.stereo : labels.mono}\`;

      // 16-bit PCM = 2 bytes per sample, per channel.
      const bytesPerSec = sampleRate * 2 * channels;
      const totalBytes = minutes * 60 * bytesPerSec;
      const totalMB = decimal.format(totalBytes / (1024 * 1024));
      const kbSec = decimal.format(bytesPerSec / 1024);

      document.getElementById('calc-ram-mb').innerText = \`\${totalMB} \${labels.mb}\`;
      const aacSize = 64000 * channels * minutes * 60 / 8 / (1024 * 1024);
      const opusSize = opusBitrates[document.getElementById('calc-rate-select').value] * minutes * 60 / 8 / (1024 * 1024);
      const savedText = savedFileSizes
        .replace('{aac}', decimal.format(aacSize)).replace('{opus}', decimal.format(opusSize));
      // Each codec's estimate stays on one line; a narrow screen breaks only at the separator.
      const savedEl = document.getElementById('calc-saved-file');
      savedEl.replaceChildren(...savedText.split(' · ').flatMap((part, i) => {
        const span = document.createElement('span');
        span.className = 'calc-saved-part';
        span.textContent = part;
        return i === 0 ? [span] : [document.createTextNode(' · '), span];
      }));
      document.getElementById('calc-stats-detail').innerText = \`\${kbSec} \${labels.throughput}\`;
    }

    updateMemoryCalc();

    function triggerSimulatedSave() {
      alert(${json(t.demo_alert)});
    }

    // Mobile Hamburger Navigation
    const navHamburger = document.getElementById('nav-hamburger');
    const navLinks = document.getElementById('nav-links');

    if (navHamburger && navLinks) {
      navHamburger.addEventListener('click', () => {
        const isOpen = navLinks.classList.toggle('active');
        navHamburger.classList.toggle('active');
        navHamburger.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
      });

      // Close menu when clicking any nav link
      navLinks.querySelectorAll('a').forEach(link => {
        link.addEventListener('click', () => {
          navLinks.classList.remove('active');
          navHamburger.classList.remove('active');
          navHamburger.setAttribute('aria-expanded', 'false');
        });
      });

      // Close menu when clicking outside
      document.addEventListener('click', (e) => {
        if (!navLinks.contains(e.target) && !navHamburger.contains(e.target) && navLinks.classList.contains('active')) {
          navLinks.classList.remove('active');
          navHamburger.classList.remove('active');
          navHamburger.setAttribute('aria-expanded', 'false');
        }
      });
    }
  </script>

`;
}
