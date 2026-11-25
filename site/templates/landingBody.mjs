// Ported from docs/_layouts/landing.html; approved copy remains in docs/_data/landing.
import {escape, json, stripHtml, relative, absolute} from '../lib.mjs';
export function landingBody({t,page,languages}) {
return `  <header class="hero">
    <div class="container hero-grid">
      <div>
        <div class="badge-pill">
          <span style="display:inline-block; width:8px; height:8px; background:var(--nvis-green); border-radius:50%; margin-right:4px;"></span>
          ${t.badge}
        </div>
        <h1>${t.hero_title}</h1>
        <p class="lead">
          ${t.hero_lead}
        </p>

        <div class="hero-actions">
          <a href="https://play.google.com/store/apps/details?id=cc.machado.audioblackbox" target="_blank" rel="noopener noreferrer" class="btn btn-primary">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M3.609 1.814L13.792 12 3.61 22.186a2.008 2.008 0 0 1-.61-1.425V3.24c0-.547.22-1.044.609-1.426zm11.602 11.602l2.392 2.392-12.79 7.382 10.398-9.774zm0-2.832L4.813 0.81l12.79 7.383-2.392 2.391zm1.414 1.416l3.528 2.037c.854.493.854 1.302 0 1.795l-3.528 2.037-2.122-2.122 2.122-2.122l-.001-.025z"/></svg>
            ${t.get_play}
          </a>
          <a href="${relative('/design/')}" class="btn btn-secondary" style="border-color:var(--primary-glow); color:var(--primary-light);">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20V10M18 20V4M6 20v-4"/></svg>
            ${t.design_model}
          </a>
        </div>

        <div class="hero-badges">
          <div class="hero-badge-item">
            <span style="color:var(--nvis-green)">✓</span> Android 10+ (API 29+)
          </div>
          <div class="hero-badge-item">
            <span style="color:var(--nvis-green)">✓</span> Material 3
          </div>
          <div class="hero-badge-item">
            <span style="color:var(--nvis-green)">✓</span> 100% Kotlin & Compose
          </div>
          <div class="hero-badge-item">
            <span style="color:var(--nvis-green)">✓</span> ${t.open_source_badge}
          </div>
        </div>
      </div>

      <!-- Live Interactive Simulator -->
      <div class="phone-wrapper">
        <div class="phone-glow"></div>
        <div class="phone-mockup">
          <div class="phone-camera-island"></div>
          
          <div class="phone-app-card">
            <div style="font-size:0.75rem; font-weight:700; color:var(--primary); text-transform:uppercase; margin-bottom:4px;">${t.engine_active}</div>
            <div style="font-size:1.1rem; font-weight:800; color:var(--text-main); margin-bottom:12px;">${t.buffer_status}</div>
            
            <div class="vu-meter-header">
              <span>${t.mic_input}</span>
              <span id="vu-db-text">-22 dB</span>
            </div>
            <div class="vu-meter-bars" id="vu-meter-container">
              <!-- 18 VU bars generated dynamically -->
            </div>

            <div class="buffer-widget">
              <div class="buffer-label-row">
                <span style="color:var(--text-muted)">${t.buffer_retention}</span>
                <span id="buffer-stat-text" style="font-family:'JetBrains Mono'; color:var(--text-main); font-weight:600;">29:51 / 30:00 (54.9 ${t.mb})</span>
              </div>
              <div class="buffer-track">
                <div class="buffer-progress"></div>
              </div>
            </div>
          </div>

          <button class="phone-btn-save" onclick="triggerSimulatedSave()">
            ${t.demo_save}
          </button>
          
          <button class="phone-btn-live">
            ${t.demo_live}
          </button>
        </div>
      </div>
    </div>
  </header>

  <!-- What is Audio Blackbox -->
  <section id="what-is">
    <div class="container">
      <div class="section-header">
        <span class="section-tag">${t.overview_tag}</span>
        <h2 class="section-title">${t.overview_title}</h2>
      </div>
      <div style="max-width:760px; margin:0 auto; color:var(--text-muted); font-size:1.05rem; line-height:1.7;">
        <p style="margin-bottom:20px;">
          ${t.overview_intro}
        </p>
        <p style="margin-bottom:20px;">
          ${t.overview_detail}
        </p>
        <p>
          ${t.overview_privacy}
        </p>
      </div>
    </div>
  </section>

  <!-- Use cases: the niches the listing and outreach target -->
  <section id="use-cases">
    <div class="container">
      <div class="section-header">
        <span class="section-tag">${t.usecases_tag}</span>
        <h2 class="section-title">${t.usecases_title}</h2>
        <p class="section-desc">${t.usecases_intro}</p>
      </div>

      <div class="features-grid">
        ${t.usecases.map((uc, index) => { const forloop = {last:index === t.usecases.length - 1}; return `
        <div class="feature-card">
          <div class="feature-icon-wrapper icon-orange">${uc.icon}</div>
          <h3>${uc.title}</h3>
          <p>${uc.desc}</p>
        </div>
        `; }).join('')}
      </div>

      <p class="section-desc" style="margin-top: 24px; font-size: 0.85rem;">${t.usecases_note}</p>
    </div>
  </section>

  <!-- Core Features Section -->
  <section id="features">
    <div class="container">
      <div class="section-header">
        <span class="section-tag">${t.features_tag}</span>
        <h2 class="section-title">${t.features_title}</h2>
        <p class="section-desc">${t.features_intro}</p>
      </div>

      <div class="features-grid">
        <div class="feature-card">
          <div class="feature-icon-wrapper icon-orange">⏱</div>
          <h3>${t.feature_lookback}</h3>
          <p>${t.feature_lookback_desc}</p>
        </div>

        <div class="feature-card">
          <div class="feature-icon-wrapper icon-green">🛡</div>
          <h3>${t.feature_network}</h3>
          <p>${t.feature_network_desc}</p>
        </div>

        <div class="feature-card">
          <div class="feature-icon-wrapper icon-blue">⚡</div>
          <h3>${t.feature_capture}</h3>
          <p>${t.feature_capture_desc}</p>
        </div>

        <div class="feature-card">
          <div class="feature-icon-wrapper icon-blue">📞</div>
          <h3>${t.feature_interrupt}</h3>
          <p>${t.feature_interrupt_desc}</p>
        </div>

        <div class="feature-card">
          <div class="feature-icon-wrapper icon-blue">🔋</div>
          <h3>${t.feature_battery}</h3>
          <p>${t.feature_battery_desc}</p>
        </div>

        <div class="feature-card">
          <div class="feature-icon-wrapper icon-orange">🎵</div>
          <h3>${t.feature_player}</h3>
          <p>${t.feature_player_desc}</p>
        </div>
      </div>

      <!-- Living Design Model Banner -->
      <div style="margin-top: 32px; background: linear-gradient(135deg, rgba(255,87,34,0.12) 0%, rgba(17,24,39,0.9) 100%); border: 1px solid rgba(255,87,34,0.3); border-radius: var(--radius-lg); padding: 28px 32px; display: flex; justify-content: space-between; align-items: center; gap: 24px; flex-wrap: wrap;">
        <div>
          <div style="font-family:'JetBrains Mono', monospace; font-size:0.75rem; font-weight:700; color:var(--primary-light); letter-spacing:0.08em; text-transform:uppercase; margin-bottom:6px;">${t.design_tag}</div>
          <h3 style="font-size:1.4rem; font-weight:800; color:var(--text-main); margin-bottom:8px;">${t.design_title}</h3>
          <p style="color:var(--text-muted); max-width:600px; font-size:0.95rem;">
            ${t.design_desc}
          </p>
        </div>
        <a href="${relative('/design/')}" class="btn btn-primary" style="background:var(--primary); border-color:var(--primary-light); flex-shrink:0;">
          ${t.design_launch}
        </a>
      </div>
    </div>
  </section>

  <!-- Calculator / Technical Specs -->
  <section id="specs">
    <div class="container">
      <div class="section-header">
        <span class="section-tag">${t.memory_tag}</span>
        <h2 class="section-title">${t.memory_title}</h2>
        <p class="section-desc">${t.memory_desc}</p>
      </div>

      <div class="calc-card">
        <div>
          <div class="calc-control">
            <div class="calc-label">
              <span>${t.retention_label}</span>
              <span id="calc-min-val" style="color:var(--primary); font-family:'JetBrains Mono'">${t.minutes_initial}</span>
            </div>
            <input type="range" min="5" max="120" value="30" step="5" class="calc-slider" id="calc-slider" oninput="updateMemoryCalc()" aria-label="${escape(t.retention_aria)}">
          </div>

          <div class="calc-control">
            <div class="calc-label">
              <span>${t.quality_label}</span>
              <span style="color:var(--text-muted)" id="calc-format-label">${t.format_initial}</span>
            </div>
            <select id="calc-rate-select" onchange="updateMemoryCalc()" style="width:100%; padding:12px; border-radius:var(--radius-sm); background:var(--cockpit-panel-raised); color:var(--text-main); border:1px solid var(--border-color); outline:none; font-family:'Inter'">
              <option value="16000:1" selected>${t.preset_voice}</option>
              <option value="32000:1">${t.preset_balanced}</option>
              <option value="44100:2">${t.preset_fidelity}</option>
            </select>
          </div>

          <div style="font-size:0.85rem; color:var(--text-dim); line-height:1.5;">
            ${t.preset_note}
          </div>

          <div style="font-size:0.85rem; color:var(--text-dim); line-height:1.5; margin-top:12px;">
            ${t.slider_note}
          </div>
        </div>

        <div class="calc-result-box">
          <div class="calc-result-sub" style="text-transform:uppercase; letter-spacing:0.05em; font-weight:700; margin-bottom:8px;">${t.ram_label}</div>
          <div class="calc-result-num" id="calc-ram-mb">54.9 ${t.mb}</div>
          <div class="calc-result-sub" id="calc-stats-detail">${t.throughput_initial}</div>
          <div class="calc-saved">
            <p class="calc-saved-label">${escape(t.saved_file_label)}</p>
            <p class="calc-saved-sizes" id="calc-saved-file" aria-live="polite"></p>
            <p class="calc-saved-note">${escape(t.opus_availability_note)}</p>
          </div>
        </div>
      </div>
    </div>
  </section>

  <!-- Screenshots -->
  <section id="screenshots">
    <div class="container">
      <div class="section-header">
        <span class="section-tag">${t.visuals_tag}</span>
        <h2 class="section-title">${t.visuals_title}</h2>
        <p class="section-desc">${t.visuals_desc}</p>
      </div>

      <div class="screenshots-row">
        <div class="screenshot-item">
          <img src="${relative('/assets/screenshot_dashboard.png')}" alt="${escape(t.dashboard_alt)}">
        </div>
        <div class="screenshot-item">
          <img src="${relative('/assets/screenshot_gallery.png')}" alt="${escape(t.gallery_alt)}">
        </div>
        <div class="screenshot-item">
          <img src="${relative('/assets/screenshot_settings.png')}" alt="${escape(t.settings_alt)}">
        </div>
      </div>
    </div>
  </section>

  <!-- Privacy Proof -->
  <section id="privacy">
    <div class="container">
      <div class="privacy-banner">
        <div class="privacy-icon">🔒</div>
        <div class="privacy-content">
          <h3>${t.privacy_title}</h3>
          <p>
            ${t.privacy_desc}
          </p>
        </div>
      </div>
    </div>
  </section>

  <!-- How to Use -->
  <section id="how-to-use">
    <div class="container">
      <div class="section-header">
        <span class="section-tag">${t.usage_tag}</span>
        <h2 class="section-title">${t.usage_title}</h2>
        <p class="section-desc">${t.usage_intro}</p>
      </div>
      <ol style="max-width:720px; margin:0 auto; color:var(--text-muted); font-size:1.05rem; line-height:1.8; padding-left:24px;">
        <li style="margin-bottom:14px;">${t.usage_1}</li>
        <li style="margin-bottom:14px;">${t.usage_2}</li>
        <li style="margin-bottom:14px;">${t.usage_3}</li>
        <li style="margin-bottom:14px;">${t.usage_4}</li>
        <li>${t.usage_5}</li>
      </ol>
    </div>
  </section>

  <!-- Download -->
  <section id="download">
    <div class="container">
      <div class="section-header">
        <span class="section-tag">${t.download_tag}</span>
        <h2 class="section-title">${t.download_title}</h2>
        <p class="section-desc">${t.download_intro}</p>
      </div>

      <div class="step-card" style="max-width:560px; margin:0 auto; text-align:center;">
        <h3 style="margin-bottom:10px; color:var(--text-main);">${t.install_title}</h3>
        <p style="color:var(--text-muted); font-size:0.95rem; margin-bottom:16px;">${t.install_desc}</p>
        <a href="https://play.google.com/store/apps/details?id=cc.machado.audioblackbox" target="_blank" rel="noopener noreferrer" class="btn btn-primary" style="font-size:0.85rem;">${t.open_play}</a>
      </div>
    </div>
  </section>

  <!-- FAQ -->
  <section id="faq">
    <div class="container">
      <div class="section-header">
        <span class="section-tag">${t.faq_tag}</span>
        <h2 class="section-title">${t.faq_title}</h2>
      </div>
      <div style="max-width:760px; margin:0 auto;">
        ${t.faq.map((faq, index) => { const forloop = {last:index === t.faq.length - 1}; return `
        <div style="margin-bottom:28px;">
          <h3 style="font-size:1.1rem; color:var(--text-main); margin-bottom:8px;">${escape(faq.q)}</h3>
          <p style="color:var(--text-muted); line-height:1.7;">${escape(faq.a)}</p>
        </div>
        `; }).join('')}
      </div>
    </div>
  </section>

  <!-- Footer -->
`;
}
