# Onde a observabilidade dos nós aparece — e onde ela não aparece

Investigação pedida em 2026-11-19 pelo mantenedor: "não estou tendo observabilidade dos nós no
painel de admin do front". Este documento diz o que existe, onde está e o que foi **medido**.

## 1. O painel de admin do front TEM a seção Rede, e ela está ligada

| peça | arquivo | estado |
|---|---|---|
| aba na navegação | `src/routes/-moderador/panel-groups.ts` (`id: "network"`, `adminOnly: true`) | existe |
| seção montada | `src/routes/moderador.tsx:300` — `me.admin && activeTab === "network"` | existe |
| componente | `src/components/moderation/network-nodes-section.tsx` (443 linhas) | existe |
| dados | `adminNetworkApi.dashboard()` / `.nodes()` / `.observability(nodeId)` | existe |
| quadro ao vivo por nó | `NetworkNodeObservabilityPanel` do `@rede-enois/design-system` | existe |

A seção mostra a faixa de rede (`NetworkDashboardData`), a lista de nós com estado e a página do nó
(`NetworkNodeDetailData`) — e nela o **quadro ao vivo**, que relê a cada minuto o
`GET /admin/network/nodes/{id}/observability`.

## 2. O que o quadro ao vivo mostra — e o que ele não mede

O painel por nó devolve quatro blocos (`docs/rede-distribuida/16-observabilidade-por-no.md`):
`series` de até 60 pontos (CPU, memória, disco livre, tarefas em execução), `summary`, `console` e a
contagem por nível.

O dado chega pelo **heartbeat** que o nó já faz (ele não abre porta). E aqui está a lacuna que se
enxerga na tela: o runtime manda `cpuPercent`, `memoryMb`, `runningTasks`, `diskFreeMb`,
`diskTotalMb`, `usedMb` — **e nada de rede**. Por isso o quadro não tem tráfego de entrada/saída, e a
dúvida "o nó está transferindo?" não é respondível pela tela.

## 3. O elo que faltava: o runtime e o tráfego (fechado no ciclo 161)

`NetworkNodeController.Observed` já tinha `bytesInMb` e `bytesOutMb`, e o banco já os guardava
(`V345__network_node_traffic.sql`: `observed_bytes_in_mb` / `observed_bytes_out_mb`). **O runtime
nunca os lia** — o heartbeat ia com `cpuPercent`, `memoryMb`, `runningTasks` e `disk*`, sem nenhuma
leitura de `/proc/net/dev`. Era o caso de "campo gravado e nunca populado", e a correção foi o
runtime medir o acumulado das interfaces externas e mandá-lo no heartbeat que já existe (doc 16).

No caminho, o próprio corpo do heartbeat estava quebrado: os fragmentos opcionais (`mem_part`,
`temp_part`, `disk_io_part`) não carregavam a vírgula e o separador depois de `runningTasks`
faltava, então **todo heartbeat com memória, temperatura ou I/O medidos** ia malformado
(`0"memoryMb"`) — o Control Plane respondia 400, o nó registrava "heartbeat falhou" a cada ciclo e
saía do balanceamento por `last_seen_at` velho. Corrigido no mesmo ciclo; `node-traffic-test.sh` lê
o corpo como JSON de verdade, então um separador faltando derruba o harness. A correção certa não é inventar uma tela nova: é o runtime medir o tráfego acumulado das
interfaces e mandar no heartbeat que já existe, para o campo que já existe.

## 4. Lacuna do §8.2 do brief que decorre disso

O brief (§8.2) pede, por nó: uso de rede e throughput de entrada e saída. Hoje:

| item do brief | estado |
|---|---|
| CPU total, memória, disco livre | **entregue** (heartbeat → painel por nó) |
| tarefas em execução, versão do runtime, último contato | **entregue** |
| uso de rede / throughput (entrada e saída) | **medido desde o ciclo 161** — o runtime soma o acumulado de `/proc/net/dev` (interfaces externas) no heartbeat; o painel mostra o total e a fatia de saída. **A TAXA desde o ciclo 169** (§13): MiB/s do intervalo, medida separada do acumulado |
| swap, pressão de memória, IOPS, latência de disco, temperatura, GC, threads, p95/p99 de tarefa, DLQ por fila | **swap e pressão desde o ciclo 157** (§7); **IOPS e latência de disco desde o 158** (§8 e §9); **temperatura desde o 159** (§10); **p95/p99 do tempo de execução das tarefas desde o 160**; **retentativas desde o ciclo 162** (§11.1); **DLQ por fila desde o 161** (`deadByQueue`, doc 03). **p50/p95/p99 de requisição HTTP do Control Plane desde o 163** (§11.2, com o tamanho da amostra); **threads do processo do nó desde o 163** (§11.3); GC e heap não se aplicam ao runtime (é shell, não JVM) — ver `docs/rede-distribuida/11-global-coop-fase-0.md` §3 |

## 5. O que o mantenedor precisa conferir no deploy (não é código)

Se a aba **Rede** não aparece no painel, as causas testáveis, em ordem:

1. **A conta não é `me.admin`** — a aba é `adminOnly` (`panel-groups.ts`); moderador sem admin não a vê.
2. **O front implantado é anterior à seção** — a aba e o componente existem no repositório; verifique
   o hash do build em `GET /api/version` contra o commit que contém `network-nodes-section.tsx`.
3. **`GET /admin/network/nodes` responde erro** — a seção mostra o erro da API; o sintoma é a faixa
   vazia com mensagem, não a aba ausente.
4. **Não há nó registrado** — a seção existe e a lista vem vazia; é o estado de rede sem participante.

Nenhuma dessas quatro é "a aba não existe". O próximo passo de código é o item do §3 deste documento
(tráfego no heartbeat), que fecha a parte visualmente perceptível da lacuna.

## 6. O tráfego passou a aparecer (ciclo 58)

O elo do §3 deste documento foi fechado nos três lados:

| lado | o que passa a existir |
|---|---|
| runtime | mede `/proc/net/dev` (só interfaces externas: `eth*`, `en*`, `wl*`, `ww*`) e manda `bytesInMb`/`bytesOutMb` no heartbeat |
| backend | `GET /admin/network/nodes` devolve `trafficInMb`/`trafficOutMb` (antes: campo `NetworkNode` já populado, nunca exposto no `NodeView`) |
| design system | linha do nó mostra "Tráfego" (`↓ 2.0 GiB · ↑ 512 MiB`); nulo ou metade medida é "Sem medição" |

Provas: `NetworkParticipantTrafficTests` (2 casos novos no painel de admin — medido e nulo);
`network-nodes-section` do DS (2 casos: medido e ausência/metade).

**O que continua faltando do §8.2 do brief** (medido, não estimado): GC/threads do runtime (o
runtime não é JVM — é shell) e p95/p99 de **requisição HTTP** (o `MonitoringService` mede a do Control Plane, não a de cada nó). (Swap e pressão de memória entraram no ciclo 157 — §7; IOPS e latência de disco, no ciclo 158 — §8 e §9; temperatura, no ciclo 159 — §10; p95/p99 do tempo de execução das tarefas por nó, no ciclo 160 — §11; a DLQ por fila, no 161 — §12.)

## 7. A memória em duas medidas, swap e pressão (ciclo 157)

O §2 desta doc dizia que o runtime manda `memoryMb` e o painel mostra "memória". O que não estava
dito é que **os dois lados liam coisas diferentes**:

| lado | o que significava `memoryMb` |
|---|---|
| runtime | `MemAvailable` — memória **disponível** |
| Control Plane | `observed_memory_mb`, comparado com `declared_memory_mb` (o teto **cedido**) em `memoryExhausted()` |

Um nó com 8 GB cedidos e 7,9 GB **livres** era "teto atingido" e saía do balanceamento com a máquina
ociosa; um nó com 8 GB cedidos e 100 MB livres aparecia com "uso de 100 MB". Duas medidas opostas na
mesma coluna, e a decisão de designação tomada sobre a errada.

### Agora (V381)

| campo | o que é | origem |
|---|---|---|
| `memoryMb` | uso medido | `MemTotal - MemAvailable` (`/proc/meminfo`) |
| `memoryAvailableMb` | disponível medido | `MemAvailable` |
| `swapUsedMb` | swap em uso | `SwapTotal - SwapFree` — só vai quando o kernel expõe |
| `memoryPressurePercent` | `avg300` do `some` em `/proc/pressure/memory` | PSI — só vai quando o kernel expõe |

Decisões:

- **O nome `memoryMb` ficou com o uso**, e não o disponível: os outros `observed_*` são todos "o que
  está em uso" (CPU, disco ocupado, tarefas rodando), e `node_samples.memory_mb`, vizinha, já
  descrevia o uso. Renomear criaria duas verdades na mesma tabela.
- **Runtime antigo continua lido certo.** Só manda `memoryMb`, e era o disponível: o Control Plane
  deriva o uso (`declarado - disponível`) em `NetworkNode.usageFrom`. Disponível **acima** do
  declarado (máquina com mais RAM do que o nó cede) não deriva — daria uso negativo, e nulo é a
  resposta honesta. Zero não deriva: `meminfo` ilegível não é "tudo em uso".
- **Nada é inventado quando o kernel não expõe.** Sem PSI (kernel < 4.20, ou desligado) o campo não
  vai; sem swap no `meminfo`, idem. Nulo é "não medido", o mesmo contrato do resto do heartbeat.
- **O nó já registrado é reinterpretado na migração**: todo valor não nulo de `observed_memory_mb`
  anterior à V381 veio do runtime antigo, que mandava o disponível. A V381 copia esse número para
  `observed_memory_available_mb` e deixa o uso nulo (`NULL|7900|…`), e o `usageFrom` deriva o uso
  como para qualquer runtime antigo — nulo quando o disponível passa do declarado. Sem esse update
  o nó que já estava gravado continuaria "teto atingido" até o próximo heartbeat, que é exatamente o
  defeito que a V381 corrige.
- **A série histórica usa o uso derivado**: `network_node_samples.memory_mb` recebe o valor que o
  serviço derivou para o nó, não o campo cru do corpo. O runtime antigo registrava o disponível na
  série também, e a amostra passaria a dizer "7,9 GB em uso" da máquina ociosa.
- **Swap e pressão: medidos e agora consumidos (ciclo 165).** O runtime mede `swapUsedMb`
  (`SwapTotal - SwapFree`) e `memoryPressurePercent` (`avg300` do `some` em
  `/proc/pressure/memory`) desde o ciclo 157, mas o Control Plane não os recebia no contrato do
  `Observed`: eram descartados na borda. Desde a V389 eles entram no heartbeat, viram as colunas
  `swap_used_mb` e `memory_pressure_percent` da amostra, e aparecem em
  `summary.swapUsedMbLatest`/`summary.memoryPressurePercentLatest` (§11.4).

### Verificação

- `NetworkHeartbeatMemoryTests` **5** pela rota e pelo banco: uso e disponível distintos e gravados;
  teto só em 8192 de 8192, não em 8000; runtime antigo derivando 292 de 8192 de 7900 disponíveis;
  disponível acima do declarado não vira uso; zero não vira uso; sem memória não afirma nada.
- `node-executor-test` (**+4**, 223 asserções): o payload real do ciclo leva
  `memoryMb`, `memoryAvailableMb` e `swapUsedMb`, e o uso **não** é o mesmo número do disponível.
- `scripts/tests/node-memory-migration-test.sh` em **MariaDB 11.4 real**: V381 aplicada **duas
  vezes**; nó do contrato antigo reinterpretado (`NULL|7900|2|8192|ACTIVE`), inclusive quando o
  disponível passa do declarado; a coluna nova não recebe zero; o update do heartbeat grava as três
  colunas; a régua do teto confere no banco; tipo `int` e `NULL` aceito.
- Mutações: runtime mandando o disponível no lugar do uso (derruba 1 do harness); não derivar o
  runtime antigo (2); derivar disponível acima do declarado (1); zero virando uso (1); migração sem
  a coluna (9 falhas). Todas restauradas verdes.

## 8. IOPS e latência de disco (ciclo 158)

O §8.2 pede, por nó, IOPS e latência de disco. O Control Plane não abre conexão para dentro da
máquina do participante (brief §17), então quem mede é o runtime — de `/proc/diskstats` — e manda no
heartbeat que já existe. O caminho inteiro entrou neste ciclo:

| lado | estado |
|---|---|
| rota (`Observed.diskIops`, `Observed.diskLatencyMs`) | existe |
| banco (`V381`: `observed_disk_iops`, `observed_disk_latency_ms` em `network_nodes`; `disk_iops`, `disk_latency_ms` em `network_node_samples`) | existe |
| quadro do nó (`series[].diskIops/diskLatencyMs`, `summary.diskIopsLatest/diskLatencyMsLatest`) | existe |
| runtime medindo `/proc/diskstats` | existe — delta entre ciclos, §9 |

### As duas medidas

- **`diskIops`** — operações (leitura + escrita) concluídas por segundo no intervalo entre dois
  heartbeats. `0.0` é uma **medida** ("medi e não houve operação no intervalo"); `null` é "não
  medido" (primeiro ciclo, runtime antigo, disco não identificável).
- **`diskLatencyMs`** — tempo médio por operação concluída no mesmo intervalo. Fica `null` quando o
  intervalo não teve operação: não há por onde dividir, e `0` afirmaria latência zero.

A série curta guarda as duas por minuto (o mesmo ponto do minuto é trocado, não duplicado), e o
`summary` mostra o último valor medido.

### Verificação

- `NetworkHeartbeatDiskIoTests` **3**, pela rota e pelo banco: o heartbeat grava e o quadro mostra
  as duas medidas; IOPS 0 medido sai como `0.0` e a latência sem operação sai nula; sem medida, os
  quatro campos do quadro são nulos.
- `scripts/tests/node-disk-io-migration-test.sh` **26 PASS / 0 FAIL** em MariaDB 11.4 real: V381
  aplicada **duas vezes**, nó e amostra do contrato antigo preservados com `NULL`, update do nó e
  upsert da amostra gravando as colunas, zero medido distinto de nulo, tipo `double` aceitando `NULL`.
- Mutação: V381 sem as colunas derruba **17** asserções do harness.

## 9. A medição no runtime (`/proc/diskstats`)

O runtime lê o arquivo apontado por `DISKSTATS_FILE` (produção: `/proc/diskstats`, o do kernel) e
guarda no arquivo de estado do nó (`$NODE_HOME/disk-prev`) o **instante da medida em nanossegundos** e
os contadores somados dos discos. O próximo ciclo subtrai os dois:

- **disco que entra na soma** — `sd*`, `nvme*n1`, `vd*`, `xvd*`, `hd*`. Ficam de fora partições
  (`sda1`) e dispositivos de bloco que não são disco (loop, ram, dm, md): o I/O deles aparece no disco
  de baixo, e somar os dois contaria em dobro.
- **intervalo** — o relógio da medida anterior, em nanossegundos. Tempo de arquivo não serve: a
  resolução varia com o sistema de arquivos, e 1 s de erro num intervalo de 5 s vira 20% de IOPS.
- **retrocesso** — se qualquer contador diminuiu (disco trocado, contador de 32 bits reiniciado), esta
  rodada não manda taxa nenhuma; o referencial passa a ser o de agora, então o nó volta a medir no
  ciclo seguinte em vez de ficar mudo até reiniciar.
- **primeiro ciclo / runtime antigo** — sem referencial, os campos não vão: `null` é "não medido".

`diskIops` é operações (leitura + escrita) por segundo; `diskLatencyMs` é `ms / operação` no mesmo
intervalo. Sem operação concluída, o IOPS é `0.0` (medido) e a latência não vai (não há por onde
dividir). A soma usa as colunas **4 (leituras), 7 (ms lendo), 8 (escritas) e 11 (ms escrevendo)** do
layout do kernel — as colunas 6 e 10 são **setores**, não tempo. A primeira versão leu 6/10 e teria
mandado latência `0.00` sempre; o harness pegou a troca e a asserção de latência (3,00 ms com
operações e ms fixos) passou a prender o valor exato.

### Verificação

- `scripts/tests/node-executor-test.sh` **234 asserções / 0 falhas**, com quatro ciclos reais sobre o
  corpo do `node-runtime.sh`: 1º sem campos e referencial gravado; 2º com `diskIops` = 500 ops ÷
  intervalo real (entre 99 e 100 num intervalo de 5 s) e `diskLatencyMs = 3.00` exato, partição e loop
  fora da conta; 3º com contador retrocedendo e nenhuma taxa; 4º com `diskIops = 0.00` e latência
  ausente. Mutação: usar as colunas 6/10 derruba a latência; emitir latência sem operação derruba o 4º
  ciclo; remover a checagem de retrocesso derruba o 3º; incluir a partição derruba o referencial.
- `NetworkHeartbeatDiskIoTests` **3** e a migração **26 PASS** — §8.

## 10. Temperatura da máquina (ciclo 159)

O §8.2 pede a temperatura "quando disponível". O runtime lê
`/sys/class/hwmon/hwmon*/temp<N>_input` (CPU, GPU, NVMe, placa) e as zonas térmicas ACPI do kernel,
e manda a **maior leitura válida** em graus, com uma casa:

- **maior, não média** — uma máquina tem CPU, GPU e NVMe com temperaturas diferentes, e a que decide
  risco de limiar é a maior; a média esconderia o componente que está cozinhando;
- **faixa de operação** — leitura abaixo de −40 °C ou acima de `THERMAL_MAX_C` (padrão 110 °C) é
  descartada: sensor quebrado e sensor de ponto de critério (~115–125 °C, a temperatura em que a
  proteção dispara) devolveriam 120+ e virariam alarme. O teto é variável para máquina cujo limiar
  real é outro;
- **ausência é normal** — máquina virtual, container sem `--device`/`/sys` do host e sessão sem
  permissão de leitura não têm o arquivo, e o campo não vai. `null` é "não disponível"; `0` seria
  uma temperatura que ninguém leu;
- **uma casa decimal** — 39,6 e 39,4 são temperaturas diferentes; a coluna guarda o número
  digitalizado (39,6), não um arredondamento que junte as duas.

Caminhos dos sensores são variáveis (`THERMAL_HWMON_DIR`, `THERMAL_ZONE_DIR`) para o harness servir
leituras conhecidas e para o container cujo `/sys` do host está montado em outro ponto.

### Verificação

- `NetworkHeartbeatTemperatureTests` **3**: a medida chega ao banco e ao quadro; a casa decimal é da
  medida (39,6 e 39,4 ficam distintas); sem sensor, série e `summary` ficam nulos.
- `scripts/tests/node-executor-test.sh` **239 asserções**: sem sensor o campo não vai; com quatro
  fontes (37,85 / 38,00 / 39,62 / 125 °C) vai `39.6` e não `38.0` (maior, não a primeira, e sem a
  leitura acima da faixa); zona ACPI sozinha conta. Mutações: teto em 150 °C e "primeira fonte"
  derrubam a asserção da maior; a divisão inteira do shell (o defeito corrigido) manda `39` e
  derruba a da casa decimal.
- `node-temperature-migration-test` **23 PASS / 0 FAIL** em MariaDB 11.4 real: `V382` aplicada
  **duas vezes**, nó e amostra antigos preservados com `NULL`, update/upsert gravando `39.6` (não
  `40`), zero distinto de nulo, tipo `double` aceitando `NULL`. Mutação (V382 sem as colunas) derruba
  **13**.

## 11. Latência p50/p95/p99 das tarefas, por nó (ciclo 160)

O §8.2 do brief pede a latência em p50, p95 e p99. O que existia era `network_nodes.observed_latency_ms`
— e ela **não é mediana**: é média móvel exponencial (`(antiga * 3 + nova) / 4`, em `recordOutcome`).
Média responde "quanto, em média" e não "quanto é o pior 5%", que é a pergunta da cauda.

**Como mede.** Cada tarefa concluída com sucesso entra numa **faixa** de duração (potências de dois,
de 1 ms a ≈ 68 min) na hora corrente, em `network_task_latency_buckets` (V386). A escrita é um
`UPSERT` que soma (`samples = samples + 1`) com a hora truncada antes de gravar: duas tarefas da
mesma hora caem na mesma linha, e a tabela cresce com o número de faixas usadas, não com o de
tarefas. O painel (`GET /admin/network/nodes/{id}/observability`, campo `latency`) lê 24 horas e
calcula os percentis por interpolação linear dentro da faixa (`TaskLatency`), o mesmo método do
`histogram_quantile` do Prometheus.

**O que o número diz e o que não diz.** A precisão é a da faixa: entre 1 e 2 ms o erro é de 1 ms;
entre 4 e 8 s, de segundos. `samplesInWindow` acompanha os percentis porque p99 sobre 10 tarefas é
uma anedota. Sem tarefa na janela, os três percentis são `null` — 0 seria "toda tarefa concluiu em
0 ms", que é uma medida. Faixa sem amostra abaixo não vira piso: com uma única amostra de ≤256 ms, os
três percentis dizem 256 (o teto), não 128 (o meio de uma faixa que ninguém mediu).

**Falha não entra:** uma tarefa que falhou não tem tempo de execução, tem tempo até a falha; se
entrasse, um nó que só falha apareceria com latência excelente.

Provas: `TaskLatencyTests` (6 casos: faixa, cauda, faixa única sem piso, faixa vazia, uma amostra,
ausência) e `NetworkTaskLatencyTests` (6 casos: 9 rápidas + 1 lenta pela rota até o painel, nó sem
tarefa com percentil nulo, falha fora do histograma, janela de 24 h, soma na mesma linha, dois nós
sem vazamento). Mutações conferidas: não registrar no desfecho derruba 2; registrar falha derruba 1;
ignorar a janela derruba 1; gravar sem truncar a hora derruba 1; somar sem o nó derruba 1.
`node-task-latency-migration-test` **14 PASS / 0 FAIL** em MariaDB 11.4 real: `V386` aplicada duas
vezes, nó/tarefa anteriores preservados, chave composta, `utf8mb4`, upsert somando na mesma linha,
janela de 24 h separando hoje de anteontem. Mutação (V386 vazia) derruba **6**.

## 11.1 Retentativas das tarefas (ciclo 162)

O §8.2 do brief pede **retentativas**, e o que a rede tinha não respondia. `network_tasks.attempt`
já era a tentativa em curso, mas **quem a contava era só o `claim`** (o despacho); a devolução à fila
(`requeue`, em falha retentável e em recusa de verificador) deixava o número parado. A consequência
era medida:

- `p95/p99` da §11 respondiam "quanto tempo até concluir" e ninguém sabia **em quantas tentativas** o
  nó concluía — um nó que acerta de primeira e um que precisa de quatro tinham o mesmo número;
- a tarefa devolvida voltava com `attempt` antigo, e o painel não conseguia separar **fila normal**
  de **fila doente** (a que já falhou uma vez e vai tentar de novo).

**A regra do número.** `attempt` conta **entregas**: começa em 0 na criação, o `claim` soma 1 a cada
entrega e a devolução **não** mexe — devolver é dizer "tente de novo", não "já tentou". Duas razões
para não somar na devolução: (a) o número pularia de 1 para 3 numa rodada com recuperação de claim
vencido; (b) o limite de `maxAttempts`, que compara com o `attempt` da entrega, gastaria duas
tentativas do participante por um único claim perdido. A tentativa perdida é derivada na hora de
gravar: a entrega que conclui soube seu número, e as anteriores (uma a menos) não concluíram.

**O que muda no painel.**

- `GET /admin/network/tasks/overview` ganha `retried`: tarefas **abertas** (`PENDING`) que já foram
  entregues ao menos uma vez (`attempt >= 1`). Recém-criada está em 0 e não conta; é essa a diferença
  entre `pending` e "quanto disso é a fila doente".
- `GET /admin/network/nodes/{id}/observability`, campo `latency`, ganha `retriesInWindow` (quantas
  tarefas concluídas na janela precisaram de retentativa) e `retriesP50`/`retriesP95`/`retriesP99`
  (distribuição de **tentativas perdidas até concluir**, na mesma interpolação por faixa da §11).
  Sem nenhuma tarefa retentada na janela, os percentis são `null` — 0 seria "precisou de zero
  tentativas", que é outra afirmação.

**Por que coluna separada da duração.** O tempo até uma falha não é tempo de execução. Somar a
tentativa à coluna `samples` esticaria a cauda (p95/p99 de execução) com trabalho que não terminou;
por isso a V387 acrescenta `retries` em vez de reusar `samples`. A leitura soma as duas colunas
separadamente (`SUM_LATENCY` e `SUM_RETRIES`), e a escrita é o mesmo `UPSERT` contador na mesma linha
de (nó, hora, faixa).

Provas: `NetworkTaskRetryTests` (3 casos pela rota real: entrega que devolve e conclui — duração 1,
retentativa 1, faixa da contagem = `bucketOf(entregas - 1)`; conclusão de primeira com percentil de
tentativa `null`; `/overview` com quatro tarefas — concluída de primeira, devolvida e aberta,
devolvida e concluída, recém-criada — e `retried` 1, `pending` 2, `done` 2). Mutações conferidas:
devolução somando a tentativa derruba 1; não gravar a amostra de tentativa derruba 1; `retried` sem
o filtro derruba 1; gravar a tentativa no contador da duração derruba 1. `node-task-retries-migration-test`
**11 PASS / 0 FAIL** em MariaDB 11.4 real: V387 aplicada duas vezes, duração anterior preservada com
`retries` 0, upsert somando na mesma linha sem mexer em `samples`, 2ª aplicação sem tocar dado vivo.
Mutações conferidas: coluna anulável derruba 3; sem `if not exists` derruba a 2ª aplicação.

## 11.2 p50/p95/p99 de requisição HTTP do Control Plane (ciclo 163)

O §8.2 pede a latência de <b>aplicação</b> (p50/p95/p99 de requisição), que é outra métrica da §11:
ali é o tempo de execução das <b>tarefas</b>, por nó e por faixa; aqui é a duração de cada
<b>requisição HTTP ao Control Plane</b>, medida pelo filtro e agregada por minuto no
`MonitoringService` (`/admin/monitoring/overview`, campos `p50Ms`, `p95Ms`, `p99Ms`).

**O que já existia e estava certo.** O percentil é o nearest-rank sobre as durações reais dos
últimos 5 minutos (teto de 2000 por minuto). Ao medir, o cálculo parecia ter erro de representação
(codei `ceil(n * p)` sem folga e supus que o `double` pulava uma posição) — <b>verifiquei e não tem</b>:
para todo n de 1 a 2000, com p em 0,50, 0,95 e 0,99, o produto é exato e a folga de `1e-9` não move
posição nenhuma. O código do percentil ficou como estava e o comentário diz isso; não se "conserta" o
que não está quebrado.

**O que faltava.** O p95 era um número sem denominador: a resposta não dizia sobre quantas
requisições ele foi calculado. `latencySamplesInWindow` (aditivo, inteiro) acompanha os percentis —
"p95 em 3 s" sobre duas requisições não é a mesma afirmação que sobre duas mil, e a §8.2 pede a
métrica para apoiar decisão. Minuto sem requisição continua 0 no ponto da série (o contrato antigo do
campo, que o painel lê como "sem dado") e não herda o p95 do minuto vizinho.

Provas: `MonitoringRequestLatencyTests` (3 casos: 2000 durações distintas 1..2000 ms — p50 na
posição 1000, p95 na 1900, p99 na 1980, com `latencySamplesInWindow` 2000; duas amostras, p95 no
maior e o denominador 2; relógio fixo, minuto vazio com p95 0 e o corrente com o valor). Mutações
conferidas: `latencySamplesInWindow` fixo em 0 derruba 2; o minuto vazio herdando o p95 derruba 1. Um
mutante inerte (o ponto ocupado usando a lista da janela) foi <b>descartado</b>: com uma única
amostra o valor é o mesmo e ele não muda comportamento. O relógio do teste é fixo de propósito: a
leitura de `overview` corta a série em `clock.millis()`, e com relógio real a virada do minuto entre
as duas leituras faria o ponto "corrente" mudar de posição.

## 11.3 Threads do processo do nó (ciclo 163)

O §8.2 pede, no bloco **Runtime**, "Threads" — e o runtime do nó mede o host inteiro; as threads do
**próprio processo** não eram lidas. O que o bloco pede e o que existe:

| item do bloco | estado |
|---|---|
| estado do processo, uso de memória | **entregue** (memória do host e `runningTasks` da amostra) |
| threads | **medido desde o ciclo 163** (`threads`, V388) |
| GC, heap, non-heap | **não se aplica**: o runtime é `bash`, não JVM. O do Control Plane está no `/admin/monitoring/overview` (`jvm`) |
| tempo de execução, reinicializações | uptime do **host** existe desde antes; o do processo não — cada ciclo é um processo curto que sai, e "uptime do processo" seria sempre ~0 |

**Como mede.** `Threads:` de `/proc/<pid>/status` do processo que está medindo (o kernel publica
ali a contagem da tarefa inteira). Vai como `threads` no `observed` do heartbeat e vira a coluna
`threads` da amostra do minuto. Ausente — `/proc` inacessível, runtime antigo — fica **nulo**:
zero afirmaria um processo sem thread. Valor ilegível (arquivo truncado) não vira medida.

**Defeito grave achado no caminho.** Montar o corpo por fragmentos que "carregam a própria vírgula"
só fecha se o fragmento seguinte só entrar quando o anterior entrou. Sem `/proc/meminfo` (o normal em
container) e com temperatura medida, o `observed` saía `"runningTasks":0,,"temperatureC"` — JSON
inválido, **400 do Control Plane e o nó fora do balanceamento** em toda máquina sem meminfo. É o irmão
do defeito do ciclo 161 (falta de vírgula). Agora o acúmulo é em `obs`: o primeiro fragmento entra sem
a vírgula da frente (`${fragmento#,}`) e os demais entram como estão.

Provas: `node-threads-test` **8/8** roda o `cycle` **real** com `/proc` forjado: `Threads:` do arquivo
vai como `threads`, acompanha o arquivo na volta seguinte, ausente NÃO vai, ilegível NÃO vira medida, e
o corpo sem memória medida é JSON válido. Mutações conferidas: sem a leitura (2 falhas), `threads` fixo
em 0 (3), sem a guarda numérica (1), vírgula dupla no corpo (2). `NetworkNodeThreadsTests` (2 casos,
pela rota real) — mutações: adapter não grava (1), controller descarta (1). `node-threads-migration-test`
**10/10** em MariaDB 11.4 real (V388 duas vezes, amostra anterior intacta com `threads` NULL, UPSERT do
minuto, `int NULL` sem default, dado vivo intacto na 2ª) — mutações: `not null default 0` (2), sem
`if not exists` (1).

## 11.4 Swap em uso e pressão de memória (ciclo 165)

O §8.2 pede, no sistema operacional, **swap** e **pressão de memória**. O runtime media os dois desde
o ciclo 157 e o Control Plane os descartava — o painel dizia "não medido" para sempre.

| campo do heartbeat | o que é | origem |
|---|---|---|
| `swapUsedMb` | swap em **uso**, em MiB | `SwapTotal - SwapFree` de `/proc/meminfo` |
| `memoryPressurePercent` | tempo em que alguma tarefa esperou memória, média de 300 s | `avg300` do `some` em `/proc/pressure/memory` (PSI) |

**Onde aparece.** Viram as colunas `swap_used_mb` (bigint) e `memory_pressure_percent` (double) da
amostra do minuto (V389), e o painel do nó as publica em `summary.swapUsedMbLatest` e
`summary.memoryPressurePercentLatest`.

Decisões:

- **Ausente é nulo, nunca 0.** `meminfo` sem as linhas de swap (container sem swap) e kernel sem PSI
  (anterior a 4.20, ou desligado no boot) não mandam o campo; um `avg300` ilegível (arquivo truncado)
  também não. Zero afirmaria "nenhum swap em uso" e "ninguém esperou memória" para quem não leu o
  arquivo.
- **`avg300`, não `avg10`/`avg60`.** São janelas diferentes; a de 300 s é a que diz "está sofrendo"
  sem piscar com um pico de um segundo.
- **O resumo mostra o último valor MEDIDO da janela**, não o da amostra mais nova. Um ponto recente
  sem a medida (PSI desligado em quente, swap removido) não é "a máquina melhorou" — é "não sei", e
  o número de dez minutos atrás com a idade da série ao lado (`freshness`) diz mais do que um vazio.
  A série em si continua mostrando a lacuna no ponto: trocar o resumo não inventa medida.
- **`swapUsedMb` é MiB em uso**, não o total e não o livre; `bigint` porque 4 GiB de swap não cabem
  num `int` de 2^31 MiB.

**Verificação:** `NetworkNodeMemoryPressureTests` **3** pela rota real (swap 512/pressão 4.5 viram
amostra e chegam ao resumo; runtime que não mede deixa nulo e o resumo omite; o ponto novo sem a
medida não apaga a última medida, e a série mantém a lacuna). Mutações: resumo pegando a amostra
mais nova (1), adapter não gravando (1), controller descartando (1). `node-memory-pressure-test`
**9/9** roda o `cycle` **real** com `/proc` forjado (swap = total − livre em MiB; `meminfo` sem swap
não afirma swap; pressão é o `avg300`, com `avg10`/`avg60` bem diferentes de propósito; sem PSI não
afirma; PSI sem `avg300` não vira medida; corpo JSON válido). Mutações: swap livre no lugar do usado
(2), `full` no lugar de `some` (2), `avg10` no lugar de `avg300` (3), nome do campo trocado (2).
`node-memory-pressure-migration-test` **11/11** em MariaDB 11.4 real (V389 duas vezes, amostra
anterior intacta com as duas NULL, `bigint`/`double` sem default, UPSERT do minuto com memória junto,
4 GiB de swap cabe). Mutações: `int not null default 0` (3), sem `if not exists` (1), pressão como
`int` (3).

## 12. DLQ por fila (ciclo 161)

O `dead` do `/admin/network/tasks/overview` dizia **que** havia tarefa esgotada, não **qual fila**.
`deadByQueue` abre a conta por tipo de tarefa, só nos estados terminais sem sucesso (`DEAD`,
`FAILED`, `CANCELLED`): "8 mortas em `OBJECT_PUT`" (replicação) e "8 mortas em `MEDIA_THUMBNAIL`"
(encoder) pedem ações diferentes. Uma consulta agrupada por tipo e estado, não uma por tipo. Tipo sem
essas tarefas não aparece; o quadro vem vazio quando não há nada terminal.

Provas: `NetworkTaskQueueTests` (3 casos: duas filas mortas não se misturam e tarefa viva não entra;
falhada e cancelada separadas da morta; quadro vazio sem terminal). Mutações conferidas: tudo numa
fila só derruba 1; estado ignorado (tudo `DEAD`) derruba 3; todos os estados no quadro derruba 1.

### Correção do ciclo 161

O runtime passou a medir de fato; o `awk` que separa campos por `[: ]+` foi trocado por um que
remonta os tokens depois de `:` — com o espaço depois de `:` a segunda coluna fica vazia e todo
índice anda. Ponte (`br-*`), `docker0` e `veth*` não entram: o pacote de um container aparece nele
e na ponte, e somar as duas contaria o mesmo tráfego duas vezes. Provas: `node-traffic-test.sh`
(8 casos, roda o `cycle` real com `/proc/net/dev` forjado); mutações conferidas — separador
ausente (1 falha), ponte/docker somados (3), posições fixas (3), ausente virando zero (1).

## 13. Taxa de rede medida, e o throughput global (ciclo 169)

O §8.2 pede **throughput** de entrada e saída; o §8.3, **throughput global**. O que existia desde o
ciclo 58 era o **acumulado** de `/proc/net/dev` — um contador que só sobe, útil para o painel do
participante ("quanto já passou por aqui", §18) e que não responde "quanto está passando agora".
Somar os acumulados dos nós não dá razão nenhuma, e a diferença entre dois pontos dividida pelo
intervalo entre eles não pode ser refeita depois: o intervalo de cada amostra não fica gravado.

| onde | o que passa a existir |
|---|---|
| runtime | guarda contadores e instante da medida anterior em `$NODE_HOME/net-prev` e manda `netInMbps`/`netOutMbps` — MiB/s do intervalo — ao lado do acumulado |
| heartbeat | `observed.netInMbps`/`observed.netOutMbps` (novos, aditivos) |
| série do minuto | colunas `net_in_mbps`/`net_out_mbps` (V391); o resumo do nó publica `netInMbpsLatest`/`netOutMbpsLatest` |
| painel global | `GET /admin/network/nodes/dashboard` ganha `throughput`: `inMbps`, `outMbps`, `measuredNodes` e `measuredBytesSinceBoot` |

Decisões:

- **A taxa é medida; o acumulado é outra pergunta.** `bytesInMb`/`bytesOutMb` continuam indo em MiB
  e são do acumulado desde o boot. A taxa vai em campo próprio, em MiB/s.
- **Ausente é nulo, nunca 0.** Primeiro ciclo (sem anterior para subtrair), `/proc/net/dev` ilegível
  e contador que retrocedeu (interface recriada — uma taxa negativa ou de horas não é medida) não
  mandam o campo. Uma rede ociosa manda `0.000`: foi medido, e nada passou.
- **O estado avança sempre**, inclusive no ciclo que não mediu: se só avançasse quando medisse, um
  contador reiniciado deixaria o nó sem taxa até alguém reiniciar o processo.
- **O global soma só o minuto corrente de nós `ACTIVE` e vivos** na janela do scheduler, e cada
  campo soma só sobre quem o mediu. Sem nenhuma medida, `inMbps`/`outMbps` são nulos — zero
  afirmaria "nada passou" para o que ninguém mediu. `measuredBytesSinceBoot` fica em campo próprio
  para o acumulado não aparecer como se fosse taxa.

Provas: `node-network-throughput-test.sh` (11 casos: roda o `cycle` real com `/proc/net/dev` forjado
e intervalo fixado no estado); `node-network-throughput-migration-test.sh` (V391 2× em MariaDB
11.4 real, dados preservados); `NetworkDashboardThroughputTests` (4 casos);
`NetworkNodeMemoryPressureTests` (3 casos novos: taxa do heartbeat vira amostra, runtime antigo deixa
nulo, o ponto novo sem taxa não apaga a última medida). Mutações conferidas: taxa derivada do
acumulado (3 falhas), sem anterior medindo mesmo assim (8), contador que retrocedeu virando taxa (1),
sem `/proc` mandando zero (8), global somando acumulado (2), morto entrando na soma (1), sem medida
virando zero (2), adapter descartando a taxa (1), heartbeat descartando a taxa (1), resumo pegando a
amostra mais nova (1). Uma mutação fica verde por construção — gravar o estado só nos ciclos que
medem —, porque o ciclo seguinte que mede sobrescreve o estado congelado; ela é inobservável, não
inútil.

## 14. As quatro dimensões de saúde na rede inteira (ciclo 170)

O §8.8 do brief exige "obrigatoriamente" quatro dimensões de saúde — host, runtime, aplicação e
participação na rede — e explica o motivo: **uma máquina pode estar online e a réplica estar
inutilizável**. Elas existiam só na página do nó (`NodeHealth`, doc 06): para saber se a rede estava
saudável, o operador abria nó por nó até achar o problema.

`GET /admin/network/nodes/dashboard` ganha `health`:

| campo | o que diz |
|---|---|
| `countedNodes` | nós no quadro |
| `byStatus` | quantos em cada status do catálogo |
| `ok` / `degraded` / `failing` | por dimensão (`HOST`, `RUNTIME`, `APPLICATION`, `NETWORK`), quantos nós em cada estado |
| `measured` | `false` quando o atraso de sincronização de algum nó do quadro não foi consultado |
| `alerts` / `alertsTotal` | um alerta por nó com problema (ou da rede), o mais grave primeiro — cada um com `severity` de 0 a 4; o total mesmo quando a lista vem cortada |

Decisões:

- **Pendente de aprovação fica fora.** É fila de aprovação, não incidente — o mesmo recorte de
  `unavailable` no topo.
- **O atraso de sincronização alimenta a dimensão de aplicação**, e a conta é uma consulta por tipo
  de conteúdo. O quadro consulta até 50 nós por ciclo; acima disso `measured` é `false`, em vez de
  afirmar "em dia" sobre quem ninguém olhou.
- **A ordem dos alertas é a da urgência e é estável**: incidente de dado da rede (`NETWORK_DIVERGED`,
  `NETWORK_CAPTURE_NOT_READY`, severidade 4) → carga (`NODE_SATURATED`, `NODE_MEMORY_FULL`,
  `NODE_FULL`, 3) → servir pior (`NODE_FAILING`, `NODE_BEHIND`, `WORK_NO_TARGET`, 2) → fora por
  decisão (`NODE_QUARANTINED`, `NODE_NO_WORK`, 1) → nó fora da rede (`NODE_REVOKED`, `NODE_SUSPENDED`,
  `NODE_SILENT`, 0); dentro do mesmo código, pelo id do nó. O número vem no campo `severity` (§14.2).
  Sem isso, o recorte exibido dependeria da ordem em que a listagem veio do banco.
- **Carga antes de vaga.** Um nó em 92% com vaga declarada é `NODE_SATURATED`, não `NODE_FULL`: o
  teto é promessa, a CPU medida é o que está acontecendo — a mesma ordem do modo da rede, em que
  quem está saturado não promete vaga.
- **Um alerta por nó**, o mais grave: o suspenso que também está saturado não conta duas vezes. O
  quadro, esse sim, conta todos os fatos — o alerta é o resumo, a tabela é a medida.

### 14.1 O trabalho que o Master absorve porque ninguém pode pegá-lo (ciclo 171)

O §2.2 do brief é explícito — "o Master não deve atuar como executor obrigatório de todas as
tarefas" — e o §8.8 pede o alerta "Master sobrecarregado enquanto existem réplicas ociosas". O quadro
de cobertura (`workTypes`) já dizia *quantos* nós podem executar cada tipo; o que faltava era o
alerta dizer *que existe fila* dele e *que ela não anda*.

`health.alerts` ganha `WORK_NO_TARGET` (dimensão `APPLICATION`): um por tipo de trabalho que tem
`capable = 0` **e** pendente na fila — `"2 tarefa(s) ASYNC_JOB pendentes e nenhum nó capaz de
executá-las (3 pendentes na rede)"`. O primeiro número é do tipo, o segundo é a fila inteira (só
aparece quando difere). Um alerta de tipo sem fila **não** existe: nenhum nó executar
`MEDIA_TRANSCODE` é uma rede sem encoder, configuração — não falta de gente para uma fila.

O alerta é da **rede**, não de um nó: `nodeId`/`nodeName` são nulos. Por isso a ordenação dos alertas
trata o nó nulo (`nullsFirst`) — sem isso, o primeiro alerta de rede ao lado de um alerta de nó
estourava em NPE na ordenação (defeito encontrado e corrigido no mesmo ciclo, com teste).

A cobertura é calculada **uma vez** por painel e servida nos dois lugares (`workTypes` e o alerta):
duas contas seriam duas respostas para o mesmo quadro.

Provas: `NetworkModesTests.trabalhoSemQuemFacaViraAlertaDoPainelEOOperadorSabeQueEleFicouNoMaster`
(pela rota real: nó só de mídia, 2 tarefas `ASYNC_JOB` enfileiradas → alerta; nó de worker chega →
alerta some), `NetworkDashboardHealthTests` (2 casos novos, mais a ordenação com dois alertas de
rede e o NPE). Mutações: alerta sem exigir fila, alerta para tipo que alguém faz, tipo fora da
mensagem, contagem da rede no lugar da do tipo e comparador sem nulo — **todas derrubam**.

### 14.2 A causa da rede degradada, dita no painel (ciclo 173)

O §8.8 pede os alertas "divergência de dados" e "falha de captura do MariaDB". O modo medido já os
tratava — `NetworkModeService#healthSignal` manda a rede para `DEGRADED` quando há agregado divergindo
ou captura exigida e não pronta —, mas o painel **não dizia a causa**: o operador via `DEGRADED` e ia
procurar no lado da carga, que não tem nada a ver.

`health.alerts` ganha dois alertas **da rede** (`nodeId`/`nodeName` nulos, dimensão `APPLICATION`):

| código | quando | frase |
|---|---|---|
| `NETWORK_DIVERGED` | há agregado onde o nó e a origem discordam | `"3 agregado(s) de conteudo divergem entre o que o no serve e a origem"` |
| `NETWORK_CAPTURE_NOT_READY` | a instalação **exigiu** captura e o banco não serve para ela | `"a instalacao exige captura de alteracoes e o banco nao esta pronto para ela"` |

Decisões:

- **Uma conta, dois leitores.** A saúde da rede virou {@code NetworkHealth} (domínio, puro): o modo e o
  painel leem o **mesmo** `signal()`. Antes o painel teria de refazer a conta, e as duas respostas
  poderiam divergir — divergência no modo e não no alerta, ou o inverso.
- **"Não sei" não é problema**, a mesma regra do modo: `capture.check()` só é consultado quando a
  captura é exigida; servidor que não responde fica `READY`? **não** — fica sem medida e sem alerta.
- **A ordem virou a escala de severidade.** Cada alerta carrega `severity` (0 a 4, maior = mais grave):
  incidente de dado da rede (4) → carga (3) → servir pior (2) → fora por decisão (1) → nó fora da rede
  (0). A lista ordena por severidade, depois pelo id do nó, depois pelo código. Antes a ordem era uma
  lista de códigos em cascata e o alerta da rede não tinha lugar nela.
  **Mudança de ordem observável:** `WORK_NO_TARGET` (fila sem quem faça) agora vem **acima** de
  `NODE_SILENT`/`NODE_SUSPENDED`, porque severidade 2 > 0. É a consequência de dizer a gravidade em
  número; antes os dois grupos dividiam a lista por código e não havia número comparável.

Provas: `NetworkDashboardHealthTests` (18 casos; três novos são da rede — divergência acima de todo
alerta de nó, captura exigida e não pronta com **leitura real** do servidor em `STATEMENT`, e o caso
negativo "captura não usada / ROW / sem medida não alerta") e
`NetworkModesTests.redeDegradadaPorEvidenciaViraAlertaComACausaEDepoisOsAlertasDeNo` pela rota real.
Mutações derrubadas: rede saudável ainda alerta, alerta sem a causa (sempre `NETWORK_DIVERGED`), lista
sem a inversão, divergência que não degrada, captura sempre `READY` e captura sempre `NOT_READY` (as
duas últimas só derrubam porque o teste lê o servidor de verdade — com mock de valor fixo elas
ficavam verdes). Uma é inobservável: "o modo ignora a evidência" agora e o painel leem a mesma conta,
e o modo tem teste próprio (`NetworkModesTests`).

**Defeito de isolamento encontrado de verdade:** a divergência gravada por uma classe de teste ficava
no H2 do contexto e deixava a rede `DEGRADED` em `NetworkModesTests` que rodavam depois. O `setUp`
passou a limpar `network_content_snapshots` divergentes. É o mesmo padrão do ciclo 172 (`network_tasks`):
**teste que lê estado GLOBAL tem de limpar o que não é dele.**

**O que este bloco não faz, e por quê.** O §8.8 lista quinze alertas; aqui entram os que derivam de
dado que o Control Plane **já mede** num ciclo, sem estado novo: silêncio, suspensão/revogação,
quarentena, sem perfil, falhas seguidas, atraso, saturação, memória no teto e sem vaga. O brief pede
também severidade, **deduplicação, agrupamento e histórico de resolução** — isso é estado (um alerta
que persiste entre ciclos, com quem resolveu e quando), exige tabela própria e é outro ciclo. Os
alertas de captura do MariaDB, de declarações fora da capacidade e de tentativa de operação não
autorizada também ficam pendentes: os dois primeiros dependem do CDC (bloqueado por decisão de
infraestrutura, doc 14) e o terceiro de um registro de recusas por nó que hoje só existe na auditoria.

Provas: `NetworkDashboardHealthTests` (12 casos) e `NetworkNodesTests.dashboardAgregaARedeEmNumeros`
(a rota real: cinco nós, quatro no quadro). Mutações conferidas: pendente dentro do quadro (1),
atraso ignorado (2), atraso não consultado virando medido (1), ordem de urgência invertida (2),
recorte sem teto (1), host silencioso sem alerta (2), suspenso sem alerta (2), falhas seguidas sem
alerta (1), memória cheia sem alerta (1), vaga testada antes da carga (1) e dimensão contada no balde
errado (1).
