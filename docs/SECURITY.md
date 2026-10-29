# Énóis Security & Trust — auditoria e plano

Plano vivo do sistema antifraude. **Estado: auditoria inicial feita; implementação das fases ainda
não começou** (a rodada de 2026-10-18 foi interrompida no limite de contexto). Ao retomar, leia este
arquivo primeiro e siga a ordem das fases.

## 1. O que JÁ existe (não recriar)

| Peça | Onde | Observação |
| --- | --- | --- |
| Denúncia com alvo e categoria | `moderation/` (V38+) | alvos: POST, COMMENT, USER, JOB (falta GAME) |
| Triagem por IA da denúncia | `moderation/ai/ModerationAiService` + `AiVerdictApplicationService` + `ReportAiTriageScheduler` | provedor por env (`MODERATION_LLM_*`), desligável |
| Penalidades e recursos | `moderation/` + `shared/sanctions/SanctionGateReader` | restrição com confiança ≥ 0,7 e sem ambiguidade; ban só com recurso negado por humano |
| Barreira de mídia | `moderation/` (V199) — ver [`MODERACAO-MIDIA.md`](MODERACAO-MIDIA.md) | imagem publicada é classificada mesmo sem denúncia; ação automática só acima do piso de confiança |
| Auditoria | `shared/audit/AuditLogPersistenceAdapter` | usado nas decisões administrativas |
| Idempotência | `idempotency/IdempotencyApplicationService` | chave + hash em toda escrita sensível |
| Faísca | `coin/` (V69) | `coin_accounts` com `version` (concorrência otimista) + `coin_entries` (razão com `Kind`) |
| Rate limiting | resilience4j, `instances.auth` (20/periodo) e `instances.search` | **só nesses dois** — não há limiter global por IP |
| Validação de URL externa | `games/application/GameUrlGuard` (2026-10-18) | https, sem credencial, host público (loopback/privado/link-local/IPv6 local) |

## 0.1 Fase B — o que já estava de pé e o que foi provado (2026-10-18)

A triagem por IA **já tinha** as defesas certas de autoridade: o conteúdo denunciado entra na
mensagem de usuário entre marcadores e rotulado como dado não confiável; o prompt de sistema diz que
o modelo não bane nem executa nada; o veredito gravado é **revalidado na hora de aplicar** ("o JSON
gravado não pode valer mais do que a política do dia"); e a única ação automática possível é uma
restrição com recurso — banimento só com um humano negando o recurso.

O que faltava era **prova**. `AiTriageGuardTests` (3/3, sem provedor e sem contexto de Spring):
um payload que tenta fechar o marcador e dar ordens fica dentro do bloco de dado e não aparece no
prompt de sistema; a penalidade automática exige confiança acima do piso e ausência de ambiguidade
(confiança baixa, `ambiguo: true`, campo ausente e valor não numérico **não** passam); alvo sem
conteúdo vira "(conteudo indisponivel)" e vai para revisão humana. Os construtores de prompt viraram
`static` (texto puro) para poderem ser testados sem subir a aplicação.

Ainda **não** feito na Fase B: limite de chamadas/custo/concorrência por denúncia e métrica de
latência/custo da IA (Fase F).

## 2. Lacunas conhecidas (do que a auditoria desta sessão já viu)

1. **Rate limiting global por IP** — cadastro, login, recuperação e envio de código têm `auth`, mas
   nada limita por IP entre contas; criar conta em série e credential stuffing ficam baratos.
2. **Prompt injection na triagem por IA** — o conteúdo denunciado entra no prompt; falta separar
   instrução de dado e travar as ferramentas que a IA pode chamar (a IA não deve banir nem mexer em
   Faísca a partir de texto de terceiro).
3. **Faísca sem reconciliação** — há razão e trava otimista, mas não há verificação periódica
   saldo × histórico nem alerta de divergência.
4. **Alvo GAME ausente** em `TargetType` (o módulo Games acabou de entrar; denúncia de jogo é pedida
   na missão).
5. **Sem caso/incidente** — cada denúncia é isolada; falta agrupar, relacionar e acompanhar.
6. **Sem Risk/Policy Engine** — as decisões são por regra pontual (reputação, verificação), não por
   avaliação de risco versionada.
7. **Painel** — a fila de moderação existe (`/moderacao`), mas sem prioridade, linha do tempo,
   relações entre casos nem permissões por função.
8. **Enumeração de contas no cadastro** — o cadastro precisa dizer que o e-mail já existe (senão a
   pessoa não entende por que não entra); a defesa correta é responder igual e avisar o dono por
   e-mail, o que exige template novo. Fica registrado, não meio-feito.
9. **Reincidência** — não existe (nem existia) banimento em cadeia por IP/dispositivo; a punição só
   olha violação confirmada da conta. Nada a corrigir aqui, e o critério está escrito para não
   aparecer depois.

## 3. Fases propostas (ordem por risco × esforço)

1. **Fase A — contenção determinística**: limiter por IP nos endpoints de identidade, teto de
   criação de conta por dispositivo/IP, trava de reincidência por conta confirmada, alvo GAME nas
   denúncias. *Tudo sem IA.*
2. **Fase B — IA com limites**: separação instrução × conteúdo, validação de saída estruturada,
   lista fechada de ferramentas com autorização no backend, limite de chamadas/custo, e o
   comportamento degradado quando o provedor cai.
3. **Fase C — caso e auditoria**: `security_cases` (id, categoria, severidade, envolvidos,
   evidências, estado, responsável), linha do tempo, ligação com denúncias e decisões; auditoria
   imutável dos eventos críticos.
4. **Fase D — integridade da Faísca**: reconciliação periódica (saldo × razão), alerta de
   divergência, lançamento compensatório auditável, testes concorrentes de compra/resgate.
5. **Fase E — painel**: fila por prioridade, evidências, relações, recursos, permissões por função
   (investigação ≠ decisão ≠ financeiro), confirmação + justificativa nas ações críticas.
6. **Fase F — métricas**: incidentes por categoria, tempo de detecção/contensão, reincidência, taxa
   de reversão, falsos positivos, custo/latência da IA, divergências financeiras.

## 4. Regras que valem para toda fase

- Controle crítico **no backend**, no momento da execução — nunca só na tela.
- IA **recomenda**; quem pune é humano com evidência registrada (exceção: contenção emergencial
  delimitada).
- Testes obrigatórios por fase, com dados sintéticos; operações financeiras testadas em concorrência.
- Nada de dado pessoal em log; nada de segredo no código; migrations versionadas.
- Não confundir erro do usuário com fraude deliberada, e não confundir tolerância zero com punição
  arbitrária.
