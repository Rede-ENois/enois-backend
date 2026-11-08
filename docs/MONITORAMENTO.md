# Monitoramento e defesa em tempo real

Registro dos ciclos de monitoramento e defesa do Énóis: o estado do app, o que foi
encontrado e o que foi feito. Uma linha por ciclo, sem inventar o que não foi lido.

## Como o ciclo funciona

- **Sinais**: `GET /api/stats/community` (3x), a home, `/api/health`, e uma checagem de que o
  limite por IP do login continua de pé. Latência de rota pública acima de ~2s, 5xx/503 ou
  timeout é incidente em andamento.
- **Logs**: dependem de acesso ao cluster. O bastion (`44.200.215.79`) hoje **não** tem
  credencial AWS (`aws sts get-caller-identity` falha) nem `kubectl`; para ler os logs do backend
  falta um destes: kubeconfig no bastion, permissão de CloudWatch (`aws logs`) para a instância,
  ou o endpoint de logs do cluster. Sem isso, o relatório diz que não houve leitura de log.
- **Defesa**: `shared/security/AbuseGuardFilter` (branch `feature/seguranca-risco`, commit
  `1c4c841`) — 20 erros em 3 min por IP → 429 com `Retry-After` por 15 min, dobrando na
  reincidência. Protege quando estiver publicado; enquanto não estiver, o ciclo só registra.
- **Nada de**: teste de carga contra a produção, bloqueio manual de IP sem ordem, mexer em
  infraestrutura.

## Métricas da plataforma (Prometheus)

Com o `micrometer-registry-prometheus` ligado, a outbox assíncrona aparece como três gauges de
cardinalidade fixa (`shared/outbox/OutboxMetrics`):

- `rede_outbox_pending` — backlog: eventos esperando processamento. Subida contínua sem descida =
  worker parado ou handler quebrando sistematicamente (o `last_error` da linha mais antiga diz qual).
- `rede_outbox_failed_attempts` — soma das tentativas falhadas dos pendentes: pressão de falha.
- `rede_outbox_events_total` — linhas na outbox, hoje limitadas pela janela de retenção (14 dias).
- `rede_media_upload_bytes` — tamanho efetivo gravado por upload de mídia, por tipo (`image`,
  `video`, `audio`). Hoje o valor gravado é o próprio arquivo recebido: **não há compressão** (#546).
  A série existe para mostrar o crescimento e para a diferença entre "recebido" e "gravado" aparecer
  quando uma compressão entrar.

## Ciclos

- **2026-10-22 17:20** — app saudável: `/api/stats/community` 200 em 0,92s/0,91s/0,91s, home 200,
  `/api/health` 200. Estável em relação aos dois ciclos anteriores. Sem leitura de log (bastion sem
  credencial AWS nem kubectl). Defesa, motor de risco e histórico de risco prontos no código,
  **ainda não publicados**.
- **2026-10-22 15:35** — app saudável: `/api/stats/community` 200 em 0,87s/0,80s/0,91s, home 200 em
  1,32s, `/api/health` 200 em 0,30s. Nada de diferente do ciclo anterior. Sem leitura de log (o
  bastion segue sem credencial AWS nem kubectl). Defesa contra varredura e motor de risco prontos
  no código, **os dois ainda não publicados**.
- **2026-10-22 14:30** — app saudável: `/api/stats/community` 200 em 0,81s/0,81s/1,02s (a
  distância até `us-east-1` explica o RTT; não é fila), home 200 em 1,19s, `/api/health` 200 em
  1,49s. Login respondeu 401 nas 6 tentativas erradas (o limite por IP é 60/min, então 6 não
  estouram — não é furo). Nenhum sinal de ataque ativo nos sinais medidos. Sem leitura de log
  (bastion sem credencial AWS nem kubectl). Defesa pronta no código, **falta publicar**.

## Prompt do agendamento (para criar em um chat NOVO)

A plataforma recusa criar uma tarefa agendada de dentro de uma sessão que já é uma tarefa
agendada. Para ligar o ciclo a cada 10 minutos, peça em um **chat novo** (ou crie na tela de
automações) com o título "Monitorar e defender o Énóis contra ataques a cada 10 minutos" e o
cron `*/10 * * * *`, usando como instrução o bloco "Como o ciclo funciona" acima mais:

> A cada 10 minutos: meça `GET https://enois.ia.br/api/stats/community` (3x, status e latência),
> a home e `/api/health`; se houver acesso ao cluster/bastion, leia os logs do backend dos
> últimos 10 minutos atrás de `abuse_guard`, `Rate limit`, 429/403/401 em rajada e rotas que não
> existem; registre uma linha em `docs/MONITORAMENTO.md` e commite local (sem push). Se a latência
> de rota pública passar de ~2s, houver 5xx/503/timeout ou 429 em cadeia, trate como incidente:
> registre a hora e a rota e diga qual é a contenção e o que falta para executá-la (acesso ao
> bastion/cluster ou publicação do código). Não publique, não bloqueie IP sem ordem e não rode
> teste de carga contra a produção.
