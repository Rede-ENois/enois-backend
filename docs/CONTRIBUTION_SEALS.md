# Selos de contribuição

Reconhecimento público de quem contribui para o Énóis. O nível é **derivado** de evidências
verificadas nos repositórios oficiais da organização
[`Rede-ENois`](https://github.com/Rede-ENois) — nunca de um contador enviado pelo cliente. Selo é
reconhecimento, não privilégio: nada aqui dá poder de moderação.

## Categorias e níveis

### Código (`CODE`)

Pull requests **integrados** (merged) nos repositórios elegíveis, criados pela identidade GitHub
vinculada. A contagem usa o PR como unidade: squash/rebase não multiplicam, PR fechado sem merge não
conta e PR revertido deixa de contar.

| Nível | Faixa de PRs integrados |
| --- | --- |
| Ajudante (`AJUDANTE`) | 1 a 10 |
| Contribuinte sério (`CONTRIBUINTE_SERIO`) | 11 a 50 |
| Hardcore (`HARDCORE`) | 51 a 100 |
| Tryhard (`TRYHARD`) | 101 a 1.000 |
| Get a life (`GET_A_LIFE`) | acima de 1.000 |

### Issues (`ISSUES`)

Issues **qualificadas** nos repositórios elegíveis. Abrir issue não conta por si só: a issue precisa
ter uma label de qualificação (configurável) **ou** ser validada por um mantenedor no painel. Assim
spam de issues não vira selo.

| Nível | Faixa de issues qualificadas |
| --- | --- |
| Beta tester (`BETA_TESTER`) | 1 a 10 |
| Exigente (`EXIGENTE`) | 11 a 20 |
| Esforço máximo (`ESFORCO_MAXIMO`) | 21 a 50 |
| Plus ultra (`PLUS_ULTRA`) | 51 a 100 |
| Genkidama (`GENKIDAMA`) | acima de 100 |

Cada categoria exibe **apenas o nível mais alto alcançado**. Ao subir de nível, o anterior é
revogado e o evento fica no histórico; ao cair (revert, issue invalidada), o nível é recalculado.

## Emblemas

Cada nível tem um **emblema fixo e exclusivo**, ilustrado e versionado no frontend
(`public/seals/<emblema>.png`, 256×256, fundo transparente). O backend só informa o identificador
(`emblem`) do nível em `GET /users/{username}/seals` e `GET /seals/criteria`; ele é função do nível
(`SealType.emblem()`), não é escolhido pela pessoa e só aparece para quem recebeu o selo pelas
regras de contribuição acima (ou por concessão explícita e auditada da administração).

| Nível | Emblema | Arte |
| --- | --- | --- |
| Ajudante | `ajudante` | disquete |
| Contribuinte sério | `contribuinte-serio` | PC retrô |
| Hardcore | `hardcore` | cafeteira de PC |
| Tryhard | `tryhard` | teclado |
| Get a life | `get-a-life` | grama com mouse |
| Beta tester | `beta-tester` | tecla com bug |
| Exigente | `exigente` | lupa com bugs |
| Esforço máximo | `esforco-maximo` | lanterna detectora de bugs |
| Plus ultra | `plus-ultra` | luva de herói |
| Genkidama | `genkidama` | esfera de energia |
### Categorias futuras

`DOCUMENTATION`, `SECURITY`, `COMMUNITY`, `DONATION` e `INFRASTRUCTURE` existem no modelo para
receber critérios próprios quando houver fonte verificável. Elas **não** concedem selo hoje e apoio
financeiro nunca se mistura com reputação técnica.

## Fluxo

1. A pessoa entra na conta do Énóis e vincula o GitHub pelo OAuth oficial
   (`POST /auth/oauth/github/link`; o callback usa `state` de uso único no servidor).
2. Guardamos o **ID numérico imutável** do GitHub e o login (apenas apresentação). Uma conta GitHub
   só pode estar ligada a uma conta Énóis e vice-versa. O GitHub serve **apenas para vincular**:
   não cria conta nem marca identidade verificada.
3. A sincronização roda em três gatilhos: ao concluir o vínculo (só as contribuições daquele
   GitHub, fora da thread da requisição), no botão **Verificar contribuições**
   (`POST /me/github/sync`, com limite por usuário) e na varredura completa
   (`POST /admin/contributors/sync`, ou o worker a cada 10 minutos quando `GITHUB_SYNC_ENABLED=true`
   — o default de produção/QA). Ela lê:
   - PRs integrados (`/repos/{repo}/pulls?state=closed`), com arquivos, revisões e sinais;
   - issues (`/repos/{repo}/issues`), descartando pull requests e guardando as labels.
4. PR integrado nasce como evidência `VALIDATED` (o GitHub é a fonte). Issue nasce `VALIDATED` se
   tiver label qualificadora; caso contrário `PENDING`, aguardando um mantenedor.
5. Depois de cada ciclo, os níveis das categorias são recalculados a partir da contagem. Promoção,
   rebaixamento, concessão e revogação geram evento de auditoria.
6. A tela de segurança mostra o estado do vínculo e permite verificar de novo; a página de selos
   explica quando o vínculo existe mas ainda não há contribuição verificada e mostra "dados já
   confirmados" para quem preencheu o formulário — em nenhum desses estados a tela fica vazia.
7. O perfil público mostra o nível e a contagem verificada; as evidências carregam em **páginas**
   pelo endpoint `GET /users/{username}/seals/{category}/evidence?page=&size=` — o cartão abre com a
   primeira página e “Carregar mais” busca o resto, então não existe mais o teto de 12 por
   categoria. Notas internas, suspeitas, e-mail e detalhes de revisão nunca são públicos.

## Revisão de PRs no painel

- A aba **Pull requests** lista os PRs abertos dos repositórios elegíveis (`GET /admin/github/pulls`,
  moderação) e envia a decisão (`POST /admin/github/pulls/review`): aprovar ou pedir mudanças com
  comentário.
- A revisão sai em nome de quem tem o vínculo GitHub **com escrita** — o botão fica no próprio
  painel e usa `POST /auth/oauth/github/write-link` (escopo `repo`). O token fica cifrado
  (`github_user_tokens`) e nunca é devolvido ao cliente; sem ele, a revisão usa o token de serviço.

## Painel de contribuintes e tag

- Quem tem contribuição verificada (selo ativo) aparece **automaticamente** no painel de
  contribuintes, já aprovado pelo GitHub (`source=GITHUB_VERIFIED`, status `APPROVED`), mesmo antes
  de preencher o formulário.
- Esse formulário é o **mesmo da landing** e serve só para confirmar nome, e-mail, área e mensagem.
  O handle do GitHub vem do vínculo verificado (nunca do corpo), a candidatura nasce aprovada — o
  GitHub é a aprovação — e reenviar atualiza a mesma linha, sem duplicar.
- Candidaturas feitas pela landing continuam na fila manual (nova → análise → aprovar/recusar).
- A tag **Contribuinte** aparece abaixo do nome no perfil. Quem tem selo ativo pode escolher a cor
  em **Aparência** (`PUT /cosmetics/profile/contributor-tag`); a cor fica no perfil
  (`contributorTagColor`, `null` = cor padrão do tema). A opção só aparece para contribuintes, e o
  backend recusa a alteração de quem não tem contribuição verificada.

## Revogação e correção

- Um mantenedor pode **contestar**, **rejeitar** ou **revogar** uma evidência no painel; o recálculo
  seguinte ajusta o nível.
- Revogação **manual** de um selo não é desfeita pela sincronização. Para restaurar, a administração
  precisa conceder de novo explicitamente (`POST /admin/contributors/seals/{userId}/grant`), e a
  ação fica auditada.
- Revogação automática (nível atualizado, PR revertido, issue invalidada) é registrada com ator
  vazio e motivo explícito.

## Modelo de dados

| Tabela | Papel |
| --- | --- |
| `github_identities` | vínculo usuário Énóis ↔ ID imutável do GitHub, login e estado da última sync |
| `github_repositories` | allowlist explícita de repositórios elegíveis |
| `github_contributions` | evidência por `(repository, kind, object_number)` — PR **ou** issue |
| `github_contribution_events` | auditoria de importação, revisão, contestação e revogação |
| `user_seals` | uma linha por `(user_id, seal_type)`, status ACTIVE/REVOKED |
| `user_seal_events` | auditoria de concessão/revogação/rebaixamento |

`github_contributions` guarda `kind`, `object_number`, `labels`, `merged_at`/`closed_at`,
`details_fetched` e `github_updated_at`. O índice único impede contagem dupla quando o mesmo objeto
aparece em páginas, ciclos ou reprocessamentos diferentes. O orçamento de detalhes evita estourar o
rate limit; o que faltou é completado no próximo ciclo.

## Estados da evidência

```
PENDING -> VALIDATED | REJECTED | CONTESTED
VALIDATED -> REVOKED | CONTESTED
CONTESTED -> VALIDATED | REJECTED | REVOKED
REJECTED/REVOKED -> CONTESTED | VALIDATED
```

`REVOKED` preserva o histórico. A revisão exige `AdminGuard` e grava ator, data, estado
anterior/novo e nota.

## API

Público:

- `GET /users/{username}/seals` — níveis ativos + contagem de evidências (sem a lista).
- `GET /users/{username}/seals/{category}/evidence?page=0&size=50` — evidências paginadas
  (até 100 por página), com `total` e `hasMore`.
- `GET /seals/criteria` — categorias, faixas, políticas e repositórios elegíveis.

Conta:

- `GET /me/github` — vínculo, última sincronização e estado.
- `POST /auth/oauth/github/link` — inicia o OAuth de vínculo (exige sessão).
- `DELETE /auth/oauth/github/link` — desvincula (as regras de último método de login continuam).
- `GET /contributors/me` — verificado? já confirmou os dados? (usado pelo convite em `/selos`).
- `POST /contributors/applications/verified` — confirma os dados; nasce `APPROVED` (GitHub aprova).
- `PUT /cosmetics/profile/contributor-tag` — `{ "color": "#rrggbb" }` (ou `null` para a cor padrão);
  exige selo ativo e grava `contributorTagColor` no perfil.

O mesmo vínculo também permite **importar repositórios públicos como projetos e o README do perfil
como bio** — endpoints e regras em [`GITHUB-PROFILE-IMPORT.md`](GITHUB-PROFILE-IMPORT.md).

Admin:

- `GET /admin/contributors/panel` — candidaturas + contribuintes verificados automaticamente.
- `GET /admin/contributors/contributions?status=&kind=&limit=` — fila de evidências.
- `GET /admin/contributors/contributions/{id}` — evidência + auditoria.
- `POST /admin/contributors/contributions/{id}/review` — `{ "status": "...", "note": "..." }`.
- `POST /admin/contributors/seals/{userId}/grant` — `{ "type": "AJUDANTE", "reason": "..." }`.
- `POST /admin/contributors/seals/{userId}/revoke` — `{ "type": "AJUDANTE", "reason": "..." }`.
- `POST /admin/contributors/sync` — `{ "dryRun": true|false, "full": true|false, "repositories": [] }`.
- `POST /admin/contributors/users/{userId}/recalculate` — recalcula uma conta.
- `POST /admin/contributors/recalculate` — recalcula todas as contas vinculadas.
- `GET /admin/contributors/repositories`
- `PATCH /admin/contributors/repositories` — `{ "repository": "...", "enabled": true|false }`.

## Backfill

O backfill é o mesmo endpoint de sync com `full=true`:

1. `POST /admin/contributors/sync` com `{ "dryRun": true, "full": true }` para ver o relatório sem
   persistir.
2. Rode com `dryRun=false`, confira a fila e, se necessário, conteste evidências duvidosas.
3. `POST /admin/contributors/recalculate` para nivelar as contas depois de uma mudança de política.

O processo é seguro para reexecução: o índice único e o upsert preservam a revisão existente.
`max-pages` e `max-details-per-run` limitam chamadas ao GitHub. Um `403/429` interrompe o ciclo com
`rateLimited=true`, marca os vínculos vistos como `ERROR` e o próximo tenta de novo.

## Configuração

| Variável | Uso |
| --- | --- |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | OAuth App do GitHub (callback `.../api/auth/oauth/github/callback`) |
| `GITHUB_REDIRECT_URI` | URL exata cadastrada no OAuth App |
| `GITHUB_CONTRIBUTIONS_TOKEN` | token fine-grained **somente leitura** dos repositórios; opcional, mas recomendado |
| `GITHUB_SYNC_ENABLED` | liga o worker periódico (default `false`; QA/produção ligam) |
| `GITHUB_SYNC_CRON` | cadência do worker (default `0 */10 * * * *`, a cada 10 minutos) |
| `GITHUB_SYNC_MAX_PAGES` | páginas por repositório por ciclo (default 3) |
| `GITHUB_SYNC_MAX_DETAILS` | PRs com detalhes (arquivos/revisões) por ciclo (default 60) |
| `GITHUB_QUALIFIED_ISSUE_LABELS` | labels que qualificam issue automaticamente, separadas por vírgula (default vazio = só revisão humana) |

**Nome dos secrets no GitHub:** o GitHub recusa secrets cujo **nome** comece com `GITHUB_`
(HTTP 422, “Secret names must not start with GITHUB_”). Por isso o deploy usa nomes diferentes do
nome da variável de ambiente entregue ao app:

| Ambiente | Secret | Variável que o app lê |
| --- | --- | --- |
| QA | `QA_GITHUB_CLIENT_ID`, `QA_GITHUB_CLIENT_SECRET` | `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` |
| QA | `QA_GITHUB_CONTRIBUTIONS_TOKEN` | `GITHUB_CONTRIBUTIONS_TOKEN` |
| Produção | `OAUTH_GITHUB_CLIENT_ID`, `OAUTH_GITHUB_CLIENT_SECRET` | `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` |
| Produção | `OAUTH_GITHUB_CONTRIBUTIONS_TOKEN` | `GITHUB_CONTRIBUTIONS_TOKEN` |

O PAT fine-grained só precisa de **Metadata (read)**, **Pull requests (read)** e **Issues (read)**
nos repositórios elegíveis; sem ele a API pública limita em 60 requisições/hora, com ele em 5.000.
`GITHUB_SYNC_ENABLED` continua `false` nos dois ambientes: o primeiro backfill é manual.

## Segurança e resistência à fraude

- ID imutável do GitHub como chave; login apenas exibição.
- Vínculo exige OAuth com `state` de uso único e sessão Énóis; não há vínculo por e-mail declarado.
- Nenhum endpoint público concede selo; revisão e concessão manual são `AdminGuard` + auditoria.
- Importação idempotente por `(repository, kind, object_number)`.
- PR revertido é sinalizado (`REVERT_DETECTED`) e deixa de contar no recálculo.
- Autoria vem do ID da conta GitHub autenticada, não de nome/e-mail de commit.
- O frontend só lê; qualquer tentativa de manipular o nível pelo cliente falha no backend.
- Rate limit, token opcional e falha do GitHub não derrubam login nem o restante do app.

## Limitações

- A detecção de revert é heurística (título/corpo com `Revert` + `#N`), não uma verdade absoluta.
- Sem token, a API pública do GitHub limita bastante o backfill.
- Issues sem label qualificadora dependem de revisão humana.
- Categorias de documentação, segurança, comunidade, apoio e infraestrutura ainda não têm critério.
- O vínculo não altera a verificação de identidade nem o segundo fator da conta.

## Testes

- `SealTypeTests`: todos os limites das duas categorias (0, 1, 10, 11, 20, 21, 50, 51, 100, 101,
  1.000, 1.001) e um emblema único e fixo por nível.
- `GitHubContributionSyncTests`: idempotência de PR e issue, mapeamento por ID, qualificação por
  label, perda de qualificação, dry-run e revert.
- `ContributorSealFlowTests`: promoção/rebaixamento por contagem, qualificação por revisão,
  projeção pública, revogação/concessão manual e critérios.
- `GitHubIdentityServiceTests`: exclusividade do ID e desvínculo.
- `GitHubOAuthLinkTests`: GitHub é vínculo (não login), sessão obrigatória e falha fechada sem config.
- `AutomatedWritePathsTests`: as rotas públicas de escrita são conhecidas e o restante continua fechado.
