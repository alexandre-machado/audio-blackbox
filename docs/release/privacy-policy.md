# Privacy Policy for Audio Blackbox

**Effective Date:** August 26, 2026  
**App:** Audio Blackbox (`cc.machado.audioblackbox`)

Audio Blackbox was built with a fundamental commitment to privacy: **your audio never leaves your device**. This Privacy Policy explains how Audio Blackbox handles data when you use the app.

---

## 1. Summary: Zero Network Egress & 100% On-Device

- **No Remote Servers:** Audio Blackbox contains no network code, no remote servers, and no third-party analytics SDKs.
- **No Internet Access:** The application does not request the `android.permission.INTERNET` permission. It is physically impossible for the app to send data over the network.
- **No User Accounts:** You do not need to create an account, log in, or provide any personal details (such as your name, email address, or phone number) to use Audio Blackbox.

---

## 2. Information Handled by the App

### Audio Data (Microphone)
- **Purpose:** The core function of Audio Blackbox is to maintain a temporary rolling buffer of recent ambient sound in memory (RAM), operating like a dashcam for audio.
- **In-Memory Retention:** Captured audio is held strictly in temporary RAM. As new sound is recorded, older sound is automatically overwritten.
- **Local Storage:** Audio is only written to device storage (as an `.m4a` / AAC file in your device's `Recordings` or `Music` directory) when you explicitly tap the save action or initiate forward recording.
- **Third-Party Access:** Audio Blackbox never shares, sells, or transmits your audio recordings to any third party.

---

## 3. Permissions Requested

1. `RECORD_AUDIO`: Required to capture ambient audio into the rolling buffer.
2. `POST_NOTIFICATIONS`: Required on Android 13+ to display a persistent foreground service notification while recording is actively running, ensuring you always know when audio capture is in progress.
3. `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE`: Required on modern Android versions to allow the rolling buffer to remain active in RAM while using other apps or with the screen turned off.

---

## 4. Data Storage and Deletion

- **Buffer Deletion:** Stopping the recording engine or closing the application immediately purges the in-memory rolling audio buffer.
- **Saved Recordings:** Audio files that you explicitly save remain in your device storage until you choose to delete them. You can delete recordings at any time using the in-app Gallery or your device's built-in file manager.
- **Recordings Survive Uninstallation:** Saved recordings are your files, stored on your device, and Android does not delete them when Audio Blackbox is uninstalled. If you later uninstall and then reinstall the app, however, Audio Blackbox may no longer be able to list those previously saved recordings in its in-app Gallery. This is because the app does not request the `READ_MEDIA_AUDIO` (Android 13+) or `READ_EXTERNAL_STORAGE` (Android 10-12) permission that would be needed to regain visibility into files it no longer has ownership attribution over after a reinstall. The files themselves are not deleted or hidden by this — they remain on your device and are reachable through your device's file manager or the system's media picker, even if the app's own Gallery does not show them. *(This mechanism is documented by Android's own storage guidance; the app's own permission set corroborates it. A deliberate on-device uninstall/reinstall reproduction is still pending — see issue [#59](https://github.com/alexandre-machado/audio-blackbox/issues/59) — so treat this as the documented, not yet hardware-confirmed, explanation.)*
- **Uninstalling:** Uninstalling Audio Blackbox discards all application preferences.

---

## 5. Children's Privacy

Audio Blackbox does not collect, store, or transmit personal data from anyone, including children under the age of 13.

---

## 6. Changes to this Policy

If this Privacy Policy is updated, the revised version will be published in this repository with an updated effective date.

---

## 7. Contact & Source Code

Audio Blackbox is open source. You can inspect the code and verify our privacy guarantees at:  
[https://github.com/alexandre-machado/audio-blackbox](https://github.com/alexandre-machado/audio-blackbox)

---
---

# Política de Privacidade do Audio Blackbox (pt-BR)

**Data de Vigência:** 26 de agosto de 2026  
**Aplicativo:** Audio Blackbox (`cc.machado.audioblackbox`)

O Audio Blackbox foi desenvolvido com um compromisso fundamental com a privacidade: **seu áudio nunca sai do seu dispositivo**. Esta Política de Privacidade explica como o Audio Blackbox trata os dados quando você usa o aplicativo.

---

## 1. Resumo: Zero Tráfego de Rede e 100% no Dispositivo

- **Sem Servidores Remotos:** O Audio Blackbox não contém código de rede, servidores remotos, nem SDKs de análise de terceiros.
- **Sem Acesso à Internet:** O aplicativo não solicita a permissão `android.permission.INTERNET`. É fisicamente impossível para o app enviar dados pela rede.
- **Sem Contas de Usuário:** Você não precisa criar uma conta, fazer login, ou fornecer qualquer dado pessoal (como nome, e-mail ou telefone) para usar o Audio Blackbox.

---

## 2. Informações Tratadas pelo Aplicativo

### Dados de Áudio (Microfone)
- **Finalidade:** A função principal do Audio Blackbox é manter um buffer circular temporário do som ambiente recente na memória (RAM), funcionando como uma câmera veicular (dashcam) para áudio.
- **Retenção em Memória:** O áudio capturado é mantido estritamente na memória RAM temporária. Conforme novo som é gravado, o som mais antigo é automaticamente sobrescrito.
- **Armazenamento Local:** O áudio só é gravado no armazenamento do dispositivo (como um arquivo `.m4a` / AAC no diretório `Recordings` ou `Music` do seu dispositivo) quando você toca explicitamente na ação de salvar ou inicia a gravação contínua para frente.
- **Acesso de Terceiros:** O Audio Blackbox nunca compartilha, vende ou transmite suas gravações de áudio a terceiros.

---

## 3. Permissões Solicitadas

1. `RECORD_AUDIO`: Necessária para capturar o áudio ambiente no buffer circular.
2. `POST_NOTIFICATIONS`: Necessária no Android 13+ para exibir uma notificação persistente de serviço em primeiro plano enquanto a gravação está ativa, garantindo que você sempre saiba quando a captura de áudio está em andamento.
3. `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE`: Necessária nas versões modernas do Android para permitir que o buffer circular permaneça ativo na RAM enquanto você usa outros aplicativos ou com a tela desligada.

---

## 4. Armazenamento e Exclusão de Dados

- **Exclusão do Buffer:** Interromper o motor de gravação ou fechar o aplicativo purga imediatamente o buffer circular de áudio em memória.
- **Gravações Salvas:** Os arquivos de áudio que você salva explicitamente permanecem no armazenamento do seu dispositivo até que você decida excluí-los. Você pode excluir gravações a qualquer momento pela Galeria do aplicativo ou pelo gerenciador de arquivos nativo do seu dispositivo.
- **As Gravações Sobrevivem à Desinstalação:** As gravações salvas são seus arquivos, armazenados no seu dispositivo, e o Android não as exclui quando o Audio Blackbox é desinstalado. Porém, se você desinstalar e depois reinstalar o aplicativo, o Audio Blackbox pode não conseguir mais listar essas gravações salvas anteriormente na Galeria do app. Isso acontece porque o aplicativo não solicita a permissão `READ_MEDIA_AUDIO` (Android 13+) ou `READ_EXTERNAL_STORAGE` (Android 10-12), que seria necessária para recuperar a visibilidade sobre arquivos cuja atribuição de propriedade ele perdeu após a reinstalação. Isso não exclui nem oculta os arquivos: eles permanecem no seu dispositivo e continuam acessíveis pelo gerenciador de arquivos ou pelo seletor de mídia do sistema, mesmo que a Galeria do próprio app não os exiba. *(Esse mecanismo está documentado na orientação oficial de armazenamento do Android; o conjunto de permissões do app corrobora isso. Uma reprodução deliberada de desinstalação/reinstalação em um dispositivo físico ainda está pendente — veja a issue [#59](https://github.com/alexandre-machado/audio-blackbox/issues/59) — portanto, trate isso como a explicação documentada, ainda não confirmada em hardware.)*
- **Desinstalação:** Desinstalar o Audio Blackbox descarta todas as preferências do aplicativo.

---

## 5. Privacidade Infantil

O Audio Blackbox não coleta, armazena ou transmite dados pessoais de ninguém, incluindo crianças menores de 13 anos.

---

## 6. Alterações a Esta Política

Se esta Política de Privacidade for atualizada, a versão revisada será publicada neste repositório com uma data de vigência atualizada.

---

## 7. Contato e Código-Fonte

O Audio Blackbox é de código aberto. Você pode inspecionar o código e verificar nossas garantias de privacidade em:  
[https://github.com/alexandre-machado/audio-blackbox](https://github.com/alexandre-machado/audio-blackbox)
