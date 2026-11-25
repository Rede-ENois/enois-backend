# Transparência da sanção e autenticação limitada

Contrato da issue #705. As rotas abaixo são as do controller; o gateway público acrescenta `/api`.
A política de aplicação, conversão, duração e revisão humana não muda.

## Consulta do titular

`GET /me/sanctions`, autenticado pelo cookie HttpOnly `enois_session` (ou pelo cabeçalho
`X-Session-Token` para clientes não navegador), retorna:

```json
{
  "serverNow": "2026-11-14T12:00:00Z",
  "sanctions": [{
    "id": "sanc_exemplo",
    "userId": "user_exemplo",
    "reportId": "admin:decisao_exemplo",
    "type": "SUSPENDED",
    "reason": "Conduta repetida contrária às diretrizes.",
    "status": "ACTIVE",
    "createdAt": "2026-11-14T11:00:00Z",
    "removedAt": null,
    "dueAt": "2026-11-21T11:00:00Z",
    "deadline": {"kind": "APPEAL", "at": "2026-11-21T11:00:00Z"},
    "evidence": {
      "kind": "BEHAVIOR",
      "text": "Decisao sobre a conduta da conta, sem publicacao unica associada."
    },
    "canAppeal": true
  }],
  "appeals": []
}
```

Os campos anteriores de sanções e recursos permanecem. Listas continuam limitadas aos 20 registros
mais recentes do próprio titular; parâmetros com identidade de outra pessoa não mudam esse titular.
`reason` mantém o motivo persistido. O campo legado `dueAt` mantém a compatibilidade com a QA:
somente suspensão ativa retorna `createdAt + graça configurada`; todos os demais casos retornam
`null`, inclusive suspensão removida. É prazo de recurso, não término efetivo. A apresentação nova
usa `deadline.kind/at`, sem inferir duração no cliente. Não existem campos `endAt` ou
`appealDeadlineAt` nesta versão da API. As respostas administrativas anteriores conservam `dueAt`.
`appeals` mantém `id`, `sanctionId`, `userId`, `message`, `status`, `createdAt`, `reviewedBy` e
`reviewedAt`; os estados são `PENDING`, `APPROVED` e `DENIED`.

### Significado do prazo

- `SUSPENDED`: `APPEAL`, com `at = createdAt + graça configurada`. A configuração é
  `rede.moderation.suspension.grace-days`, padrão 7, limitada entre 1 e 365 dias, exatamente como o
  worker existente. O contador **não** é uma promessa de liberação.
- `RESTRICTION`, `BAN` e `PERMABAN`: `NONE`, com `at: null`. Não existe término efetivo persistido.
- `END` é reservado pelo contrato para término efetivo, mas **nenhum tipo atual o emite**.
- Datas são instantes ISO-8601 UTC, não horas locais. A consulta não executa o worker nem modifica
  a sanção. Uma suspensão removida preserva seu prazo histórico e tem `canAppeal: false`.
- O worker atual converte somente registros criados **antes** do corte. No limite exato o registro
  ainda não é selecionado; após o corte, sem recurso pendente, vira `BAN`, não conta livre. Com
  recurso pendente, continua `SUSPENDED` aguardando revisão humana.
- O domínio atual não recusa novo recurso só porque a data passou: enquanto a suspensão ainda está
  ativa e não convertida, continua elegível. A consulta reflete isso; não cria regra temporal nova.

### Evidência segura

`evidence` é sempre `{kind, text}`:

- `TEXT`: trecho do próprio alvo denunciado, por leitores existentes ou pelo snapshot arquivado
  **da mesma sanção, titular, tipo e conteúdo**. Não retorna o restante do arquivo da conta.
  O trecho é texto simples, sem tags, scripts ou controles; URLs HTTP(S)/www são omitidas.
  O limite é de 500 pontos de código.
  Deve ser renderizado como texto, nunca como HTML.
- `MEDIA`: descrição estática de uma associação persistida de mídia do alvo. Não baixa nem retorna
  binário, captura, URL, identificador da mídia ou chave de armazenamento.
  Inclui fotos de Colagem e comentários com foto, inclusive quando removidos logicamente;
  marcadores internos da triagem não são apresentados como texto escrito pelo titular.
- `BEHAVIOR`: decisões de conduta identificadas por suas referências existentes (administração,
  votação, antispam, honeypot ou contestação). Não escolhe um post arbitrário do arquivo da conta.
- `UNAVAILABLE`, `text: null`: associação ausente, registro legado desconhecido, conteúdo apagado
  sem arquivo correspondente ou snapshot textual inválido. Não inventa evidência para o passado.

Não são fontes de evidência o relato de quem denunciou, participantes da chamada, identidade do
denunciante nem campos do classificador. Falha de banco/serviço continua sendo erro HTTP, não lista
vazia de penalidades. Nenhuma nova cópia de conteúdo bruto nem mudança de retenção foi introduzida.

## Entrada depois de revogar a sessão normal

A suspensão continua revogando as sessões normais. Uma credencial revogada recebe
`401 UNAUTHENTICATED`, sem revelar a situação da conta. O cliente deve pedir autenticação novamente,
não interpretar um 401 como suspensão confirmada.

Depois da senha correta, identidade verificada e todos os fatores locais exigidos, o login responde
`200` com o formato existente acrescido de `accessScope`:

```json
{
  "expiresAt": "2026-11-14T13:00:00Z",
  "refreshExpiresAt": null,
  "user": {
    "id": "user_exemplo", "username": "pessoa-exemplo", "name": "Pessoa Exemplo",
    "headline": null, "email": "pessoa@example.invalid", "emailVerified": true,
    "twoFactorEnabled": false, "identityVerified": true, "hasPassword": true,
    "onboardingCompleted": false
  },
  "accessScope": "SANCTIONS_ONLY"
}
```

- Conta sem suspensão recebe `accessScope: "FULL"` e os prazos normais de refresh.
- `SANCTIONS_ONLY` usa o mesmo transporte HttpOnly/Secure/SameSite já existente. Não há token em JSON,
  URL, localStorage ou bearer novo. O cookie de refresh é apagado; sessão limitada não é renovável.
- O escopo e a validade são persistidos. A validade do acesso conhecida por versões antigas nasce
  vencida em uma data fixa, independente da emissão, para não virar acesso normal na implantação
  mista. Sem token de refresh, sua data permanece apenas para preservar a retenção nos pods antigos.
- Validade do acesso: a configuração existente `rede.auth.access-token-ttl-minutes` (padrão 60).
  A mesma revogação e retenção de sessões existentes se aplicam.
- Somente `GET /me/sanctions`, `POST /me/sanctions/appeals` e logout usam essa sessão. As rotas de
  autenticação necessárias para entrar novamente continuam acessíveis, mas não concedem privilégios.
- Áreas privadas, publicação, painel, alteração de senha/identidade e gestão de fatores não aceitam
  a sessão limitada. Respondem `403 ACCOUNT_SUSPENDED` enquanto há suspensão ativa, ou
  `403 SESSION_RESTRICTED` quando a decisão já mudou. O escopo nunca é promovido automaticamente.
- `GET /auth/me` também responde esse 403 para a sessão limitada. Para sessão normal, mantém seu
  `AuthUserView` anterior, sem envelope novo.
- Os mesmos caminhos são aceitos pelo alias `/v1`. A leitura de escopo respeita o orçamento de
  consultas de sessão existente, após os limitadores públicos. Esgotamento desse orçamento responde
  `429 SESSION_LOOKUP_LIMIT` com `Retry-After`, sem consultar novamente a persistência.
- Depois de recurso aceito, a consulta limitada mostra `REMOVED`/`APPROVED`. A pessoa deve entrar de
  novo para obter `FULL`. A consulta não restaura conteúdo anteriormente purgado.
- A consulta acompanha a decisão vigente: `BAN` e `PERMABAN` não autenticam a sessão limitada.
  Recusas em superfícies administrativas mantêm a trilha e os contadores por conta da QA, sem
  conceder acesso normal.
- `BAN`/`PERMABAN` continuam sem nova sessão e sem recurso: `403 ACCOUNT_BANNED` somente depois da
  confirmação dos fatores. Senha errada continua `401 INVALID_CREDENTIALS`; identidade não verificada
  continua `403 EMAIL_NOT_VERIFIED`.

### Segundo fator por senha

O contrato anterior permanece: `POST /auth/login` responde `202` com
`{twoFactorRequired:true, challengeId, method, expiresAt}`, sem sessão, usuário ou escopo.
`POST /auth/2fa/verify` recebe `{challengeId, code}` e só depois do código correto emite o
`AuthResponse` acima. Código incorreto: `400 INVALID_TWO_FACTOR_CODE`; desafio usado:
`409 TWO_FACTOR_CHALLENGE_ALREADY_USED`. Desafio não autentica a consulta de sanções.

### OAuth e segundo fator local

Os validadores de provedor, state/nonce, vínculo e confirmação do e-mail continuam existentes.
Fator local habilitado (TOTP/e-mail) também é exigido depois da prova do provedor:

1. Sem fator local pendente, callback de navegador retorna `302` para o callback do frontend com
   `?status=ok&accessScope=SANCTIONS_ONLY` se suspenso. Conta normal mantém `?status=ok`.
2. Com fator local pendente, retorna `302` com **somente** `?status=two_factor_required`, sem divulgar
   suspensão. Limpa cookies de sessão e refresh e guarda o desafio no cookie HttpOnly
   `enois_2fa_challenge` (SameSite=Lax, Secure em HTTPS, validade do desafio existente de 10 minutos).
3. O frontend lê `GET /auth/2fa/challenge` com cookies. Resposta `200`:
   `{challengeId, method:"TOTP"|"EMAIL", expiresAt}`. Não contém identidade ou estado da conta.
   Cookie ausente, desafio vencido/consumido ou tentativas esgotadas: `401 TWO_FACTOR_CHALLENGE_NOT_FOUND`.
   Essa resposta usa `Cache-Control: no-store`.
4. Conclui pelo mesmo `POST /auth/2fa/verify`. O sucesso apaga o cookie de desafio e emite o escopo
   correspondente à decisão atual. Desafio sozinho não tem acesso à consulta nem recurso.

Em `POST /auth/oauth/{provider}/token` (e callback sem redirect configurado), uma etapa pendente
retorna `200` com `{expiresAt:null, refreshExpiresAt:null, user:null, accessScope:null,
 twoFactor:{challengeId, method, expiresAt}}`, sem credencial de sessão. Depois do fator, o contrato
é o mesmo do login. Confirmação de e-mail de uma conta antiga também não contorna fator local.

## Recurso e tratamento pelo frontend

`POST /me/sanctions/appeals` recebe `{message}` (mínimo 10 caracteres) e retorna
`200 {status:"PENDING", id}`. Em cookie, exige `X-CSRF-Protection: 1`, assim como as outras escritas;
falta dele: `403 CSRF_REQUIRED`. Repetição pendente: `409 APPEAL_ALREADY_PENDING`.
Ausência de sanção: `404 NO_ACTIVE_SANCTION`; banimento definitivo: `409 SANCTION_IS_BAN`.
O titular vem da sessão, nunca de um campo do pedido. O acompanhamento usa o mesmo GET.

O consumidor deve encaminhar `SANCTIONS_ONLY`/`ACCOUNT_SUSPENDED` para `/conta-suspensa`, sem montar
áreas privadas normais. Após 401, entrar novamente; após 403 de CSRF ou falha de rede, não presumir
perda de sessão ou ausência de penalidades. Uma falha na consulta deve oferecer erro/repetição.

Migração aditiva: `V368__auth_session_access_scope.sql`, sem alteração de sanções ou evidências
existentes. Nenhuma alteração em classificadores, bot, agressividade, fila humana, conversão,
worker de vencimento, liberação automática ou elegibilidade de BAN/PERMABAN.

A migração própria foi renumerada de V296, depois V341, V344, V345, V366 e V368 na integração:
a QA publicou `V296__eroleplay_chat.sql`, `V344__network_node_observed_content_usage.sql`,
`V345__network_node_traffic.sql`, `V366__network_storage_operation_metrics.sql` e
`V367__network_storage_object_metadata.sql` depois das renomeações anteriores. O SQL da sessão não
mudou; nenhuma migração da QA foi editada. A PR não deve ser aplicada com as numerações antigas.

---

# Sanções: decisão administrativa

Escopo da issue #729. As rotas abaixo são as do controller; o gateway público acrescenta `/api`.

## Decisão administrativa direta sobre denúncia

As rotas `POST /admin/reports/{reportId}/penalize`, `/warn` e `/ban` exigem sessão de
administrador validada no servidor. São o caminho direto para uma decisão administrativa,
inclusive emergencial, separado da votação dos moderadores. Não exigem votos e não alteram o
quórum configurado nem concedem esse acesso a moderadores sem privilégio administrativo.

O sucesso retorna `200 application/json` com o objeto `ReportView`, não texto de `record.toString()`:
`id`, `reporterId`, `targetType`, `targetId`, `reason`, `description`, `status`, `createdAt`,
`aiTriage` e `aiTriagedAt`. Datas são ISO-8601; os dois campos de triagem podem ser nulos.
O consumidor `reportsAdminApi` e o parser JSON estrito do cliente aceitam esse objeto diretamente;
não há fallback para texto nem mudança no corpo de entrada. O DTO administrativo não é a consulta
do titular e não deve ser exposto em telas públicas.

| Ação | Efeito existente preservado | Status da denúncia |
| --- | --- | --- |
| `penalize` | Remove o conteúdo denunciado quando suportado pelo módulo e aplica `RESTRICTION` ao autor identificado, se ainda não houver sanção ativa | `AUTO_PENALIZED` |
| `warn` | Avisa o autor, mantém o conteúdo e libera a quarentena de post, quando houver | `APPROVED` |
| `ban` | Remove o conteúdo denunciado, cria `BAN`, revoga sessões e executa a limpeza de conteúdo da conta | `APPROVED` |

`penalize` é restrição, não suspensão temporária nem banimento: impede publicar/comentar,
não revoga sessões por esta ação e não tem data de liberação automática. Recurso e sua revisão
seguem as regras existentes. Uma sanção já ativa é reutilizada sem mudança de tipo, prazo ou
referência de origem; o caso revisado e a notificação continuam registrados.

A penalização humana usa a mesma regra de restrição da triagem, mas grava `reviewedBy` e
`reviewedAt` na denúncia com a pessoa autenticada e o relógio da aplicação. A remoção registra
essa pessoa e o prefixo `HUMAN`, e a notificação descreve revisão humana, não análise automática.
O aprendizado continua marcado `HUMAN`. A votação que alcança quórum chama esse mesmo caso de uso,
com o revisor já fornecido pelo fluxo existente; os critérios de votação não mudam.
Por compatibilidade, o nome legado `AUTO_PENALIZED` permanece também na decisão humana:
**esse status sozinho não prova automação**. Os campos de revisão ficam persistidos, sem ampliação
do `ReportView`. Registros antigos não recebem autoria retroativa inventada.

### Guardas e limites desta integração

- Sem privilégio administrativo, as três rotas recusam a ação com `403`, sem resolver a denúncia
  nem aplicar efeitos. Denúncia inexistente retorna `404 REPORT_NOT_FOUND`.
- Todas exigem denúncia `PENDING`. Repetição sequencial retorna `409 ALREADY_REVIEWED`, sem
  repetir sanção, remoção ou notificação; não é replay de sucesso por `Idempotency-Key`.
- `penalize` e `ban` recusam `COMPANY` com `400 COMPANY_TARGET`: página empresarial tem ação própria,
  sem punição automática de seu dono. Penalização sem autor identificável retorna
  `409 CANNOT_PENALIZE`; banimento sem autor retorna `409 NO_AUTHOR`.
- A remoção compartilhada atua em `POST` e `COMMENT`. Outros alvos dependem dos leitores de autor
  e das capacidades já existentes dos módulos; esta integração não amplia tipos de alvo.
- Conteúdo anônimo (`DESABAFO_POST`, `DESABAFO_COMMENT`, `CONFESSIONARIO_POST`,
  `CONFESSIONARIO_COMMENT`) também tem autor identificável para a moderação. O anonimato é do
  contrato publico, nao um apagamento do vinculo: `penalize` e `ban` recaem sobre a conta que
  publicou, e a decisao de um administrador nao depende de quorum, como em qualquer outro alvo.

### A notificacao de punicao carrega a prova

Quem e punido recebe uma notificacao `SANCAO` com o que vale (restricao ou banimento), a
justificativa e o trecho do conteudo denunciado em `preview`, abaixo do corpo. O conteudo sai do ar
na mesma decisao, entao a notificacao e o registro que sobra para a pessoa entender o que quebrou
e recorrer. O trecho e lido antes da remocao; a justificativa da denuncia vem em portugues, e a
punicao direta do painel usa o motivo escrito pelo administrador.
- **Concorrência: limitação preexistente, identificada por inspeção estática.** A proteção contra
  repetição sequencial não comprova execução única em paralelo. Esta integração não modifica a
  estratégia de concorrência existente e não inclui validação concorrente; essa garantia exige
  avaliação específica antes de ser assumida pelo consumidor.
