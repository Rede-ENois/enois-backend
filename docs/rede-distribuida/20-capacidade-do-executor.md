# 20 — Capacidade do executor: o nó diz o que consegue rodar

**Brief:** §8.2, "Health checks" — a declaração de capacidade do executor.
**Estado:** existe no código; a rota é opcional para o Control Plane que já a recebe.

## O problema

A permissão assinada (`allowedTasks`) diz o que o Control Plane **pode pedir** ao nó. Ela não diz
nada sobre o que a **máquina** consegue executar. Um nó com perfil `NODE_MEDIA` recebe
`MEDIA_TRANSCODE` na lista autorizada mesmo numa máquina sem `ffmpeg`: o despacho o escolhe, o nó
tenta, falta o binário e a tarefa gasta uma tentativa — uma por ciclo, para sempre.

Faltava ao nó um jeito de dizer "posso receber, mas não consigo rodar isto aqui".

## O que existe

### No Control Plane

- **Coluna** `network_nodes.executor_task_types` (`V391__network_node_executor_capabilities.sql`):
  JSON array de nomes de `TaskType`, `null` quando o nó nunca declarou.
- **Rota** `POST /network/nodes/me/executor-capabilities` (`NetworkNodeController`), na mesma
  credencial de nó do resto do `/me` (API key, sem sessão). Corpo:
  `{"enabledTaskTypes":["MEDIA_TRANSCODE"]}`. A rota está em `PUBLIC_WRITES` do
  `WriteRouteInterceptor` e na lista de `AutomatedWritePathsTests`.
- **Recorte na entrada** (`NetworkNodeService.declareExecutorCapabilities`, `@Transactional`): a
  declaração só pode escolher entre os tipos que a config assinada do nó autoriza
  (`TaskType.allowedNamesFor(perfis)` via `NetworkConfigService.allowedTasks(node)`). Um nó não
  declara saber o que nunca lhe seria pedido; o resto da lista é descartado.
- **Despacho** (`NetworkTaskService.choose`): o candidato só entra se `node.able(tipo)`.

### Semântica de nulo e de lista vazia

| Estado da coluna | Significado | Despacho |
|---|---|---|
| `null` | não declarou (runtime anterior a esta rota) | vale a permissão do perfil |
| `["X"]` | consegue rodar `X` | só o que está na lista |
| `[]` | não consegue nada | não é candidato |

O `null` existe de propósito: um nó que ainda não declara **não sai do balanceamento** por não
saber da rota. É a lista vazia que diz "não consigo nada", e é um estado explícito — não o silêncio.

### No runtime do nó (`deploy/node-runtime/node-runtime.sh`)

A declaração viaja **no pedido que já existe**, sem segundo canal nem segunda credencial: é uma
query string no `GET /network/nodes/me/config` (`--get --data-urlencode executorTaskTypes=[...]`).
O Control Plane que ainda não conhece o campo o ignora, e a config volta igual.

- `executor_nao_roda` — o que cada tipo exige além de bash/sed/awk/curl. Hoje só
  `MEDIA_TRANSCODE` (exige `ffmpeg`).
- `declaracao_de_capacidade` — cruza a lista que a config **autoriza** com o que a máquina roda.
  Lista vazia é declaração honesta e vai como `[]`, não como omissão.
- `declaracao_vencida` — o intervalo (`RELEASE_EVERY`, padrão 5 ciclos). Lista **igual** à que o
  Control Plane já recebeu e instante recente não se repetem. Lista **diferente** sai na hora: a
  capacidade perdida não espera a janela.
- Estado em `$NODE_HOME/executor-capabilities.json`: linha 1 = instante da última declaração
  **aceita**, linha 2 = o corpo enviado. Só a resposta que voltou grava — uma tentativa que não
  chegou seria dada como publicada até a janela seguinte, e o Control Plane seguiria sem saber que
  a máquina perdeu o executor.

## Testes

- `NetworkNodeExecutorCapabilitiesTests` (5): a declaração é gravada; tipo fora do perfil é
  recortado; `[]` é respeitado; `able` lida com `null`, `[]` e lista.
- `NetworkSchedulerExecutorCapabilityTests` (3): sem declaração despacha; `[]` não despacha; entre
  incapaz e capaz, despacha ao capaz.
- `scripts/tests/node-executor-test.sh`, cenário 9.1: sem `ffmpeg` a declaração sai `[]`; com
  `ffmpeg` sai `["MEDIA_TRANSCODE"]`; lista igual dentro da janela não repete; instante vencido
  repete; a perda do executor muda a lista na hora; queda de transporte não conta como publicado;
  e o tipo que a lista autorizada não contém não é declarado.

## O que não existe

- Não há métrica nem evento de auditoria para a declaração (o host é `/me`, autenticado pela
  credencial do próprio nó).
- A declaração não diz *por que* um tipo falta (binário ausente, configuração); só se consegue.