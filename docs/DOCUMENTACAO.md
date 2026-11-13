# Inventário de documentação

> **Este arquivo é o painel do esforço de documentação.** Cada rodada do agendamento (a cada 20
> minutos) marca o que foi documentado e registra o lote no log abaixo. Termina quando todos os
> itens estão ✅. Regras deste esforço: documentar o que **existe** (implementado e verificado, como
> manda o [`AGENTS.md`](../AGENTS.md)); nada de segredo ou tática de defesa em texto público
> (descrever comportamento visível, nunca o mecanismo interno de proteção); commit por rodada.

## Como usar

- ⬜ pendente · 🔄 em andamento nesta rodada · ✅ documentado
- Novo módulo/feature que surgir entra nesta lista no mesmo commit do código.

## Backend — módulos (doc em [`MODULES.md`](MODULES.md))

| # | Módulo | Estado |
|---|--------|--------|
| 1 | RedeApplication + shared | ✅ |
| 2 | identity | ✅ |
| 3 | feed | ✅ |
| 4 | profile | ✅ |
| 5 | network | ✅ |
| 6 | media | ✅ |
| 7 | communities | ✅ |
| 8 | jobs | ✅ |
| 9 | events | ✅ |
| 10 | mentoring | ✅ |
| 11 | messaging | ✅ |
| 12 | notifications | ✅ |
| 13 | search | ✅ |
| 14 | moderation | ✅ |
| 15 | idempotency | ✅ |
| 16 | coin (carteira, disputas) | ✅ |
| 17 | marketplace | ✅ |
| 18 | games | ✅ |
| 19 | services (contratar/gerenciar) | ✅ |
| 20 | freelances | ✅ |
| 21 | support (tickets) | ✅ |
| 22 | ops (monitoramento + log de e-mails) | ✅ |
| 23 | themes + cosmetics (aparência) | ✅ |
| 24 | dailygames (jogos diários) | ✅ |
| 25 | push (notificações web push) | ✅ |
| 26 | gifs (busca de GIFs) | ✅ |
| 27 | groups (grupos) | ✅ |
| 28 | companies + corporate (empresas) | ✅ |
| 29 | communityprojects + projects | ✅ |
| 30 | preferences (preferências do usuário) | ✅ |
| 31 | stats (estatísticas públicas) | ✅ |
| 32 | age (faixa etária) | ✅ |
| 33 | apiv1 (API versionada /v1) | ✅ |
| 34 | blog | ✅ |
| 35 | importlinkedin + importresume + linkedin | ✅ |
| 36 | calendar (agenda central) | ✅ |

## Backend — features recentes

| Tema | Doc | Estado |
|------|-----|--------|
| Agenda central (compromissos, convites, recorrência, `.ics`, lembretes) | `CALENDAR.md` | ✅ |
| Processo seletivo (etapas, board, notas internas) | `SELECTION_PROCESS.md` | ✅ |
| Gitflow (feature → qa → main) | `GITFLOW.md` | ✅ |
| Concurrency and hot rows (isolation level, counters, lock order) | `CONCURRENCY.md` | ✅ |
| Monitoramento (painel, eventos, retenção) | `MONITORAMENTO.md` | ✅ (atualizar com filtro de data/hora e teto de linhas) |
| Modo pânico (chave de sistema, 429 global) | `MODULES.md` §shared | ✅ |
| Superfície da API (rotas conhecidas, resposta padrão fora delas) | `API.md` | ✅ |
| Contrato do painel de moderador (quem vê o quê) | `MODULES.md` §moderation | ✅ |
| Agentes de IA (contrato) | `../AGENTS.md` | ✅ |
| Acesso ao host pelo Actions | `ACESSO_HOST.md` | ✅ |

## Frontend (repo `enois-frontend`)

| Tema | Doc | Estado |
|------|-----|--------|
| Rotas (todas, tabela completa) | `docs/rotas.md` | ✅ |
| Painel do moderador (abas e permissões) | `docs/rotas.md` §moderador | ✅ |
| Painel de monitoramento (abas, filtros, logs) | `docs/rotas.md` §monitoramento | ✅ |
| Temas e coleções (60 temas, 2 modos) | `docs/temas.md` (novo) | ✅ |
| PWA e service worker | `docs/configuracao.md` | ✅ |
| Componentes principais | `docs/componentes.md` | ✅ |
| Modelos de dados | `docs/modelos-de-dados.md` | ✅ |
| Arquitetura | `docs/arquitetura.md` | ✅ |

## Log de rodadas

- **Rodada 1** (manual, 2026-10-24): inventário criado; documentados coin, marketplace, games,
  services, freelances, support, ops, themes/cosmetics, dailygames, push, gifs, groups,
  companies/corporate, projects, preferences, stats, age, apiv1, blog, import (backend); rotas do
  frontend atualizadas; temas documentados.

## Como continuar

Este esforço continua por rodadas automáticas (20 em 20 minutos) enquanto houver itens pendentes.
Se uma rodada não estiver rodando: abra uma nova conversa e peça "retomar a documentação dos
módulos/páginas" — o agente lê este painel e segue daqui, marcando o log abaixo a cada rodada.
