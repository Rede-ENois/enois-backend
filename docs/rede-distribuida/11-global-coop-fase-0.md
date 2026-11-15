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
| §12 modos operacionais | ADR-009 | direto |
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
| §8 | observabilidade distribuída "de todos os nós" | **parcial**: heartbeat e saúde por nó existem (`degraded`, latência EWMA, sequência de falhas, quarentena), e o painel agrega; não há coleta de métricas de host nem do runtime além do que o heartbeat carrega |
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
