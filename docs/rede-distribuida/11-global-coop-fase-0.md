# Énóis Global Cooperative — fase 0: diagnóstico

O brief do Global Cooperative (`enois-global-coop-brief.txt`) abre exigindo investigação antes de
qualquer implementação e manda "preservar o que já funciona, reutilizar os mecanismos existentes,
não criar sistemas paralelos". Este documento é essa investigação. Descreve o código como ele está
no commit em que foi escrito — inclusive o que **não** existe e o brief supõe que exista.

## 1. O que já existe e serve ao cooperativismo global

| requisito do brief | o que existe hoje | reuso |
|---|---|---|
| §1 master como autoridade dos dados | o Control Plane é o único que escreve no MariaDB; nó auxiliar nunca escreve no banco principal (regra fixa em `docs/rede-distribuida/README.md` §2) | direto, sem mudança |
| §2 master como gateway distribuidor | `NetworkTaskService` já enfileira, escolhe nó por saúde/origem/região/folga/jitter e entrega por pull | direto |
| §3 malha cooperativa entre réplicas | sinais entre instâncias em `network_instance_signals` (V307): cada instância publica o que mudou e as outras leem; o que viaja é **id**, nunca conteúdo | direto, é a malha que existe |
| §5 operação durante indisponibilidade | modos `NORMAL`/`HIGH_LOAD` medidos e `DEGRADED`/`EMERGENCY`/`RECOVERY` declarados (ADR-009); drenagem sem perder tarefa em voo | direto |
| §6 rede como camada única | enroll, API key por nó, config assinada, heartbeat — o Storage vai usar os mesmos | direto |
| §7 provisionamento sem IP/hostname | token de uso único; o nó só faz chamadas de saída (pull), então funciona atrás de NAT | direto |
| §9 múltiplos domínios | região declarada por nó + estado de região fora de operação (ADR-015) + cobertura por região (ADR-016) | parcial: região existe, "domínio" com autoridade própria não |
| §12 modos operacionais | ADR-009; desde o ciclo 156 o modo `DEGRADED` também entra por **evidência medida** (captura exigida e parada, divergência de conteúdo), com a transição gravada — doc 06 §1.1 | direto |
| §13 governança | `AuditTrail` com ator, alvo e motivo; `AdminGuard` | direto |
| §3.7 / §4.3 divergência e reconciliação | `ContentReconcileService` / `ContentReconcileWorker`: o nó declara o que tem, o Control Plane confere contra a origem e grava `CONTENT_STALE`/`CONTENT_GONE` | direto para o **conteúdo**; não para tabelas arbitrárias |
| §3 change feed | `network_content_changes` (V283): cada mudança de conteúdo ganha versão e hash na **mesma transação** em que o corpo muda; o nó lê por cursor (`seq`) e recebe ids + versão + hash | é o alicerce real da sincronização entre nós |

## 2. O ponto crítico do brief e o que realmente existe

O §4 é declarado "prioridade crítica": **uma única conexão dedicada de captura de alterações do
MariaDB**, preferencialmente por **binlog** (CDC), alimentando a distribuição pela rede, com
posição de origem persistente, checkpoints duráveis, backpressure e retomada da última posição
confirmada.

**Isso não existe.** Medido no repositório:

- nenhuma classe, configuração ou dependência de CDC/binlog (a única menção a binlog é um comentário
  em `application.properties` linha 18, sobre `binlog_format ROW` exigido para o dado ser legível —
  não há leitor de binlog no código);
- não há tabela de posição de origem, checkpoint de réplica nem consumidor central de captura;
- o change feed do §3 **não é CDC**: ele não observa o banco, ele é escrito pela própria aplicação
  no momento da mudança (`ContentSyncService.publish`). Só cobre as tabelas de conteúdo que
  chamam esse serviço; uma alteração feita por SQL direto, migração ou outro módulo **não entra** no
  feed.

Consequência: o §4 é trabalho novo, não reuso. E ele tem uma consequência de operação que precisa
estar dita antes de alguém ligar isso em produção: ler binlog exige `log_bin` ligado e
`binlog_format=ROW` no MariaDB, e o usuário da captura precisa de `REPLICATION SLAVE`/`REPLICATION
CLIENT` — permissão que o usuário da aplicação **não deve** ter. Isso é decisão de infraestrutura
do mantenedor, não efeito colateral de código.

## 3. O que o brief pressupõe e não existe no código

| brief § | item | estado real |
|---|---|---|
| §4.1 | captura única por binlog, posição persistente | **inexistente** (ver §2) |
| §4.3 | aplicação idempotente de eventos de tabela nas réplicas com checkpoint transacional | **inexistente**. O que há é o cursor do change feed de conteúdo, que avança só depois de o corpo estar em disco no nó |
| §8 | observabilidade distribuída "de todos os nós" | **parcial**: heartbeat e saúde por nó existem (`degraded`, latência EWMA, sequência de falhas, quarentena), e o painel agrega; o que o heartbeat carrega do host cresceu (CPU, memória em uso e disponível, disco, tráfego acumulado das interfaces, desde o ciclo 161 — doc 20 §3 e §6; o runtime também mede e envia swap e pressão PSI, mas o Control Plane ainda não os consome); IOPS, latência de disco, temperatura, p95/p99 do tempo de execução (ciclo 160), DLQ por fila (ciclo 161) e retentativas por nó e na fila (ciclo 162 — doc 20 §11 e §11.1) estão entregues; threads do processo do nó entregue no ciclo 163 (V388, doc 20 §11.3); GC/heap não se aplicam (o runtime é shell) e faltam os itens de Gateway; o p50/p95/p99 de **requisição HTTP** do Control Plane existe desde o ciclo 163 com o tamanho da amostra (doc 20 §11.2) |
| §9 | múltiplos domínios com autoridade própria | **inexistente**. Existe região, que é ordenação de afinidade e estado fora de operação. Um segundo domínio com autoridade necessita de termo e quórum, que a F6 (ADR-013) já esboça para uma autoridade única |
| §11 | blockchain e consenso | **inexistente** e o brief não exige implementar agora; a autoridade com termo (ADR-013) é o que existe de mais próximo |

## 4. Ordem recomendada, por dependência real (não por número do brief)

1. **Observabilidade (§8)** — o que falta depende de dado que o nó já manda no heartbeat; é o menor
   passo e destrava os demais diagnósticos.
2. **CDC (§4)** — decisão de infraestrutura primeiro (ligar binlog, usuário de replicação), código
   depois. Sem essa decisão, qualquer implementação seria um segundo mecanismo sem credencial.
3. **Domínios (§9)** — só depois que o CDC existir, porque o domínio secundário precisa receber
   eventos de um domínio primário.

O Storage (fase 1) **não** depende de nada deste documento; os dois seguem em paralelo sem colidir,
porque o Storage acrescenta perfil e tipo de tarefa e o Cooperative mexe em captura e observação.
## 5. Observabilidade §8.4 entregue: a distribuição do trabalho

O §8.4 do brief pede o que nenhum painel respondia: *"identificar se a infraestrutura está realmente
distribuindo o processamento ou se o Master continua absorvendo trabalho que poderia ser executado
pelas réplicas"*. `GET /admin/network/nodes/dashboard` ganhou `load`:

| campo | o que diz |
|---|---|
| `nodes[]` | por nó: `nodeId`, `displayName`, `status`, `done`, `failed`, `runningNow` |
| `totalDone` / `totalFailed` | o agregado do catálogo |
| `unassigned` | concluídas sem nó no registro — entram no total, não em nó nenhum |
| `concentratedShare` | fatia (%) do nó que mais concluiu; **nula** quando não há conclusão |
| `singleNode` | a maior fatia é de um nó, com **2+ réplicas ativas** |

Decisões que os números carregam:

- **nó que nunca executou aparece com zero**, não é omitido: é o dado do nó ocioso, e omiti-lo
  faria "nó sem tarefa" e "nó inexistente" ficarem iguais;
- **`concentratedShare` nula sem conclusão**: 0% e 100% seriam lidos como "distribui igual" e "tudo
  num nó" para o mesmo caso vazio;
- **`singleNode` exige 2+ réplicas ativas**: com uma só, 100% é a única opção, não falta de
  distribuição — acusar aí culpava a topologia pelo que ela não podia fazer;
- **`runningNow` usa o campo cru** do heartbeat (`observedRunningTasks`), não o getter
  `runningTasks()` que converte nulo em zero: "não reportou" e "nenhuma rodando" são casos
  diferentes, e o painel não pode decidir por ele;
- **falhas definitivas** = `FAILED` + `DEAD`, que é a mesma falha depois de esgotar tentativas;
  reprocessamento não entra (a tarefa continua viva e será contada quando terminar).

A consulta `countDoneByNode` já existia no repositório **sem nenhum leitor**; esta entrega é o
primeiro uso dela, e ganhou a irmã `countFailedByNode`.

Provas: `NetworkDashboardDistributionTests` 8 e `NetworkNodesTests` 27 (1 novo pela rota). Mutações
conferidas: fatia nula→0, uma réplica contando como concentração, órfãs ignoradas, `runningNow` pelo
getter e falhas não contadas — cada uma derruba exatamente um teste. Suíte completa **3202**.

## 6. §8.2 entregue: o Storage no painel

`GET /admin/network/nodes/storage/overview` (admin) responde capacidade, uso, objetos e estado das
cópias. A regra do brief — "indicadores devem refletir dados efetivamente coletados, nunca valores
estimados" — virou três separações explícitas no contrato:

| campo | o que é de verdade |
|---|---|
| `capacityMb` | disco **declarado** pelos nós de armazenamento vivos (a promessa que o runtime impõe como teto) |
| `freeMb` | disco **livre medido** no heartbeat, somado. `null` quando **nenhum** nó mediu |
| `unmeasuredNodes` | quantos nós ficaram fora dessa soma |
| `usedBytes` | bytes das cópias **`OK`**, medidos pelo Control Plane ao receber o corpo |
| `objectsByState` / `copiesByState` | contagem por estado — `DELETED` ainda ocupa chave, não ocupa disco |
| `objectsUnderReplicated` + `replicasExpected` | a lacuna **com o alvo ao lado**, porque "3 abaixo" sem alvo não diz se é saudável |
| `objectsByDurability` | contagem por nível **efetivamente observado** (`SINGLE_DOMAIN`, `CROSS_REGION_PARTIAL`, `CROSS_REGION`) — ver [`13-storage-fase-3-replicacao.md`](13-storage-fase-3-replicacao.md) §2.1 |
| `buckets` + `usageByBucket` | quantos buckets têm objeto servível e quanto cada um ocupa (`{objects, bytes}`). Bucket não é tabela: existe enquanto tem objeto, então o número é o de chaves distintas no `group by` — uma consulta só, coberta pelo índice `(bucket, object_key)` da V319. **Os bytes são da versão corrente** (`superseded_by is null`, ciclo 99): a substituída continua no histórico e continua ocupando disco — o que ela não é é "o que o bucket guarda hoje". A contagem conta as duas, porque a chave existe no bucket nas duas |

Quem fica fora da conta, e por quê:

- **nó de processamento**: disco de transcodificação não é espaço de objeto (disco não dá
  permissão);
- **nó parado** (sem heartbeat há 180 s, o mesmo "vivo" do escalonador): não promete disco agora;
- **região evacuada**: decisão humana, e o escalonamento já recusa objeto novo ali;
- **cópia `PENDING_REMOTE`**: é obrigação, não byte em disco — somá-la inflaria o uso com algo que
  não existe.

O `usedBytes` vem de uma consulta de agregação em SQL (`sum(bytes) group by state`), não de varrer
o catálogo linha a linha; e a lacuna vem da **mesma** varredura que o operador usa em `/storage/scrub`
— dois números diferentes para "faltam cópias" seriam dois lugares para discordar.

Este endpoint é leitura de catálogo. Reler bytes custa disco de quem contribui e continua sendo
`scrub?readBodies=true`, opt-in.

Provas: `StorageOverviewTests` 8, `NetworkStorageBalanceTests` 5 (2 novos pela rota, com nó real
declarando 100 GB e reportando 4 GB). Mutações: disponível usando o declarado (derruba 4), sem
medida virando zero, perfil qualquer somando disco, uso incluindo designação, nó parado somando e
região evacuada somando — cada uma derruba o seu. Suíte completa **3212**.

**O disco na página do nó (§17).** `GET /admin/network/nodes/{id}/storage` (admin) devolve o `Disco`
daquele nó: `declaredMb` (espaço disponibilizado), `usedMb` (o que o Énóis ocupa, medido), `freeMb`
(disponível), `totalMb`, `reservedMb` e `usedPercent`. Antes isso só existia em
`/storage/overview` → `disks[]`, e o admin tinha de achar a linha do nó.

A conta é a **mesma**: o serviço usa o mesmo método que monta a linha do painel geral. Duas contas
para o mesmo disco dariam dois números na tela. Nó de processamento devolve corpo vazio (o mapa sem
campos), não erro — a página do nó existe, só não tem disco de armazenamento. Nó desconhecido é 404,
e a rota exige admin pelo `AdminRouteInterceptor`; o `admin()` no controller é defesa em
profundidade (mutação que o remove continua verde, e isso é o esperado).
**Nós online e offline (§17).** `onlineNodes`, `offlineNodes` e `offline[]` dizem quem está no ar.
Até aqui o nó sem heartbeat saía da soma (correto — parado não promete disco) **e do painel** (errado
— não havia como saber quem caiu). Agora ele aparece com `lastSeenAt` e `secondsSinceSeen`, e o disco
dele continua fora de toda soma: a mudança é de visibilidade, não de conta.

`lastSeenAt`/`secondsSinceSeen` são **nulos** para quem nunca bateu coração — é o nó recém-enrolado,
e "ausente" não é "há 0 segundos". Nó de processamento não entra na lista: o critério é o mesmo da
soma (`OBJECT_PUT.allowedFor`), não o nome do perfil.

**Bucket e lápide.** `buckets` conta objetos **servíveis**: o bucket que só tem lápide não aparece,
porque lápide não é conteúdo e o operador não "tem" aquele bucket. Objeto `PENDING` conta na
quantidade e soma **zero** bytes — ele existe e ainda não ocupa espaço medido. `Fotos` e `fotos` são
**dois** buckets: o bucket é a chave do catálogo, não texto livre de tela, e normalizar aqui juntaria
dois espaços de nomes.

O SQL é `group by bucket` numa consulta só, com índice, e é verificado contra o H2 em
`StorageCatalogBucketsPersistenceTests` — os testes do serviço usam mock e provariam a ligação, não
a consulta.
