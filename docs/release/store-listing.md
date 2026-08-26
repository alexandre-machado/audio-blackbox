# Google Play Store Listing & Launch Metadata

Comprehensive store listing copy, graphic assets specification, and policy declaration answers for **Audio Blackbox** on Google Play Console (Issue #48).

---

## 1. Store Listing Copy (Bilingual)

### English (`en-US`)

#### App Title (≤ 30 characters)
```text
Audio Blackbox
```
*(14 / 30 characters)*

#### Short Description (≤ 80 characters)
```text
Continuous ambient audio buffer. Save recent audio anytime.
```
*(59 / 80 characters)*

#### Full Description (≤ 4000 characters)
```text
Audio Blackbox works like a dashcam, but for sound. It keeps a rolling buffer of recent ambient audio in memory—never writing anything to storage until you choose to save it.

HOW IT WORKS
• Continuous In-Memory Buffer: When recording is enabled, the app continuously buffers the last few minutes of audio (5 to 60 minutes, configured by you in Settings) entirely in device RAM.
• Overwrite Loop: As new audio arrives, the oldest audio in the buffer is automatically overwritten.
• Instant Single-Tap Save: If a conversation, meeting note, sudden inspiration, or incident just happened, tap "Save recent audio" to immediately export the entire buffered window to standard M4A (AAC) format.
• Forward Continuous Recording: Need to record continuously from now on? Start forward recording to write directly to an audio file.

PRIVACY BY DESIGN
• 100% On-Device: Audio stays strictly inside your device's memory.
• Zero Network Egress: Audio Blackbox contains no internet permissions, no telemetry trackers, and no external servers.
• User Controlled: You start and stop capture explicitly via the dashboard toggle. A persistent foreground notification remains visible the entire time recording is active.

SMART INTEGRATION & GALLERY
• Telephony Call Courtesy: Capture automatically pauses during phone calls and seamlessly resumes when the call ends, filling call gaps with silence to keep timestamps accurate.
• In-App Gallery: Play back saved recordings, seek through waveforms, share audio to other apps, or delete recordings directly.
• Material 3 Design: Native, dynamic color theming that respects your Android system aesthetic and dark mode.

LEGAL & CONSENT NOTICE
Recording conversations may require the consent of all parties involved depending on applicable laws in your jurisdiction. You are solely responsible for ensuring your use of Audio Blackbox complies with all relevant local, state, and national laws.
```

---

### Portuguese (Brazil) (`pt-BR`)

#### Nome do App (≤ 30 caracteres)
```text
Audio Blackbox
```
*(14 / 30 caracteres)*

#### Descrição Breve (≤ 80 caracteres)
```text
Gravação contínua em memória. Salve o áudio recente a qualquer momento.
```
*(71 / 80 caracteres)*

#### Descrição Completa (≤ 4000 caracteres)
```text
O Audio Blackbox funciona como uma câmera veicular (dashcam), mas para som. Ele mantém um buffer circular contínuo do áudio ambiente recente na memória RAM — sem gravar nada no armazenamento até que você decida salvar.

COMO FUNCIONA
• Buffer Contínuo em Memória: Com a gravação ativa, o aplicativo retém os últimos minutos de áudio (de 5 a 60 minutos, configuráveis em Configurações) exclusivamente na memória RAM.
• Substituição Automática: Conforme novo áudio é capturado, o áudio mais antigo é automaticamente descartado do buffer.
• Salvamento Instantâneo com Um Toque: Se uma conversa, ideia importante ou momento marcante acabou de acontecer, toque em "Salvar o passado" para exportar imediatamente todo o buffer recente em formato padrão M4A (AAC).
• Gravação Contínua para Frente: Precisa gravar continuamente a partir de agora? Inicie a gravação contínua direta para um arquivo de áudio.

PRIVACIDADE POR DESIGN
• 100% no Dispositivo: O áudio nunca sai do seu celular.
• Zero Acesso à Rede: O Audio Blackbox não possui permissão de internet, não utiliza servidores externos e não realiza telemetria.
• Controle Total do Usuário: Você inicia e interrompe a captura diretamente pelo interruptor no painel. Uma notificação persistente permanece visível durante todo o tempo em que a captura estiver ativa.

INTEGRAÇÃO INTELIGENTE E GALERIA
• Pausa em Chamadas Telefônicas: A captura pausa automaticamente durante ligações e retorna assim que a chamada é encerrada, preenchendo a lacuna com silêncio para manter o sincronismo temporal exato.
• Galeria Integrada: Ouça suas gravações salvas, navegue pela reprodução, compartilhe arquivos com outros apps ou exclua gravações com facilidade.
• Design Material 3: Visual limpo com suporte a cores dinâmicas do sistema e modo escuro nativo do Android.

AVISO LEGAL E DE CONSENTIMENTO
A gravação de conversas pode exigir o consentimento de todos os participantes, conforme as leis vigentes na sua jurisdição. O uso do aplicativo em conformidade com as leis aplicáveis é de responsabilidade do usuário.
```

---

## 2. Play Store Graphical Assets

| Asset | Dimension & Format | Status & File Location |
| :--- | :--- | :--- |
| **App Icon (Store Listing)** | 512 x 512 px, 32-bit PNG (with alpha) | [`docs/design/store/ic_launcher_store_512.png`](docs/design/store/ic_launcher_store_512.png) |
| **Feature Graphic** | 1024 x 500 px, 24-bit PNG (RGB, no alpha) | [`docs/design/store/feature_graphic_1024x500.png`](docs/design/store/feature_graphic_1024x500.png) |
| **Phone Screenshots** | Min 2, up to 8 (1080x1920 or native) | Generated automatically by CI `ScreenshotCaptureTest` into `build/screen-captures/` |

---

## 3. Data Safety Form (Google Play Console)

Copy-pasteable questionnaire responses for Google Play Console:

1. **Does your app collect or share any user data?**
   - **Answer**: `Yes` (Audio recordings).
2. **Audio (Voice or sound recordings)**:
   - **Collected?**: `Yes`
   - **Shared with third parties?**: `No`
   - **Is this data processed ephemerally?**: `No` (Audio is buffered in RAM and saved to on-device storage upon user request).
   - **Is this data required or optional?**: `Required` (Core functionality of the app).
   - **Purposes**: `App functionality`.
3. **Data transfer and security practices**:
   - **Is data transferred over a secure connection?**: `N/A - Data is never transferred off the device (Zero network egress)`.
   - **Can users request data deletion?**: `Yes` (Users can delete exported files at any time via the in-app Gallery or standard file managers. In-memory buffer is discarded when recording is stopped or the app is uninstalled).

---

## 4. Foreground Service Declaration (`TYPE_MICROPHONE`)

Because `targetSdk` is 36, Google Play requires a specific declaration for `FOREGROUND_SERVICE_MICROPHONE`:

- **Use case selection**: `Background Audio Access / Voice recording`.
- **Functionality Description**:
  > Audio Blackbox provides a continuous rolling audio buffer in device RAM (user-configured between 5 to 60 minutes), operating like an audio dashcam. The user explicitly controls the service via a prominent switch on the dashboard and persistent system notification. When the user taps 'Save recent audio', the recent in-memory buffer is exported to device storage. If the foreground service were stopped, the in-memory rolling buffer would be immediately lost, preventing the user from retrieving recently elapsed audio.
- **Demo Video Requirement**:
  > A short video recorded on a real device or emulator showing:
  > 1. User launching the app and toggling the recording engine ON.
  > 2. Persistent notification appearing in the system drawer.
  > 3. Tapping 'Save recent audio' and viewing the exported recording in the Gallery.

---

## 5. App Category & Target Audience

- **Category**: `Tools` / `Audio & Video`.
- **Target Audience**: `18 and over` (or `13 and over`).
- **Contains Ads**: `No`.
- **Pricing**: `Free`.
