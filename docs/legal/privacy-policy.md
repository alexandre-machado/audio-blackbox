# Audio Blackbox — Privacy Policy

**Draft for owner review. Not legal advice, not reviewed by counsel.** This
document was produced by re-reading the app's source code at a specific
commit and citing what it found; it is a starting point for the owner (and,
if desired, an actual lawyer) to finalize before publishing.

*Last drafted: 2026-08-25, against commit `24cc125`.*

### Hosting options for the published policy URL (owner decides)

The Play Console requires a publicly reachable URL for this policy. Options,
with trade-offs — **not a recommendation, the owner picks**:

- **GitHub Pages from this repo** (e.g. rendering this file, or a copy of
  it, under a `gh-pages` branch or `docs/` Pages config). Pros: zero
  additional hosting cost or account, versioned alongside the code changes
  that the policy describes, easy to keep in sync. Cons: tied to this
  repo's visibility/availability (a repo rename or a GitHub outage affects
  the live URL), URL looks like `<owner>.github.io/<repo>/...` rather than a
  branded domain, and any custom domain would need separate DNS setup.
- **An external host** (the owner's own website/domain, a static-site
  service, or a policy-hosting tool such as a free privacy-policy
  generator's hosted page). Pros: can use a branded/stable domain
  independent of this repo, easier to hand off to non-engineering upkeep
  later, some tools auto-generate Data-safety-adjacent boilerplate. Cons:
  another account/service to maintain, risk of the hosted copy drifting out
  of sync with this source-controlled draft unless a publish step is added,
  and (for third-party generator tools) less control over exact wording
  than hand-authoring here.
- **A hybrid**: source of truth stays this file in the repo; the owner
  publishes it (unmodified or lightly reformatted) to whichever surface they
  prefer, and updates the published copy whenever this file changes. This
  avoids two documents diverging in *content* even if they live in two
  *places*.

This document does not select one — the owner should choose based on how
much they want the URL to be stable, brandable, or tied to this repo's
lifecycle.

---

## English

### What Audio Blackbox does

Audio Blackbox is a "black box" for audio: it continuously records from the
device microphone into a short rolling buffer, and only writes anything to
storage when you explicitly tap Save.

### What we collect

**Audio.** Audio Blackbox records microphone audio into memory only. It is
never transmitted anywhere — the app has no network permission and contains
no networking code for audio (verified: see the appendix in the pull request
that introduced this policy). Audio only reaches your device's storage when
you tap the Save action; until then it exists solely in a fixed-size,
in-memory rolling buffer that is overwritten as new audio arrives and is
discarded entirely when the recording engine stops or the app process ends.

**Coarse operational analytics.** Audio Blackbox uses Google Firebase
Analytics to collect a small set of operational signals: when the recording
engine changes state (idle/recording/paused/error), when a save is triggered
and completes (including the requested duration in minutes, the output
format, and the resulting file size in bytes), when the retention window
setting is changed, and sanitized error categories. These events are
transmitted to Google's Firebase Analytics service. **Firebase Analytics is
the one respect in which this app is not fully offline** — the app's core
recording function has no network dependency, but this operational telemetry
does leave the device. None of these events contain audio content,
transcripts, file names, storage paths, file contents, precise location, or
other device/user identifiers beyond whatever Firebase Analytics collects by
default as part of its own service (see Google's own privacy documentation
for Firebase Analytics' baseline collection). The owner has not yet decided
between keeping this telemetry, replacing it with a fully offline
alternative, or removing it — this policy will need to be re-checked against
whatever is chosen.

**Nothing else.** Audio Blackbox does not collect your name, email address,
location, contacts, or any other personal information. It does not use
advertising SDKs and it has no user accounts.

### Where recordings are stored

Recordings you explicitly save are written to your device's local storage
(via Android's `MediaStore`), in a folder visible to your device's own Files
or Music app. They never leave your device through Audio Blackbox — the app
does not upload, sync, or share them anywhere.

### How long we keep things

- **In-memory buffer:** discarded continuously; never persisted unless you
  save.
- **Saved recordings:** these are your files, stored locally on your device
  through Android's standard media storage, exactly like a photo taken with
  the camera app. Audio Blackbox keeps them for as long as they exist on
  your device — there is no separate expiry applied by the app.
- **On uninstalling the app:** saved recordings are not deleted by
  uninstalling Audio Blackbox. Android's shared-storage design keeps media
  files that an app has saved on the device even after that app is removed;
  they remain visible in your device's Files or Music app.
  **One caveat, not yet confirmed on a physical device:** if you later
  reinstall Audio Blackbox, the reinstalled app may not be able to *list*
  recordings it saved before, because Android requires an additional storage
  permission this app deliberately does not request in order to keep its
  permission footprint minimal (see the Data safety document for why). Your
  files are not lost in that case — they are still on the device, and still
  visible in the system Files/Music app — but Audio Blackbox's own in-app
  gallery may not find them until this is resolved. This is documented
  behaviour drawn from Android's own developer documentation; it has not yet
  been reproduced on the owner's hardware (tracked in issue #59). This
  wording will be revisited once that reproduction happens.
- **Operational analytics:** retained by Google Firebase Analytics according
  to Google's own data retention settings for the project, which the app
  owner configures in the Firebase console (not in app code).

### Permissions this app requests, and why

- **Microphone** — to record audio into the buffer.
- **Notifications** — to show the persistent recording-status notification
  required while the recording service runs in the foreground.
- **Foreground service / foreground service microphone** — required by
  Android to keep recording while the app is not in the foreground.
- **Ignore battery optimizations (request only)** — to reduce the chance the
  operating system kills the background recording service.
- **Receive boot completed** — used only to show a notification offering to
  resume recording after a reboot; it does not silently restart recording on
  its own.

Audio Blackbox does not request any permission to read your other media,
contacts, location, or files.

### Recording other people

Audio Blackbox may pick up audio of people other than you, depending on how
and where you use it. Consent-to-record laws vary by jurisdiction — some
places require only one party's consent, others require every party's
consent. **This is the user's and the app owner's responsibility to research
and comply with**, not something this app's design decides for you. Issue
#48 tracks this as an open item the owner has not yet resolved; this policy
does not attempt to resolve it either.

### Children

Audio Blackbox is not directed at children and is not knowingly used to
collect information from children.

### Changes to this policy

If this policy changes, the "Last drafted" date at the top will be updated.

### Contact

`TODO(owner)`: insert the contact email or method you want published on the
Play Store listing and in this document. Not filled in here because this is
a documentation-only drafting pass and the owner's preferred public contact
was not established at commit time.

---

## Português (Brasil)

### O que o Audio Blackbox faz

Audio Blackbox é uma "caixa preta" de áudio: grava continuamente o
microfone do dispositivo em um buffer curto e rotativo, e só grava qualquer
coisa no armazenamento quando você toca explicitamente em Salvar.

### O que coletamos

**Áudio.** O Audio Blackbox grava o áudio do microfone apenas na memória.
Ele nunca é transmitido a lugar nenhum — o aplicativo não tem permissão de
rede e não contém código de rede para o áudio (verificado: veja o apêndice
no pull request que introduziu esta política). O áudio só chega ao
armazenamento do seu dispositivo quando você toca em Salvar; até lá, ele
existe apenas em um buffer rotativo na memória, de tamanho fixo, que é
sobrescrito conforme chega novo áudio e é descartado por completo quando o
motor de gravação é desligado ou o processo do aplicativo termina.

**Dados analíticos operacionais resumidos.** O Audio Blackbox usa o Google
Firebase Analytics para coletar um pequeno conjunto de sinais operacionais:
mudanças de estado do motor de gravação (ocioso/gravando/pausado/erro),
quando um salvamento é iniciado e concluído (incluindo a duração solicitada
em minutos, o formato de saída e o tamanho do arquivo resultante em bytes),
mudanças na janela de retenção configurada, e categorias de erro
higienizadas. Esses eventos são transmitidos ao serviço Firebase Analytics
do Google. **O Firebase Analytics é o único aspecto em que este aplicativo
não é totalmente offline** — a função principal de gravação não depende de
rede, mas essa telemetria operacional sai do dispositivo. Nenhum desses
eventos contém conteúdo de áudio, transcrições, nomes de arquivo, caminhos
de armazenamento, conteúdo de arquivos, localização precisa ou outros
identificadores de dispositivo/usuário além do que o próprio Firebase
Analytics coleta por padrão como parte do seu serviço (consulte a
documentação de privacidade do próprio Google para a coleta padrão do
Firebase Analytics). O responsável pelo aplicativo ainda não decidiu entre
manter essa telemetria, substituí-la por uma alternativa totalmente offline,
ou removê-la — esta política precisará ser revisada de acordo com a decisão
tomada.

**Mais nada.** O Audio Blackbox não coleta seu nome, endereço de e-mail,
localização, contatos ou qualquer outra informação pessoal. Não usa SDKs de
publicidade e não tem contas de usuário.

### Onde as gravações são armazenadas

As gravações que você salva explicitamente são gravadas no armazenamento
local do seu dispositivo (via `MediaStore` do Android), em uma pasta visível
pelo aplicativo de Arquivos ou Música do próprio dispositivo. Elas nunca
saem do seu dispositivo através do Audio Blackbox — o aplicativo não faz
upload, sincronização ou compartilhamento delas em nenhum lugar.

### Por quanto tempo mantemos as coisas

- **Buffer na memória:** descartado continuamente; nunca persistido a menos
  que você salve.
- **Gravações salvas:** são seus arquivos, armazenados localmente no seu
  dispositivo através do armazenamento padrão de mídia do Android, assim
  como uma foto tirada com o aplicativo de câmera. O Audio Blackbox as
  mantém enquanto existirem no seu dispositivo — não há expiração separada
  aplicada pelo aplicativo.
- **Ao desinstalar o aplicativo:** as gravações salvas não são apagadas ao
  desinstalar o Audio Blackbox. O design de armazenamento compartilhado do
  Android mantém os arquivos de mídia que um aplicativo salvou no
  dispositivo mesmo depois que esse aplicativo é removido; eles continuam
  visíveis no aplicativo de Arquivos ou Música do dispositivo.
  **Uma ressalva, ainda não confirmada em um dispositivo físico:** se você
  reinstalar o Audio Blackbox depois, o aplicativo reinstalado pode não
  conseguir *listar* as gravações que salvou anteriormente, porque o Android
  exige uma permissão adicional de armazenamento que este aplicativo
  deliberadamente não solicita, para manter sua pegada de permissões mínima
  (veja o documento de segurança de dados para o motivo). Seus arquivos não
  são perdidos nesse caso — continuam no dispositivo e continuam visíveis no
  aplicativo de sistema de Arquivos/Música — mas a galeria interna do Audio
  Blackbox pode não encontrá-los até que isso seja resolvido. Este é um
  comportamento documentado a partir da própria documentação de
  desenvolvedores do Android; ainda não foi reproduzido no hardware do
  responsável pelo aplicativo (acompanhado na issue #59). Este texto será
  revisado assim que essa reprodução acontecer.
- **Dados analíticos operacionais:** retidos pelo Google Firebase Analytics
  de acordo com as próprias configurações de retenção de dados do projeto no
  Google, que o responsável pelo aplicativo configura no console do Firebase
  (não no código do aplicativo).

### Permissões que este aplicativo solicita, e por quê

- **Microfone** — para gravar áudio no buffer.
- **Notificações** — para exibir a notificação persistente de status de
  gravação exigida enquanto o serviço de gravação roda em primeiro plano.
- **Serviço em primeiro plano / serviço em primeiro plano de microfone** —
  exigido pelo Android para continuar gravando enquanto o aplicativo não
  está em primeiro plano.
- **Ignorar otimizações de bateria (apenas solicitação)** — para reduzir a
  chance de o sistema operacional encerrar o serviço de gravação em segundo
  plano.
- **Receber conclusão de inicialização (boot)** — usado apenas para exibir
  uma notificação oferecendo retomar a gravação após uma reinicialização;
  não reinicia a gravação silenciosamente por conta própria.

O Audio Blackbox não solicita nenhuma permissão para ler suas outras mídias,
contatos, localização ou arquivos.

### Gravação de outras pessoas

O Audio Blackbox pode captar áudio de pessoas além de você, dependendo de
como e onde você o usa. As leis de consentimento para gravação variam por
jurisdição — alguns lugares exigem o consentimento de apenas uma parte,
outros exigem o consentimento de todas as partes. **É responsabilidade do
usuário e do responsável pelo aplicativo pesquisar e cumprir essas leis**,
não algo que o design deste aplicativo decide por você. A issue #48
acompanha isso como um item em aberto que o responsável ainda não resolveu;
esta política também não tenta resolvê-lo.

### Crianças

O Audio Blackbox não é direcionado a crianças e não é usado
conscientemente para coletar informações de crianças.

### Alterações nesta política

Se esta política mudar, a data "Last drafted" no topo será atualizada.

### Contato

`TODO(owner)`: insira o e-mail ou meio de contato que deseja publicar na
ficha da Play Store e neste documento. Não preenchido aqui porque esta é
uma etapa de redação apenas de documentação, e o contato público preferido
do responsável ainda não estava definido no momento deste commit.
