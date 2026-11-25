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
| swap, pressão de memória, IOPS, latência de disco, temperatura, GC, threads, p95/p99, DLQ | **swap e pressão medidos desde o ciclo 157** (§7 abaixo); o resto não existe — ver `docs/rede-distribribuida/11-global-coop-fase-0.md` §3 |

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

**O que continua faltando do §8.2 do brief** (medido, não estimado): IOPS, latência de disco,
temperatura, GC/threads do runtime (o runtime não é JVM — é shell), p95/p99 de requisição, DLQ por
fila. Nenhum desses existe no heartbeat nem no painel. (Swap e pressão de memória entraram no ciclo
157 — §7.)

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

### Agora (V378)

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
- **A coluna nova nasce nula no nó já registrado** — ninguém mediu o disponível dele ainda, e o
  próximo heartbeat preenche.

### Verificação

- `NetworkHeartbeatMemoryTests` **5** pela rota e pelo banco: uso e disponível distintos e gravados;
  teto só em 8192 de 8192, não em 8000; runtime antigo derivando 292 de 8192 de 7900 disponíveis;
  disponível acima do declarado não vira uso; zero não vira uso; sem memória não afirma nada.
- `node-executor-test` (**+4**, 223 asserções): o payload real do ciclo leva
  `memoryMb`, `memoryAvailableMb` e `swapUsedMb`, e o uso **não** é o mesmo número do disponível.
- `scripts/tests/node-memory-migration-test.sh` em **MariaDB 11.4 real**: **16 PASS / 0 FAIL** —
  V378 aplicada **duas vezes**; nó do contrato antigo preservado (`7900|NULL|2|8192|ACTIVE`); a
  coluna nova não recebe zero; o update do heartbeat grava as três colunas; a régua do teto confere
  no banco; tipo `int` e `NULL` aceito.
- Mutações: runtime mandando o disponível no lugar do uso (derruba 1 do harness); não derivar o
  runtime antigo (2); derivar disponível acima do declarado (1); zero virando uso (1); migração sem
  a coluna (9 falhas). Todas restauradas verdes.
