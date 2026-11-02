# Importar do GitHub para o perfil

Depois de vincular o GitHub, a pessoa pode trazer para o perfil o que já é público lá:

- **Repositórios → projetos**: os repositórios públicos escolhidos viram cards na seção Projetos,
  com o link apontando para o repositório, a descrição do GitHub, a linguagem e as topics (quando
  existirem) na stack.
- **README do perfil → bio**: o README do repositório especial `{login}/{login}` é convertido de
  Markdown para o HTML da bio, com os links e imagens relativos ajustados para o GitHub.

Tudo é opcional e reversível: a pessoa escolhe os repositórios e pode editar/excluir os cards ou
trocar a bio depois pelos fluxos normais.

## Endpoints

Todos exigem sessão (`X-Session-Token`) e um GitHub vinculado; o handle vem do vínculo, nunca do
corpo.

| Método | Caminho | Uso |
| --- | --- | --- |
| `GET` | `/me/github/repos` | repositórios públicos do login vinculado (até 3 páginas de 100) |
| `GET` | `/me/github/readme` | README do perfil convertido para HTML, **sem salvar** (prévia) |
| `POST` | `/me/github/import/projects` | `{ "repos": ["owner/name", ...] }` → cria os cards escolhidos |
| `POST` | `/me/github/import/readme` | salva o README convertido como bio |

Sem vínculo, os quatro respondem `403 GITHUB_NOT_LINKED`. Sem README público, a prévia responde
`404 GITHUB_README_NOT_FOUND`.

## Regras

- Só entram repositórios **públicos** que a API devolveu para aquele login; o corpo não escolhe
  outra conta. Repositórios privados nunca aparecem.
- A importação de projetos passa pelas mesmas regras do cadastro manual: título, stack (até 10
  itens), link https e o teto de 20 projetos por perfil. Repetir a importação **não duplica**: o
  repositório já importado é contado como ignorado.
- No máximo 20 repositórios por chamada.
- O README é convertido com CommonMark + tabelas/riscado (GFM). Links relativos viram
  `github.com/{login}/{login}/blob/{branch}/...` e imagens relativas viram
  `raw.githubusercontent.com/{login}/{login}/{branch}/...`; âncoras, `mailto:`, `data:` e URLs
  absolutas ficam como estão.
- O HTML passa pelo `SafeHtml` da bio antes de ser gravado (mesmo teto e mesma allowlist): script,
  evento inline e CSS perigoso não entram. README grande demais recebe erro explícito
  (`README_TOO_LONG`) em vez de ser cortado em silêncio.
- Nenhum token do usuário é pedido ou guardado. A leitura usa o token fine-grained **somente
  leitura** do serviço (o mesmo da sincronização de contribuições), que eleva o limite de 60 para
  5.000 req/h.

## Cache

A lista de repositórios e o README (dados públicos) ficam em cache em memória por login, por 3
minutos (`githubRepositories` e `githubReadme` no `CacheConfig`, lidos por `CachedGitHubProfileReader`).
Abrir o diálogo e importar em seguida fazem uma ida ao GitHub, não duas. Falha e README ausente não
entram no cache. Consequência: um repositório criado, ou um README editado, aparece em até 3
minutos.

## Segurança e privacidade

- A escrita é sempre na própria conta autenticada; não existe importar para o perfil de outra pessoa.
- O README é conteúdo externo: é convertido no servidor, sanitizado e exibido no mesmo iframe
  sandboxed da bio (sem scripts), então uma tentativa de injeção não ganha a sessão de quem lê.
- A importação não guarda o Markdown cru no banco: fica só o HTML sanitizado da bio.

## Limitações

- O README de perfil só existe se a pessoa mantiver um repositório público com o próprio nome
  (`{login}/{login}`) e um README nele; sem isso, a prévia explica e não altera nada.
- As topics de um repositório só aparecem na stack se estiverem definidas no GitHub; a linguagem
  principal entra sempre.
- O teto atual da bio em HTML é de 20.000 caracteres; READMEs muito longos precisam ser resumidos
  pela pessoa (a importação recusa com mensagem clara).

## Testes

`GitHubProfileImportTests` cobre: lista de repositórios do vínculo, importação idempotente
(dedupe), repositório fora da lista rejeitado, README convertido com links/imagens absolutos,
`<script>` removido, bio salva e visível no perfil público, usuário sem vínculo (403), perfil sem
README (404), uma única ida ao GitHub para listar e importar, e README ausente fora do cache.
