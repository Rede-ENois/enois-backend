# Páginas empresariais no énóis — arquitetura, modelo e plano

Princípio: **empresa não é usuário com outro nome.** Página com identidade própria, equipe com
papeis, caixa de entrada própria, vagas, publicações identificadas, moderação reforçada. Reuso
obrigatório: posts, comentários, vagas, conversas e denúncias já existem — a empresa entra como
**origem/atribuição** nesses módulos, não como sistemas paralelos.

## Fase 0 — Auditoria do que existe (feito)
- Sem domínio de empresas hoje: perfis são `UserProfile`; vagas têm `company` como TEXTO livre; não
  há CNPJ, equipe nem caixa empresarial.
- Reutilizável: posts/comentários (feed), vagas (jobs), conversas (messaging), denúncias
  (moderation/reports com `TargetType`), reputação (ReputationService), cache Caffeine, outbox,
  EmailService, AdminGuard (allowlist de e-mails).
- Lacunas: identidade empresarial, papéis/equipe, caixa separada, agendamento, métricas.

## Fase 1 — Fundação (modelo + papéis + página pública)
Migrações (V+n):
1. `companies` (id, slug unique, name, legal_name?, cnpj?, description, sector, size, location,
   website, logo, cover, founded_year?, owner_user_id not null, status
   PENDING_VERIFICATION/ACTIVE/RESTRICTED/SUSPENDED/CLOSED, created_at, updated_at).
   - CNPJ via **BrasilAPI** (endpoint público e gratuito) apenas para validar formato/existência —
     **não** prova representação; a representação é atestada pelo responsável e pode exigir revisão.
2. `company_memberships` (id, company_id, user_id, role OWNER|ADMIN|EDITOR|RECRUITER|SUPPORT|ANALYST,
   invited_by?, invited_at, accepted_at?, revoked_at?, unique(company_id, user_id)).
   - OWNER único por página (transferência segue o padrão seguro já usado em mentoria/posição:
     procedimento explícito, sem apagar histórico).
3. `company_invitations` (id, company_id, invitee_user_id, role, token unique, invited_by,
   invited_at, accepted_at?, revoked_at?).
4. `jobs.company_page_id` (nullable) — vinculação **autorizada** por quem tem papel na página;
   mantém o histórico de denúncias/alterações mesmo após encerramento.
5. `posts.company_page_id` (nullable) — publicações em nome da página com o **autor humano interno
   registrado** (audit).

Backend (hexagonal, como o resto):
- `companies` module: ports in (CreateCompanyPage, ManageMembership, TransferOwnership...),
  ports out (CompanyRepository), policies (elegibilidade: conta ativa + identidade verificada +
  credibilidade ≥ limiar + sem suspensão ativa — **reusa ReputationService/identidade**).
- Autorização **no backend** por papel, por operação (nunca pelo front).
- Endpoints: `POST /companies`, `GET /companies/{slug}` (público), `PATCH`, `POST /companies/{id}/
  invitations`, `POST /invitations/{token}/accept`, `DELETE memberships/{userId}`, `GET /companies/
  {id}/members`, `POST /companies/{id}/transfer-ownership`, vagas: `POST /companies/{id}/jobs`,
  `GET /companies/{id}/jobs`.
- Auditoria: ações administrativas em `CompanyAuditLog` (ou audit_log existente) — quem, o quê, quando.

Frontend:
- Rota pública `/empresa/{slug}` (identidade visual própria: logo/cover/selo/descrição/vagas/contato).
- Painel `/empresa/{slug}/admin` com as seções (visão geral, página, publicações, vagas, equipe,
  mensagens, métricas, configurações) — **não mostra nada para quem não tem o papel**.

## Fase 1.5 — Marketplace de microprojetos (pedido direto da Mel, 2026-10-17)

Dentro da página da empresa: **microprojetos** — a empresa publica um problema REAL e contrata
profissionais para **pilotos pagos de escopo limitado**, com entregáveis e critérios de aceite
definidos ANTES de começar.

Modelo (V56, mesmo commit da fundação):
- `micro_projects`: id, company_id, created_by (usuário humano), title, problem, scope,
  deliverables (linhas), acceptance_criteria (linhas), budget (texto livre — o pagamento
  acontece FORA da plataforma; o registro é o contrato de escopo), deadline (data, opcional),
  status OPEN | IN_PROGRESS | COMPLETED | CANCELLED, selected_proposal_id?, created_at,
  updated_at.
- `micro_project_proposals`: id, project_id, user_id (uma proposta por pessoa), message,
  status SENT | SELECTED | DECLINED, proposed_at, decided_at?. Uma proposta selecionada
  move o projeto para IN_PROGRESS e as demais viram DECLINED.

Fluxo (com notificações em cada virada):
1. Empresa (papel EDITOR+) publica o microprojeto → status OPEN, público na página.
2. Profissional se candidata com mensagem → empresa é avisada.
3. Empresa SELECIONA um candidato → projeto IN_PROGRESS; profissional avisado; demais recusados.
4. Profissional entrega (mensagem/link pela conversa da página — Fase 2; MVP: aceite manual).
5. Empresa marca COMPLETED (critérios de aceite conferidos).

Regras: quem publica precisa de papel na página; um profissional não candidata a projeto da
própria empresa; projeto CANCELLED some da vitrine mas mantém histórico. Aceite: projeto
aberto aparece na página e no diretório; fluxo candidato→selecionado→entrega→aceite funciona
com notificações.

## Fase 2 — Caixa de mensagens empresarial (independente da pessoal)
- `conversations.company_page_id` (nullable) — conversa empresarial é vinculada à página; acesso
  depende do papel na página (backend). Nunca aparece na caixa pessoal.
- UI própria: lista com não-lidas/previa/filtros, atribuição entre RECRUITER/SUPPORT, etiquetas,
  notas internas (jamais expostas ao cliente), respostas rápidas (CRUD), indicadores
  (recebidas/pendentes/resolvidas/tempo da 1ª resposta — cálculo declarado, sem números fictícios).
- Contato pelo botão da página pública cria conversa empresarial (não DM pessoal do dono).

## Fase 3 — Publicações, feed e agendamento
- Publicar em nome da página: `posts.company_page_id` preenchido; **autor humano registrado
  internamente**; no feed, identificação visual de página empresarial.
- Agendamento nativo (permitido pela política): `scheduled_publications` (estado RASCUNHO/
  AGUARDANDO_APROVACAO/AGENDADA/PUBLICADA/CANCELADA/BLOQUEADA, histórico, idempotência por
  (pagina, publicar_as)), worker de publicação, cancelamento, bloqueio quando a página for
  suspensa. **Proibido**: automação de posts por IA/bots/integrações (regra já vigente).

## Fase 4 — Vagas da página + recrutamento
- Vinculação autorizada: vaga criada com `company_page_id` por RECRUITER/ADMIN.
- Área de recrutamento: vagas ativas/encerradas/pendentes, candidaturas (módulo existente) visíveis
  só a RECRUITER/SUPPORT/ADMIN da página.

## Fase 5 — Métricas
- Painel: visualizações da página, seguidores, desempenho de publicações e vagas — só métricas com
  dado real (ex.: contadores de visualização com mecanismo já existente); **sem rastrear pessoas**.

## Moderação reforçada (transversal)

**IMPLEMENTADO (2026-10-31):** denúncia com alvo `COMPANY` (mesma `POST /api/reports`). O painel de
revisão mostra o conteúdo da página e o dono como responsável. A ação própria é
`POST /api/admin/reports/{reportId}/remove-company`: fecha a página (`CompanyStatus.CLOSED`) — ela sai
do diretório (`GET /api/empresas`), responde `404 COMPANY_NOT_FOUND` na leitura pública e recusa
operações com `409 COMPANY_CLOSED` — e a conta do dono fica intacta, com notificação do caminho de
recurso. `ban`/`penalize` recusam alvo `COMPANY` com `400 COMPANY_TARGET`: a página se remove, não se
bane o dono por causa dela.

## Pendências/decisões suas
- Verificação de representação: CNPJ valida existência, **não** representação — aceitamos
  autodeclaração com responsabilidade + revisão? ou exige documento? (decidir antes da Fase 1).
- Orçamento de crédito mínimo para criar página (reusa credibilidade; limiar a definir).

## Critérios de aceite da Fase 1
1. Empresa criada com estado PENDING_VERIFICATION → ACTIVE.
2. Só quem tem papel na página acessa o painel (verificado no backend).
3. OWNER convida/aceita/revoga; papéis com permissões distintas verificadas por testes.
4. Página pública `/empresa/{slug}` com identidade própria.
5. Auditoria de ações administrativas.
