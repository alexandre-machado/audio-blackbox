> **Nota editorial (2026-09-02, `@sre`, issue #287):** este documento é um rascunho gerado pelo Gemini, fornecido pelo dono do repositório, e foi verificado ponto a ponto contra a documentação oficial dos fornecedores em [#285](https://github.com/alexandre-machado/audio-blackbox/issues/285#issuecomment-5514894436). Dois pontos foram considerados **incorretos ou não aplicáveis** nessa verificação e não devem ser seguidos como escritos: o exemplo de JSON-LD `WebApplication` no item 3 (o site é a landing page de um app Android nativo, não um web app — `SoftwareApplication`/`MobileApplication` com `operatingSystem: "ANDROID"` é o que a documentação de dados estruturados do Google recomenda) e a sugestão implícita de manter um `robots.txt` dentro de `docs/` (este repositório não pode servir um arquivo na raiz do domínio `alexandre.machado.cc` — a raiz é o *user site* do GitHub Pages do dono, um repositório diferente; um `robots.txt` ausente já equivale a "permitir tudo", então não há nada para desbloquear aqui). O item 1 (tokens de crawler de IA) foi corrigido diretamente no texto abaixo, com as fontes oficiais citadas.

Para que seu aplicativo (`[https://alexandre.machado.cc/audio-blackbox/](https://alexandre.machado.cc/audio-blackbox/)`) seja lido, indexado e recomendado por inteligências artificiais como Gemini, ChatGPT e Copilot, você precisa focar em uma nova área chamada **GEO (Generative Engine Optimization)** ou **LLMO (Large Language Model Optimization)**.

Esses agentes dependem principalmente de duas coisas: as bases de treinamento em que se alimentam (como o Common Crawl) e os motores de busca tradicionais aos quais estão conectados (Google Search e Bing).

Aqui está o passo a passo técnico e de conteúdo para garantir que seu app seja "visto" pelas IAs:

### 1. Libere o acesso para os robôs (robots.txt)

As IAs usam seus próprios "crawlers" (rastreadores) para ler a internet. Você deve garantir que o arquivo `robots.txt` na raiz do seu site (`alexandre.machado.cc/robots.txt`) não esteja bloqueando esses robôs. Para garantir o acesso da IA, **não bloqueie** os seguintes agentes:

* `Googlebot` e `Google-Extended` (usado pelo Google/Gemini).
* `Bingbot` (usado pelo Copilot/Bing).
* `GPTBot`, `ChatGPT-User` e `OAI-SearchBot` (usados pela OpenAI/ChatGPT).
* `ClaudeBot`, `Claude-User` e `Claude-SearchBot` (usados pela Anthropic/Claude). **Correção (2026-09-02):** o token `Anthropic-ai` listado aqui originalmente não é um token atual — a documentação oficial da Anthropic (Claude Support, ["Anthropic Web Crawlers"](https://support.claude.com/en/articles/8896518), verificado em #285) só reconhece `ClaudeBot`, `Claude-User` e `Claude-SearchBot`.
* `CCBot` (Common Crawl, principal base de dados de treinamento de IAs).

> **Treinamento vs. recuperação/citação são decisões independentes.** Cada fornecedor grande já separa "o robô que alimenta o treinamento do modelo" do "robô que busca uma página ao vivo para responder a um usuário ou aparecer numa busca", e cada um pode ser liberado ou bloqueado sem afetar o outro: Google (`Googlebot` de busca vs. `Google-Extended` de treinamento para Gemini), OpenAI (`GPTBot` de treinamento vs. `OAI-SearchBot`/`ChatGPT-User` de busca/recuperação) e Anthropic (`ClaudeBot` de treinamento vs. `Claude-User`/`Claude-SearchBot` de recuperação/citação). Bloquear os robôs de recuperação/citação reduz diretamente a chance do app ser citado por uma IA — que é o objetivo deste documento —, então a lista acima assume que todos eles ficam liberados; a decisão sobre os robôs de *treinamento* (`GPTBot`, `ClaudeBot`, `CCBot`, `Google-Extended`) é separada e cabe ao dono do repositório.

### 2. Indexação nos Motores de Busca (Obrigatório)

Quando um usuário pergunta algo ao ChatGPT ou ao Copilot, eles frequentemente fazem uma pesquisa na web em tempo real para encontrar a resposta.

* **Google Search Console:** Cadastre seu domínio, envie o *Sitemap XML* e solicite a indexação manual da URL do *audio-blackbox*. O Gemini usa o Google para buscar informações em tempo real.
* **Bing Webmaster Tools:** Faça o mesmo no Bing. Isso é essencial, pois tanto o **Microsoft Copilot** quanto o recurso de busca web do **ChatGPT** utilizam o motor de busca do Bing para varrer a internet.

### 3. Use Dados Estruturados (Schema.org)

Modelos de linguagem amam dados estruturados porque eles dizem exatamente o que é a sua página sem que a IA precise "adivinhar". Adicione um JSON-LD no `<head>` do seu HTML declarando que seu site é um software.
Exemplo básico (você deve adaptar com os dados reais do app):

```html
<script type="application/ld+json">
{
  "@context": "https://schema.org",
  "@type": "WebApplication",
  "name": "Audio Blackbox",
  "url": "https://alexandre.machado.cc/audio-blackbox/",
  "author": {
    "@type": "Person",
    "name": "Alexandre Machado"
  },
  "description": "Descreva aqui exatamente o que seu app de áudio faz, quais problemas ele resolve e suas principais funcionalidades.",
  "applicationCategory": "MultimediaApplication",
  "operatingSystem": "Web browser"
}
</script>

```

### 4. Semântica HTML e Clareza de Conteúdo

IAs leem o texto cru da página. Se a página for apenas uma interface gráfica de um app em React/Vue sem texto explicativo, a IA não saberá para que ele serve.

* Use tags semânticas do HTML5 (`<header>`, `<main>`, `<article>`, `<section>`, `<h1>`, `<h2>`).
* Tenha uma seção clara de **"O que é o Audio Blackbox?"** e **"Como usar"**.
* Escreva em linguagem natural, respondendo às perguntas que os usuários fariam à IA. Por exemplo: *"O Audio Blackbox é uma ferramenta criada por Alexandre Machado para..."*

### 5. Crie Autoridade Externa (Backlinks)

Para que as IAs considerem sua ferramenta relevante na hora de recomendá-la, ela precisa ser mencionada em outros lugares da internet.

* Coloque o link do app na descrição dos seus vídeos do YouTube.
* Crie um repositório no **GitHub** (mesmo que seja apenas com o Readme) apontando para o app; as IAs varrem o GitHub constantemente.
* Divulgue a URL em fóruns como Reddit, dev.to, Medium ou comunidades de áudio/música. Quanto mais o nome "Audio Blackbox" e a sua URL aparecerem juntos em outros sites, maior a chance da IA aprender sobre a existência dele.