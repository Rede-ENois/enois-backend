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

## 3. O que o backend JÁ aceita e o runtime nunca manda

`NetworkNodeController.Observed` já tem `bytesInMb` e `bytesOutMb`, e o banco já os guarda
(`V345__network_node_traffic.sql`: `observed_bytes_in_mb` / `observed_bytes_out_mb`). O **runtime nunca mede nem envia**:

```
deploy/node-runtime/node-runtime.sh  # heartbeat: cpuPercent, memoryMb, runningTasks, disk*
                                     # nenhuma leitura de /proc/net/dev
```

É o caso de "campo gravado e nunca populado" — o mesmo padrão do teto de banda do rebalanceamento
(ciclo 54). A correção certa não é inventar uma tela nova: é o runtime medir o tráfego acumulado das
interfaces e mandar no heartbeat que já existe, para o campo que já existe.

## 4. Lacuna do §8.2 do brief que decorre disso

O brief (§8.2) pede, por nó: uso de rede e throughput de entrada e saída. Hoje:

| item do brief | estado |
|---|---|
| CPU total, memória, disco livre | **entregue** (heartbeat → painel por nó) |
| tarefas em execução, versão do runtime, último contato | **entregue** |
| uso de rede / throughput (entrada e saída) | **não medido pelo runtime**, coluna pronta no banco |
| swap, pressão de memória, IOPS, latência de disco, temperatura, GC, threads, p95/p99 de tarefa, DLQ | **swap e pressão desde o ciclo 157** (§7); **IOPS e latência de disco desde o 158** (§8 e §9); **temperatura desde o 159** (§10); **p95/p99 do tempo de execução das tarefas desde o 160** (§11). GC/threads (o runtime é shell, não JVM), p95/p99 de requisição HTTP e DLQ por fila seguem inexistentes — ver `docs/rede-distribuida/11-global-coop-fase-0.md` §3 |

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
| runtime | mede `/proc/net/dev` (fora loopback) e manda `bytesInMb`/`bytesOutMb` no heartbeat |
| backend | `GET /admin/network/nodes` devolve `trafficInMb`/`trafficOutMb` (antes: campo `NetworkNode` já populado, nunca exposto no `NodeView`) |
| design system | linha do nó mostra "Tráfego" (`↓ 2.0 GiB · ↑ 512 MiB`); nulo ou metade medida é "Sem medição" |

Provas: `NetworkParticipantTrafficTests` (2 casos novos no painel de admin — medido e nulo);
`network-nodes-section` do DS (2 casos: medido e ausência/metade).

**O que continua faltando do §8.2 do brief** (medido, não estimado): GC/threads do runtime (o
runtime não é JVM — é shell), p95/p99 de **requisição HTTP** (o `MonitoringService` mede a do
Control Plane, não a de cada nó), DLQ por fila. (Swap e pressão de memória entraram no ciclo 157 —
§7; IOPS e latência de disco, no ciclo 158 — §8 e §9; temperatura, no ciclo 159 — §10; p95/p99 do
tempo de execução das tarefas por nó, no ciclo 160 — §11.)

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
- **Swap e pressão: medidos e enviados, ainda não consumidos.** O runtime do ciclo 157 mede
  `swapUsedMb` (`SwapTotal - SwapFree`) e `memoryPressurePercent` (`avg300` do `some` em
  `/proc/pressure/memory`) e os manda no corpo do heartbeat, mas o Control Plane **ainda não os
  recebe** no contrato do `Observed` nem os guarda: hoje eles são descartados na borda. Quando
  entrarem, precisam de coluna própria na série e do quadro na página do nó — o campo não aparece
  no painel enquanto isso.

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
de 1 ms a ≈ 68 min) na hora corrente, em `network_task_latency_buckets` (V383). A escrita é um
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
`node-task-latency-migration-test` **14 PASS / 0 FAIL** em MariaDB 11.4 real: `V383` aplicada duas
vezes, nó/tarefa anteriores preservados, chave composta, `utf8mb4`, upsert somando na mesma linha,
janela de 24 h separando hoje de anteontem. Mutação (V383 vazia) derruba **6**.

### O tipo físico da coluna de temperatura (ciclo 160)

A coluna `observed_temperature_c` é `double` na V382 e `BigDecimal` na entidade. Sem
`columnDefinition = "double"` o H2 gera `numeric(38,2)` para o `BigDecimal` e a validação de schema
da suíte recusa a coluna que é `double` no banco: o app de teste não sobe (medido — 3 erros em
`RedeApplicationTests`). Com a anotação, o H2 e o dialect de produção emitem `double`, que é o tipo
que a V382 criou.

`NodeColumnTypeTests` prende essa decisão na régua de produção: gera o DDL da entidade com o dialect
que prd/qa/hml publicam e exige `double`. Mutação conferida — tirar o `columnDefinition` faz o teste
dizer `expected "double" but was "decimal"` nos três profiles.

**O que ficou em aberto, e por quê.** A #1143 atribuiu a queda do boot na `qa` à anotação, e o
relato não se reproduziu aqui: com a V305/V325/V343/V380/V382 aplicadas num **MariaDB 11.4 real**,
validar o mapeamento com o `MariaDBDialect` (metadados JDBC desligados, como em produção) passa com
**e sem** a anotação — porque o Hibernate casa o `BigDecimal` por tipo Java com a coluna `double`
antes de comparar o tipo do DDL lógico. A causa real do incidente segue não medida; o que está
provado é o que a suíte pode provar (H2) e o que aqui se mediu (MariaDB de sonda, não a `qa`): com a
anotação, nenhum dos dois lados recusa. A conversão da entidade para `Double` foi tentada e mexe na
conversão de leitura/escrita (`Incompatible types` na montagem do registro), portanto não entra de
carona neste ciclo.

