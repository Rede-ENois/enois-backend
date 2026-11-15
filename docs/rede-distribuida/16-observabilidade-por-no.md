# Rede Énois — observabilidade por nó

Documento do que **existe** no código. Complementa
[`03-control-plane-e-modo-de-rede.md`](03-control-plane-e-modo-de-rede.md) e
[`13-painel-de-monitoracao.md`](13-painel-de-monitoracao.md): aqueles descrevem o painel do
**Control Plane**; este descreve o painel **de cada nó**, dentro da página do nó.

## 1. O que o painel mostra

`GET /admin/network/nodes/{id}/observability` (admin) devolve quatro blocos, os mesmos quadros do
topo do painel de monitoração, calculados sobre a janela do nó:

| bloco | conteúdo |
|---|---|
| `series` | até **60** pontos, um por minuto, do mais antigo ao mais novo: `cpuPercent`, `memoryMb`, `diskFreeMb`, `runningTasks`, `mode` |
| `summary` | `cpuAvgPercent`, `cpuMaxPercent`, `memoryMbLatest`, `diskFreeMbLatest`, `runningTasksLatest`, `sampleCount` |
| `console` | até **200** linhas, da mais nova à mais antiga: `id`, `at`, `level`, `message` |
| `consoleLevels` | contagem por nível (`INFO`, `WARN`, `ERROR`) na janela |

`generatedAt` carimba o instante do quadro. Um ponto sem medida tem o campo **nulo**, não zero: o
gráfico desenha lacuna onde o nó não mediu — o mesmo princípio de `observedDiskFreeMb`.

## 2. De onde vem o dado — e por que não é um pull direto

O runtime do nó **não abre porta** (brief §17: o nó nunca é endereço de entrada; o Control Plane
fala com ele pelo caminho de saída que já existe). "Ler direto do nó" exigiria o backend abrir
conexão para dentro da máquina de um participante — exatamente o que o modelo pull impede.

Então o nó **empurra** a observação no heartbeat que ele já faz a cada ciclo:

```json
POST /network/nodes/heartbeat
{ "runtimeVersion": "…",
  "observed": { "cpuPercent": 12.5, "memoryMb": 2048, "runningTasks": 0, "diskFreeMb": 9000 },
  "logs": [ { "level": "INFO", "message": "tarefa tk_1 concluida", "at": "2026-11-12T12:00:00Z" } ] }
```

O efeito visível é o de um quadro ao vivo (atualizado a cada heartbeat, e a tela recarrega a rota);
o caminho é o que a arquitetura permite. `logs` é opcional: um runtime anterior a esta fase
continua funcionando e simplesmente não alimenta o console.

**O console do nó não é auditoria.** É uma janela curta, apagada por teto na escrita (abaixo). O
registro de auditoria (`audit_*`) tem outra promessa e não recebe estas linhas.

## 3. Teto e retenção

| tabela | conteúdo | teto por nó | como é aplicado |
|---|---|---|---|
| `network_node_samples` | um ponto por **minuto** | 60 | `trimSamples` depois de cada gravação |
| `network_node_logs` | linha do runtime | 200 | `trimLogs` depois de cada gravação |

O teto é aplicado **na escrita**, não por job: um nó que some não deixa lixo acumulando, e um que
volta encontra o histórico recente. Duas consequências que os testes fixam:

- **dois heartbeats no mesmo minuto trocam o ponto**, não abrem dois — a chave é
  `(node_id, sampled_at)` e a gravação é um upsert (`on duplicate key update`);
- apagar o nó (cascade) leva junto a janela dele.

A mensagem do console é normalizada no serviço: nível restrito a `DEBUG|INFO|WARN|ERROR` (o resto
vira `INFO`) e tamanho máximo de 500 caracteres — é uma linha de terminal, não um stack trace.

## 4. Do lado do nó

`deploy/node-runtime/node-runtime.sh` mantém um buffer curto e **em arquivo**
(`$NODE_HOME/pending-events`, modo `600`), porque o `cycle` roda num processo por volta e um
reinício não pode apagar a linha que ainda não subiu:

- `event <nível> <mensagem>` grava uma linha no buffer (até 20 pendentes);
- `notice_once` — que já avisa "aceito pela rede", "drenando", "recusada" — passa a alimentar o
  buffer **também**;
- o heartbeat anexa `"logs":[ … ]` ao corpo e **esvazia** o buffer só depois de o envio dar certo;
  se o heartbeat falhar, as linhas ficam para a próxima volta.

A linha de aceite nasce da **resposta** do heartbeat, então o primeiro heartbeat de um nó novo não
carrega linha nenhuma — o segundo é o que a leva. O harness fixa os dois lados.

## 5. Verificação

| verificação | resultado |
|---|---|
| `NetworkNodeObservabilityTests` | 5 — heartbeat alimenta série e console; dois no mesmo minuto = um ponto; teto de 200 na escrita; janela de um nó não vaza para outro; admin exigido e nó desconhecido é 404 |
| `node-executor-test` | **177** asserções (172 + 5 do painel): primeiro heartbeat sem linha, segundo com `"logs":[`, nível, mensagem e instante |
| `node-observability-migration-test` | **26** asserções em MariaDB 11.4 real, migrações 2× com dados |

Mutações conferidas: remover a gravação da amostra/linha no heartbeat derruba 4 dos 5 testes;
remover `trimLogs` derruba `oTetoDoConsoleValeNaEscrita…`; tirar `logs_part` do corpo do heartbeat
derruba `heartbeat carrega as linhas do painel`.

## 6. Defeitos encontrados no caminho

- **Buffer em memória não sobrevivia ao ciclo.** O loop roda um processo por volta, então a linha
  gravada no 1º ciclo sumia antes do 2º. Virou arquivo no `NODE_HOME` — e isso também é o correto
  em produção, onde um reinício não pode perder a linha pendente.
- **`event()` usava `cut`.** O runtime roda com PATH mínimo, e no harness sem `cut` a linha era
  descartada em silêncio (`2>/dev/null`, `|| return 0`). Agora normaliza com expansão de parâmetro
  do bash, sem dependência externa.
- **`network_node_samples` sem FK.** O harness de MariaDB real mostrou que apagar o nó deixava
  amostras órfãs: a FK com `on delete cascade` entrou na V325.
- **As asserções do harness procuravam `\"logs\"`** (com barra invertida literal) enquanto o stub
  registra o corpo sem escape — passariam ou falhariam por acidente. E a asserção do "primeiro
  ciclo" rodava depois de um segundo ciclo; foi movida para o instante certo.
- **Harness intermitente.** O `mariadb:11.4` reinicia o mysqld ao fim do init; "respondeu uma vez"
  não é "ficou no ar". A prontidão passou a exigir um create/drop de tabela após uma pausa.

## 7. O que NÃO está entregue

| item | por quê |
|---|---|
| leitura em streaming (push do backend para a tela) | a tela recarrega a rota a cada poucos segundos; um canal aberto por nó exigiria outra infraestrutura e outro modelo de falha |
| retenção longa / exportação do console | é janela de tela; para histórico existe o log do próprio nó, na máquina, e a auditoria do Control Plane |
| métricas de processo (heap, GC, threads) do nó | o nó reporta o que mede no host (CPU, memória, disco, tarefas). Heap/GC exigiriam o runtime expor métricas próprias |
| tela de front | as rotas existem; os quatro quadros (série, resumo, console, contagem) são o contrato da página do nó |