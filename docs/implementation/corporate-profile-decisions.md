# PERFIL PROFISSIONAL — DECISÕES E RISCOS

> Log de decisões de design e riscos/impedimentos. Cada decisão tem data e racional.

## Decisões

- **2026-10-30: Social and professional interfaces.** Applying to a job and proposing for a freelance
  require the ACTIVE professional identity, resolved from the session; professional conversations
  (`JOB`, `FREELANCE`, `SERVICE`) never share a thread with the social DM; LinkedIn auto-share only
  takes professional posts. Account values stay account-level (decision 0), but the public
  professional page does not display reputation, followers or connections. Details in
  `docs/SOCIAL_PROFESSIONAL_CONTEXTS.md`.

0. **2026-10-20 — Valores de conta compartilhados (diretriz da dona).** Faíscas, credibilidade,
   reputação, seguidores, verificação e segundo fator são do nível da CONTA: as duas
   identidades os compartilham, sem duplicação nem separação de carteira. O que muda por
   identidade é apenas a apresentação e a autoria/destino de publicações. Impacto prático:
   interações em posts BUSINESS afetam a mesma reputação/carteira; o perfil profissional não
   tem saldo nem métricas próprios.

1. **2026-10-20 — Perfil profissional em tabela própria (`corporate_profiles`), não em
   colunas na tabela do perfil social.** Racional: o perfil social já tem ~40 colunas e um
   namespace de username; misturar os dois estados numa linha só criaria condicionais em todo
   o código e risco de vazamento entre identidades. Tabela 1:1 opcional, estados próprios.
2. **2026-10-20 — Autoria no `posts` como `author_identity` (SOCIAL|CORPORATE) e não como
   `corporate_profile_id` separado.** Racional: a invariante importante é binária por
   publicação; o perfil corporativo é resolvido a partir do dono da publicação. Evita
   publicações "sem perfil" quando o perfil é desativado depois.
3. **2026-10-20 — Username do perfil profissional em namespace próprio** (não colide com o
   do social; unicidade garantida por índice único na própria tabela). Formato decidido na
   Fase 3 e implementado: `^[a-z0-9](?:[a-z0-9._-]{1,28})[a-z0-9]$` (pontos, hífens e
   underline, 3–30 caracteres).
4. **2026-10-20 — Tipo de publicação BUSINESS em vez de novo sistema de posts.** Racional:
   a infraestrutura existente (agendamento, anexos, moderação, outbox) é reaproveitada; a
   separação de feeds é por consulta, conforme tabela do plan.
5. **2026-10-20 — Agendamento real**: automação de 40 minutos criada de fato (CronCreate)
   e apontada para este diretório de documentos. Sem mecanismo real, nunca fingir.
6. **2026-10-20 — Rota pública do perfil profissional: `/profissional/{username}`.** No
   roteamento real (TanStack Start) o arquivo é `profissional_.$username.tsx`: o `_` faz a
   rota ser IRMÃ de `/profissional` (o editor), em vez de filha — filha exigiria `<Outlet/>`
   no editor e abriria a página pública dentro de um layout autenticado. O nome leva à
   identidade que assinou: `authorIdentity = CORPORATE` linka para cá; SOCIAL continua em
   `/perfil/{username}`.
7. **2026-10-20 — A apresentação do post vem da identidade de autoria.** `PostView` carrega
   `authorIdentity` e o enriquecimento resolve nome/@/avatar do perfil profissional para
   posts CORPORATE (uma consulta em lote por página), do social para os SOCIAL. Emblemas
   continuam sendo da conta (diretriz 0). Sem isso, um post BUSINESS apareceria com o nome e
   a foto pessoais.

## Riscos e impedimentos

- **Árvore compartilhada**: outras sessões commitam em `rede`. Mitigação: commit pequeno por
  tarefa, `git add` seletivo, migrations numeradas pelo maior existente no instante.
- **Push proibido até o gate final**: o branch local pode divergir bastante; no gate,
  revisar antes do push (Fase 16).
- **Caches e busca**: possíveis pontos de vazamento de BUSINESS no contexto social; Fase 8
  audita endpoint por endpoint antes de considerar concluída.

## Perguntas abertas para a dona

1. ~~Formato do username profissional e rota pública~~ — resolvido na Fase 10 (decisões 3 e 6);
   fica só a confirmação de gosto, não bloqueia nada.
2. Visibilidade do feed corporativo: público para todos ou apenas para seguidores? Padrão
   atual do plano: público (mesma visibilidade do perfil).
