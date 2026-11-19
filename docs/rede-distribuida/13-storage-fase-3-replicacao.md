# Énóis Storage — fase 3: réplica, digest e varredura

Documento do que **existe** no código. A fase 1 (catálogo e rota de corpo) está em
[`12-storage-fase-1.md`](12-storage-fase-1.md).

## 1. O que está entregue

| item do brief (§ fase 3) | estado | onde está |
|---|---|---|
| **réplica** | entregue: o Control Plane designa nós e eles buscam o objeto na própria URL do CP | `StoragePlacementService`, `POST /network/storage/objects/{id}/replica` |
| **domínios de falha** | parcial: **nunca duas cópias no mesmo nó**; domínio maior que o nó (host físico, rack, operadora) não existe — ver §3 |
| **checksums** | entregue: o sha-256 medido no CP é a identidade da cópia; a confirmação da réplica é **conferida** contra ele | `StorageCatalogService.confirmReplica` |
| **scrubbing** | entregue: encontra objetos com menos cópias `OK` que o pedido e relê a **réplica 0** (tamanho + sha-256) — §4.1 | `StorageScrubber`, `GET /admin/network/nodes/storage/scrub` |
| **healing** | entregue como ato de operador (`POST /admin/network/nodes/storage/heal`), sem agendador — §5.1 | `StorageHealer` |
| **corpo servível do disco local** | entregue: a raiz do Control Plane e o diretório do nó local — §5.5 | `StorageCatalogService.readBody`, `NodeBodyStore` |
| **inventário do disco do nó** | entregue: o que o nó tem contra o que o catálogo promete, com poda só de órfão — §5.6 | `NodeStorageInventory`, `GET /network/storage/inventory` |
| **recuperação de objetos** | entregue no sentido de "buscar por conteúdo": `GET /network/storage/objects/by-digest/{sha256}` serve à réplica que conhece o digest e tem a cópia `OK` | `NetworkStorageController` |

## 2. Decisões que valem por si

**Designação não é durabilidade.** A linha nasce `PENDING_REMOTE`: o Control Plane disse "este nó
deve ter o objeto" e o nó ainda não tem. Só a confirmação — com bytes e sha-256 conferidos contra o
medido — vira `OK`. A varredura conta só `OK`. Um painel que contasse linhas diria que o objeto está
em três lugares quando está em um.

**A divergência sobrevive ao erro.** Se a réplica confirma com digest diferente do medido, a linha
vira `STALE` **e** a resposta é 409. Por isso `confirmReplica` não é transacional: com a confirmação
dentro de uma transação, o rollback do 409 desfazia a marcação e o nó que gravou lixo nunca aparecia
como `STALE` — a divergência ficava invisível justamente no registro dela.

**A réplica referencia pelo digest, não pelo id.** O id lógico (`sob_…`) vive no Control Plane; o nó
conhece o arquivo pelo sha-256. O download por digest só serve a quem tem a cópia `OK` — "tem a
linha" e "tem os bytes" são coisas diferentes. O nó busca na URL do Control Plane com a própria
chave: **nenhum IP ou hostname de storage node** é cadastrado (brief §17).

**A escolha é estável.** Ordem pela folga **cedida** (`declarado − ocupado`) e desempate por id — ver
[`17-storage-fase-4-dominios-e-equilibrio.md`](17-storage-fase-4-dominios-e-equilibrio.md) §2.1. O
mesmo objeto, reenviado, escolhe o mesmo conjunto enquanto a rede não mudar; um conjunto que mudasse
a cada retry espalharia réplicas e deixaria cópias órfãs.

**Nó sem teto cedido não recebe cópia.** Ceder é o número que só o participante pode dar; sem ele não
há limite a respeitar (brief §7) e a colocação não designa. O disco livre medido continua sendo régua
junto: ceder 50 GB num disco com 500 MB não é espaço.

## 2.1 Durabilidade efetiva (brief §9)

`StorageScrubber.durability` classifica cada objeto **servível** pelas cópias `OK`, olhando o
**domínio de falha** e não a contagem:

| nível | quando | o que significa |
|---|---|---|
| `SINGLE_DOMAIN` | todas as cópias no mesmo domínio (inclusive uma só) | perder aquele nó/região é perder o objeto |
| `CROSS_REGION_PARTIAL` | domínios distintos, cópias abaixo do alvo | sobrevive à queda de um domínio, não cumpre a política |
| `CROSS_REGION` | domínios distintos e cópias suficientes | é o que `rede.storage.replicas` pede |

**Por que a contagem não basta:** duas cópias na mesma região e duas em regiões distintas têm o
mesmo número e durabilidade diferente. O caso do §9 ("se existir somente um storage node ... sem
redundância externa") é `SINGLE_DOMAIN`, e agora aparece como tal em vez de "2 de 2 cópias".

A **réplica 0** é domínio próprio (o Control Plane): uma cópia no CP sobrevive à queda de qualquer
storage node, e uma no nó sobrevive à queda do CP. Nó **sem região** é domínio desconhecido e não se
funde com outro desconhecido (regra de `NetworkRegions`); nó fora do cadastro conta como desconhecido
e distinto — a rede sabe que ele tem cópia, não que ele está onde outro está.

O painel (`GET /admin/network/nodes/storage/overview`) devolve `objectsByDurability`, a contagem de
objetos por nível **efetivamente observado** — nunca a política pedida.

## 2.2 Replicação automática (`StorageReplicationWorker`)

Ligado por `REDE_STORAGE_REPLICATION_AUTO_HEAL_ENABLED=true`, o worker roda a cada `delay-ms` (15 min
de padrão) e faz uma passada:

1. **solta as designações vencidas** — `PENDING_REMOTE` sem confirmação por
   `rede.storage.replication.pending-max-age-minutes` (60) vira `GONE`. Antes, essa pendência durava
   para sempre e o healer a lia como "a lacuna tem dono" (`ALREADY_DESIGNATED`): o operador não
   conseguia curar aquele objeto nem manualmente, e a cópia "a caminho" nunca chegava. A idade é a de
   `verified_at`, que `savePlacement` grava como o instante da designação;
2. **cura** até 25 objetos por passada pelo mesmo `StorageHealer` (mesma colocação, mesmo teto de lote);
3. **despacha** cada objeto designado pelo `StorageReplicationDispatcher` — uma tarefa `OBJECT_PUT`
   por colocação, idempotente por `storage:<objectId>:<replica>`.

Ele **não** grava corpo, **não** apaga e **não** confirma cópia: a confirmação continua sendo do nó,
com bytes e sha-256 conferidos. `GONE` não é apagado — o histórico mostra que houve uma tentativa, e
o scrub segue contando a linha como réplica existente (o que ela é).

## 3. O que NÃO está entregue (e por quê)

| item | por que não |
|---|---|
| **healing automático ligado de fábrica** | o agendador **existe** (`StorageReplicationWorker`), mas nasce **desligado** (`rede.storage.replication.auto-heal-enabled`, padrão `false`): ligar é decisão de quem opera a rede, porque designar gera tráfego de corpo no disco e na banda do participante. Desligado, o comportamento é o de antes — só o ato de operador |
| **scrubbing que relê o corpo de OUTRO nó** | o Control Plane não inicia conexão para o nó (NAT e a proibição de cadastrar endereço). O que existe hoje é a releitura do que está **neste** CP: `GET /admin/network/nodes/storage/scrub?readBodies=true` compara tamanho e sha-256 do arquivo com o medido e devolve `divergent[]` — ver §4.1 |
| **domínio de falha maior que o nó** | o cadastro não tem host físico, rack nem operadora. Derivar isso de região/cidade seria deduzir topologia. "Mesmo nó" é o que o dado permite afirmar |
| **erasure coding** | da fase 4 do brief (§ distribuição inteligente), não da 3 |
| **via de entrega ao par (push do CP para o nó)** | o nó **puxa** o objeto na URL do CP, e o CP não inicia conexão para o nó — pelo mesmo motivo do §17: o CP não conhece o endereço do nó |

## 4. Verificação

| verificação | resultado |
|---|---|
| suíte completa | 3111 testes (R123) |
| `StoragePlacementServiceTests` (novo) | 8 — folga, não-repetição, nó pronto, disco, estável |
| `NetworkStorageReplicationTests` (novo) | 6 — designação ≠ durabilidade, confirmação conferida, digest, varredura |
| `StorageReplicationWorkerTests` (novo) | 3 — designação vencida vira `GONE` e volta a ser curável (com despacho), designação recente **não** é solta, desligado não toca em nada |
| `storage-migration-test` | **29** asserções em MariaDB 11.4 real, migrações 2× com dados |

Mutações conferidas: confirmação sem conferir digest derruba `designacaoNaoContaComoDurabilidade…`;
aceitar `PENDING_REMOTE` como confirmável derruba o mesmo teste; varredura contando
`PENDING_REMOTE` como cópia derruba `varreduraApontaObjetoComMenosCopiasQueOPedido`. No worker:
soltar toda `PENDING_REMOTE` sem olhar a idade derruba `designacao_recente_nao_e_solta`.

## 5. Migração

`V323__network_storage_placement_pending_sha.sql` — `sha256` passa a aceitar nulo, porque a
designação existe antes de haver cópia. Aditiva: nenhuma linha existente muda.

## 4.1. Releitura do corpo (implementada)

`GET /admin/network/nodes/storage/scrub?readBodies=true` vai além de contar linhas: lê de novo o
arquivo da **réplica 0** — a cópia que este Control Plane hospeda na própria raiz — e compara
**tamanho e sha-256** com o medido que o catálogo guarda. Cópia de outro nó não é relida daqui, e
por isso também não é acusada (§5.5). O relatório ganhou `bodiesRelidos` e `divergent[]` (objeto, réplica,
nó, digest esperado, digest encontrado); divergência é auditada como `STORAGE_SCRUB_DIVERGENT`.

Divergência é coisa diferente de lacuna: `underReplicated` diz "falta cópia", `divergent` diz "a
cópia que o catálogo promete existe e não confere". Um objeto ainda pode ser servido pela cópia
certa; o que não pode é o catálogo prometer durabilidade para um arquivo trocado.

O parâmetro é **opt-in**: reler disco de quem contribui é custo, e a contagem periódica não deve
carregá-lo por efeito colateral. Três casos têm asserção — corpo trocado com o mesmo tamanho
(comparação de comprimento passaria), corpo ausente (digest vazio = "não consegui ler", nunca um
digest calculado) e corpo intacto (relido, não reportado).

Provas: `StorageScrubberReadBackTests` 4 e `NetworkStorageReplicationTests` 7 (1 novo pela rota
admin). Mutações: aceitar divergência, comparar só o tamanho e ignorar corpo ausente — cada uma
derruba exatamente um teste.

## 5.1 Healing: a lacuna que a varredura aponta tem quem a feche

Até aqui a varredura **apontava** a lacuna e nada a fechava. O defeito era de distância entre dois
serviços: `designReplicas` só roda **no instante do upload**. Objeto que ficou sem cópia — cluster
sem nó de armazenamento vivo naquele momento, réplica confirmada `STALE`, ou designação que nunca
foi puxada — ficava `underReplicated` **para sempre**, visível em `/storage/scrub` e sem chamador.

`POST /admin/network/nodes/storage/heal?bucket=&maxObjects=100&dryRun=false` (admin) lê a **mesma**
varredura do operador e, para cada lacuna, grava a designação `PENDING_REMOTE` num nó escolhido.
O nó continua sendo quem busca o corpo (`GET /network/storage/objects/{id}`), quem grava e quem
confirma (`POST .../replica`) — nada disso mudou.

| o que o healing não faz | por quê |
|---|---|
| reler corpo de nó | a origem confiável é a réplica 0 ou uma `OK`; uma `STALE` pode ter conteúdo trocado. `staleCopiesIgnored`/`STALE_COPIES_ONLY` separa "não há cópia boa" de "não há nó" — são pedidos de cura diferentes |
| escolher réplica | a diversidade de região é a de `StoragePlacementService`, a mesma do upload. Duas regras de colocação seriam duas chances de acreditar numa redundância inexistente |
| marcar a cópia como `OK` | a única prova de que a cópia existe é a confirmação do nó com os bytes/sha que ele mediu. Um healer que declarasse pronto seria a mesma mentira que o contrato evita |
| rodar sozinho | cada designação é tráfego de corpo. `dryRun=true` mostra o que seria feito sem gravar |

Motivos de `skipped[]`: `ALREADY_DESIGNATED` (a lacuna já tem dono esperando confirmação — designar
de novo só reinicia o relógio do retry), `NO_CANDIDATE` (nenhum nó livre com perfil, disco e região),
`STALE_COPIES_ONLY` (as cópias restantes não conferem) e `BODY_MISSING` (o objeto foi lapidado entre
a varredura e a cura, ou o CP não tem o arquivo).

O número da réplica continua depois do **maior existente**, nunca do 1: a chave é
`(object_id, replica)`, e reaproveitar um número sobrescreveria a cópia antiga de outro nó.

Cada objeto é uma transação própria: um nó que entra em quarentena no meio do lote não desfaz as
designações anteriores, que já são válidas.

### 5.1.1 A cópia que não confere volta a ser espaço

**Antes:** a cura só andava em `underReplicated`. A cópia `STALE` — o objeto existe e o conteúdo não
é o prometido — ficava assim **para sempre**: a cura a ignorava, ela continuava ocupando o espaço
cedido em `StoragePlacementService`, e cada rodada designava mais uma réplica ao lado da queimada.
A tela dizia "existe e não confere" e nada acontecia.

**Agora** a cura olha as duas testemunhas de divergência:

| testemunha | o que enxerga |
|---|---|
| `divergent[]` da varredura com releitura | o disco **desta** instalação: réplica 0 e o diretório do nó local |
| cópias `STALE` do catálogo | a cópia de **qualquer** nó que o próprio nó marcou (`409` na confirmação) ou que o nó reportou em `/network/storage/scrub` |

A segunda é a que fecha o buraco quando o Control Plane não hospeda corpo nenhum (é o caso da
instalação que não tem réplica 0): a varredura com releitura não tem o que comparar, `divergent`
volta vazio para sempre, e só o estado da colocação conta a história. As duas listas se somam sem
repetir (a chave é `objectId#replica`).

O que a cura faz com cada uma:

- **réplica 0 divergente** → **nada é apagado**. É o corpo do próprio Control Plane; reler em
  caminho de cura não pode escrever por cima nem marcar como perdida a única cópia que se sabe boa.
  Fica auditado (`STORAGE_REPLICA_ZERO_DIVERGENT`) e o relatório devolve `REPLICA_ZERO`;
- **cópia de nó** → vai para `GONE` (`STORAGE_REPLICA_REPLACED`), o que **devolve o espaço** na conta
  de folga cedida; a rodada seguinte designa uma réplica nova (número depois da maior) e o nó puxa
  o corpo do CP e confirma com o digest. O arquivo velho no disco dele deixa de estar atribuído a
  ele e a poda do nó (`/network/storage/inventory/purge`) o recolhe — a cura não apaga disco de
  terceiro.

O relatório ganhou `repostos[]` (`objectId`, `replica`, `nodeId`, `action`), porque a cura que
tratou uma cópia e a cura que não achou nada merecem respostas diferentes na tela de quem opera.

**Verificação:** `StorageHealerTests` **13** e `NetworkStorageHealTests` **5**. As asserções novas
são discriminantes — mutações: não olhar as `STALE` do catálogo, marcar `OK` em vez de `GONE`, tratar
a réplica 0 como as demais, varrer sem reler o disco — **4 de 4 derrubam** o teste correspondente.

## 5.2 O `OBJECT_PUT` tem produtor, e o `DONE` dele é conferido

Duas metades que não se conheciam:

1. **ninguém enfileirava `OBJECT_PUT`.** O tipo existia, exigia `NODE_STORAGE` e aparecia na config
   assinada — mas nenhum serviço criava a tarefa. O healing designava e parava ali.
2. **o Control Plane aceitava qualquer `DONE`.** O `*)` do `deploy/node-runtime/node-runtime.sh`
   responde `DONE` com um eco do `idempotencyKey` para todo tipo **sem executor** — inclusive
   `OBJECT_PUT`. A tarefa de réplica terminava "com sucesso" sem nada gravado: a colocação seguia
   `PENDING_REMOTE`, o `/storage/scrub` seguia apontando a lacuna e o painel mostrava a tarefa
   concluída. Verde por fora, durabilidade nenhuma.

### O produtor

`StorageReplicationDispatcher` transforma cada colocação `PENDING_REMOTE` num `OBJECT_PUT` com
`targetNodeId` = o nó da colocação (a réplica não pode rodar em nó que não é dono da linha), chave
`storage:<objectId>:<replica>` e payload `{objectId, bucket, key, sha256, bytes, replica}`.
Chamado por `POST /admin/network/nodes/storage/objects/{id}/dispatch` e por
`POST /admin/network/nodes/storage/heal?dispatch=true`. Não designa: já chama `dispatchPending()` na
mesma transação, senão a tarefa esperaria a próxima varredura periódica.

A **classe de dado é `PUBLIC_MEDIA`**, a mesma do transcode e a do conteúdo que a tarefa descreve.
`INTERNAL` seria errado no sentido oposto: a regra dura manda dado que não pode sair da infra para
nó `NODE_LOCAL`, e o Storage vive num participante `NODE_STORAGE` — a tarefa ficaria parada na fila
para sempre, sem erro, filtrada pela classe.

### O conferente

`StorageResultVerifier` (`StorageResultVerifierAdapter`) recusa o `DONE` de `OBJECT_PUT` quando:

| situação | status | retentável |
|---|---|---|
| sem cópia `OK` **deste** nó (inclui `PENDING_REMOTE` e `STALE`, e a cópia existir só em outro nó) | `STORAGE_REPLICA_NOT_CONFIRMED` | sim |
| `bytes` declarados diferentes do medido pelo CP | `STORAGE_REPLICA_MEASURE_MISMATCH` | não |
| `sha256` declarado diferente do medido pelo CP | `STORAGE_REPLICA_MEASURE_MISMATCH` | não |
| payload sem `objectId` | `STORAGE_TASK_PAYLOAD_INVALID` | não |

Os campos de medida no `output` são **opcionais**: exigir seria criar um segundo contrato de
confirmação ao lado do `POST .../replica`, que já é o contrato. O que se recusa é a **divergência**
— número declarado que o catálogo contradiz é afirmação falsa.

Com isto o eco do runtime passa a ser recusado pelo motivo certo, e a tarefa **continua na fila**
(recusa retentável), em vez de morrer com sucesso falso. Enquanto o executor de Storage no runtime
não existir, é este estado correto que se vê: réplica pendente, tarefa voltando, painel honesto.

**Verificação:** `StorageResultVerifierTests` 8 e `NetworkStorageResultAcceptanceTests` 4 (caminho
real: tarefa designada, nó posta o eco do runtime, `applied=false`; depois o nó confirma pelo
`POST .../replica` e o mesmo `DONE` é aceito). Mutação "sem conferência de `OBJECT_PUT`" (o código
anterior) derruba 2.

## 5.3 O runtime executa `OBJECT_PUT`

O `*)` do `deploy/node-runtime/node-runtime.sh` respondia `DONE` com um eco do `idempotencyKey`
para todo tipo **sem executor** — inclusive `OBJECT_PUT`. Com o conferente do §5.2 o eco passou a
ser recusado (o que já era correto), mas a réplica nunca acontecia. Agora existe `exec_object_put`:

| passo | o que faz |
|---|---|
| 1 | lê `objectId`, `sha256`, `bytes` e `replica` do payload; payload incompleto → `STORAGE_TASK_PAYLOAD_INVALID` |
| 2 | baixa por `GET /network/storage/objects/{id}` com a **mesma** chave de nó e `--limit-rate` do **menor** entre o teto da transferência (`bandwidthLimitMbps` do payload, quando existe) e a banda declarada |
| 3 | **mede o que veio** (`sha256sum` + `stat`) e compara com o medido pelo Control Plane. Divergência → `STORAGE_REPLICA_MISMATCH`, e **não confirma** |
| 4 | confere o teto de disco cedido **antes** de confirmar. Acima → `STORAGE_OBJECT_TOO_LARGE` (o temporário sai; `diskMb=0` é "sem teto declarado", não "zero bytes") |
| 5 | confirma em `POST .../replica` com **as medidas que ele mesmo calculou** |
| 6 | posta `DONE` com `{objectId, replica, bytes, sha256}` — que o Control Plane confere de novo |

O corpo **não** fica em `$NODE_HOME/replica`: `disk_make_room` trata `replica/` como promessa não
evictável, e quem decide o que o nó hospeda é o catálogo por colocação. Guardar ali criaria duas
verdades sobre a mesma cópia. Este executor baixa, confere e confirma; retenção local entra quando
houver política de cache que o Control Plane consiga reconciliar. Como o arquivo temporário sai
logo após a confirmação, o nó não acumula conteúdo sem política — e não enche o disco de quem
contribui.

O `--silent` no `curl` do download não é decoração: sem ele o curl copia o corpo para a saída padrão
e o painel do nó receberia **bytes de conteúdo de usuário** como se fossem linha de log.

**Verificação:** 9 asserções novas em `scripts/tests/node-executor-test.sh` (177 → **186**):
caminho feliz (baixa → confere → confirma → `DONE` com o medido, e **sem** o eco), corpo divergente
(não confirma), e teto de disco menor que o objeto (não confirma). Mutações: sem a entrada no `case`
(volta ao eco), confiar no sha do payload, e confirmar sem checar o teto — cada uma derruba a sua.

## 5.4 O nó guarda o corpo no próprio disco

O §5.3 baixava, conferia, confirmava e **apagava**. O catálogo dizia "cópia `OK` neste nó" e o
arquivo não existia em lugar nenhum — a durabilidade era uma linha de banco. Agora o corpo fica.

**Onde:** `$NODE_HOME/objects/<objectId>.bin`, diretório próprio.

Por que não `$NODE_HOME/replica/`: `disk_make_room` trata `replica/` como **promessa não evictável**
e a poda dela é decisão do Control Plane. Um corpo de Storage no mesmo lugar confundiria duas
políticas de retenção — a do cache de projeção e a da colocação de objeto.

**Quando vira corpo:** depois da confirmação. O arquivo baixa como `objects/object-put.*` e só ganha
o nome final com `mv` quando o CP aceitou a cópia. Um processo morto entre as duas etapas deixa um
`.partial` — nunca um corpo servível que o catálogo não conhece. O `.partial` é varrido no ciclo
seguinte.

**O teto declarado conta.** `content_bytes` passou a somar `objects/*.bin`: sem isso o nó reportava
2 MB de uso enquanto guardava 40 GB de réplica local, e o teto que o participante cedeu não valia
nada.

**No Control Plane:** `NodeBodyStore` (`@ConditionalOnProperty rede.storage.node-body-root`) é a
mesma ideia do lado servidor — gravação atômica (temporário + `ATOMIC_MOVE`), validação de
`objectId` contra `..` e `/`, e `usedBytes()` que conta **só** `.bin` (temporário e lixo não inflam
o medido). Vazio = o processo não guarda corpo de nó, que é o caso do Control Plane puro e da suíte.

**`NODE_LOCAL` conta no painel.** `StorageOverviewNodes` filtrava por **nome** do perfil
`NODE_STORAGE`; o nó da infraestrutura própria (`NODE_LOCAL`) guarda objeto e cobria todos os
tipos, mas ficava fora da soma. O painel mostrava menos capacidade do que a rede tem e o operador
decidia colocação sobre um número que excluía as réplicas locais. O critério agora é o mesmo do
despacho: `OBJECT_PUT.allowedFor(profiles)`.

**Verificação:** `NodeBodyStoreTests` 7 (atômico, `..` não escapa, uso só de `.bin`, remoção
idempotente); `StorageOverviewTests` 9 (1 novo: nó local soma). Harness: 189 asserções, 3 novas.
Mutações: apagar após confirmar, `objects/` fora do `content_bytes`, escrita não atômica, id sem
validação, uso contando qualquer arquivo, filtro por nome de perfil — 6 de 6 derrubam.

## 5.5 O Control Plane serve o corpo do disco local

`GET /network/storage/objects/{objectId}` (e o `by-digest`) respondia **só** da raiz do Control
Plane (`rede.storage.root`). Objeto replicado para o nó que roda na mesma máquina respondia
`409 STORAGE_REPLICA_ELSEWHERE` mesmo com os bytes a um diretório de distância: o "guardar no
próprio disco" do §5.4 gravava, mas ninguém lia.

**A ordem de leitura** (`StorageCatalogService.readBody`):

1. `rede.storage.root/<objectId>` — a réplica 0, gravada pelo upload;
2. `<rede.storage.node-body-root>/<objectId>.bin` — o corpo do nó local, gravado pelo `OBJECT_PUT`;
3. nenhum dos dois: `409 STORAGE_REPLICA_ELSEWHERE` com o digest, como antes.

A raiz do CP vem primeiro porque é a réplica 0 — o arquivo que o upload mediu. O diretório do nó é
o mesmo `objects/` do §5.4; `rede.storage.node-body-root` aponta para ele. Sem a propriedade, o
processo não lê esse diretório (Control Plane puro e suíte). Propriedade **vazia** falha no boot:
`@ConditionalOnProperty` casa com presente-e-vazia, e sem a recusa `Path.of("")` gravaria no
diretório de trabalho.

**Tamanho conferido antes de servir.** O download responde com o `Content-Length` medido no upload.
Se o arquivo local tem outro tamanho, a resposta é `410 STORAGE_BODY_SIZE_MISMATCH` com os dois
números — um 200 com comprimento errado se rompe no fim, e o cliente não sabe se perdeu bytes da
rede ou do disco. Corpo do mesmo tamanho com outro conteúdo é o que a varredura encontra pelo
sha-256; o caminho de download não paga uma leitura inteira para descobrir.

**A varredura relê a réplica 0, não todas as cópias.** Antes ela percorria cada cópia `OK` e relia o
**mesmo** arquivo local uma vez por colocação: com a raiz vazia, acusava de divergente a cópia de
outro nó que está íntegra, e `bodiesRelidos` contava N leituras de um arquivo só. E, desde que o
corpo do nó local passou a ser servido, reler por `readBody` esconderia o sumiço da réplica 0 atrás
de um corpo que o catálogo não atribui a esta instalação. Agora `openReplicaZeroBody` não tem
fallback, e a divergência é atribuída à réplica 0 — a única que este processo pode reler.

**O que continua fora:** reler o corpo do nó local na varredura. O catálogo não diz qual id de nó
roda nesta máquina, então a cópia do diretório local não tem colocação a que atribuir a divergência.
Quem confere a cópia na gravação é o `confirmReplica` (bytes + sha-256 do que o nó mediu).

**A leitura não cria diretório.** `readBody` resolve `rede.storage.root` direto, sem o
`createDirectories` do `root()`: um GET num Control Plane que nunca recebeu upload não escreve em
disco, e uma falha de disco responde 409/410 em vez de virar 500 num download.

**Verificação:** `StorageLocalBodyServingTests` 7 (serve do nó local; 409 sem corpo; raiz do CP tem
prioridade; truncado é 410; sem nó local lê a raiz; réplica 0 não cai para o nó; leitura não cria o
diretório);
`StorageScrubberReadBackTests` 5 (1 novo: sumiço da réplica 0 não é escondido e a cópia de outro nó
não é acusada); `NetworkStorageReplicationTests` 8 (1 novo pela rota real). Mutações: sem fallback,
ordem invertida, sem conferência de tamanho, réplica 0 com fallback, varredura relendo todas as
cópias, `readBody` voltando a chamar `bodyPath` — 6 de 6 derrubam.

## 5.6 Inventário do disco do nó: o que ele tem contra o que o catálogo promete

O §5.4 fez o nó guardar, e o §5.5 fez o Control Plane servir do disco local. Faltava a pergunta que
liga os dois: **o que está de fato no disco deste nó, comparado com o que o catálogo diz que ele
deve ter?** Sem ela, três coisas ficavam invisíveis — colocação removida deixava o arquivo para
sempre no disco de quem contribui, a varredura só relia a réplica 0, e arquivo trocado no diretório
do nó não aparecia em relatório nenhum.

### Quem é "este nó"

O dono do disco sai das credenciais que o runtime **já** grava: `$NODE_HOME/node.json` (o id) e
`$NODE_HOME/api-key`. Não existe propriedade paralela `rede.storage.node-id`: dois lugares dizendo
de quem é o disco, e o segundo é o que ninguém atualiza. Quando `rede.storage.node-body-root`
aponta para `$NODE_HOME/objects`, as credenciais são lidas no pai; apontando para `$NODE_HOME`
direto, nele mesmo.

`api-key` legível por **grupo ou outros** anula o nó: `node.json` sozinho não prova nada, e uma
credencial legível por outro usuário é o que um atacante lê para se passar pelo nó. `node.json`
ilegível, id fora de `[A-Za-z0-9_-]{1,64}` ou credencial ausente também anulam.

### As rotas

| rota | o que devolve |
|---|---|
| `GET /network/storage/inventory` | `held`, `missing`, `divergent`, `orphan`, `orphanBytes`, `heldBytes` |
| `POST /network/storage/inventory/purge?dryRun=` | `removed`, `kept`, `freedBytes` |

As duas exigem credencial de nó (`X-Node-Id` + `X-Node-Key`) e o perfil de armazenamento. O serviço
ainda confere que **o nó autenticado é o dono do disco** — token válido de outro nó responde 403,
não lê o disco desta máquina.

### Órfão, ausente e divergente são coisas diferentes

| campo | significado | o que se faz |
|---|---|---|
| `orphan` | arquivo no disco **sem colocação** para este nó | poda pode apagar (é o único caso) |
| `missing` | colocado e **ausente** do disco; `state` diz se era `OK` (perda) ou `PENDING_REMOTE` (a tarefa ainda não rodou) | re-replicar / esperar |
| `divergent` | no disco e o sha-256 **não confere** | re-replicar — **nunca** apagar aqui |

A poda **só** apaga órfão. Cópia divergente é a única cópia possivelmente boa do objeto: apagá-la
antes de o Control Plane re-replicar transformaria divergência em perda. E `dryRun` é o padrão: o
operador vê o número antes de liberar disco de quem contribui.

`orphanBytes` é medido, não estimado. Sem instalação (`node-body-root` ausente ou sem credencial) a
rota responde `409 STORAGE_NODE_BODY_DISABLED` em vez de relatório vazio — vazio diria "nada falta,
nada sobra", que é uma afirmação.

`purge` é `POST` e por isso está na lista explícita de `WriteRouteInterceptor`: o nó não tem sessão
de navegador, e a credencial é conferida no controller (fail-closed, como `objects/*/replica`).

### O que NÃO está entregue

| item | por quê |
|---|---|
| o Control Plane **ler** o inventário de outro nó | o CP não inicia conexão para o nó (NAT; o brief proíbe cadastrar endereço). Quem chama é o nó, na própria URL do CP, e é o CP que responde |
| ~~a varredura reler o corpo do nó local~~ | entregue no §5.7 |
| conferir o disco de **outro** nó | o nó devolve o que mediu no próprio disco — §5.8 |
| poda automática por colocação | apagar disco de quem contribui é ato de operador, com o número na mão (§5.1, mesmo critério do healing) |

**Verificação:** `NodeStorageInventoryTests` **11** (conferido, ausente, designação pendente com
estado, divergente, órfão medido, poda seca, poda só de órfão, divergente preservado, outro nó não lê,
credencial legível anula o nó, sem instalação é 409) e `NetworkStorageInventoryTests` **4** pela
rota real (órfão + poda seca, poda real preservando o colocado, outro nó 403, sem chave 401).
Mutações: poda apagando divergente, sem checagem de dono, órfão incluindo o colocado, `dryRun`
ignorado, digest não conferido, credencial legível aceita, purge fora do interceptor — **7 de 7
derrubam**.

## 5.7 A varredura relê também o corpo do nó local

O §5.6 fez o processo saber de quem é o disco (`NodeBodyStore.ownerNodeId()`). Antes disso o
`StorageScrubber` só relia a réplica 0, e isso deixava um ponto cego: objeto replicado para o nó
local podia ser **trocado no disco dele** e a varredura continuava dizendo "conferido". O catálogo
prometia uma durabilidade que o arquivo não tinha.

Agora a varredura percorre as cópias confirmadas e relê **só** as que estão no disco desta
instalação:

| cópia | relida? | por quê |
|---|---|---|
| réplica 0 (raiz do Control Plane) | sim, por `openReplicaZeroBody` | é o arquivo do upload, nesta máquina |
| colocação cujo `nodeId` é o dono deste disco | sim, por `openNodeBody` | o diretório do nó local é local |
| qualquer outro nó | **não** | o CP não inicia conexão para o nó (NAT; o brief proíbe cadastrar endereço) |

O `openNodeBody` é simétrico ao `openReplicaZeroBody`: abre **só** o arquivo do diretório do nó
(`rede.storage.node-body-root`), **sem** cair para a raiz do CP. Sem essa separação, um corpo da
raiz esconderia a troca no diretório do nó — o mesmo sumiço-da-réplica-0 que o §5.5 já tinha
fechado do outro lado.

`bodiesRelidos` conta **uma leitura por cópia relida**. Antes o laço percorria todas as cópias OK e
relia o mesmo arquivo local para cada uma, contando N leituras de um arquivo só; e com a raiz
vazia acusava de divergente a cópia de outro nó que estava íntegra.

Quando este processo **não** guarda corpo de nó (`node-body-root` ausente), o `NodeBodyStore` é
nulo, `ownerNodeId()` não entra na conta e a varredura relê só a réplica 0 — comportamento
declarado, não uma dedução.

**Verificação:** `StorageScrubberReadBackTests` **8**, sendo **3 novos**: corpo do nó local com
digest diferente é reportado com a `replica`/`nodeId` certos; corpo do nó local intacto não vira
acusação; cópia de **outro** nó não é relida como se fosse deste disco. Mutações: nunca reler o
corpo do nó local (volta ao ponto cego), reler qualquer cópia como local, `openNodeBody` caindo para
a raiz do CP, dono do disco ignorado, contagem de `bodiesRelidos` sem a cópia do nó — **5 de 5
derrubam**.

## 5.8 O nó confere o próprio disco e devolve o que não confere

O §5.7 fechou a releitura do disco da instalação em que o Control Plane roda. Ficava de fora a
cópia de **todo outro nó**: ela está num disco que o Control Plane não alcança (NAT; o brief proíbe
cadastrar endereço), e a varredura do CP jamais a conferia. Um arquivo trocado lá continuava
contando como durabilidade.

Quem tem acesso ao disco é o dono dele. Agora o nó relê as próprias cópias e devolve o que mediu:

| rota | corpo | devolve |
|---|---|---|
| `POST /network/storage/scrub` | `[{ "objectId", "actualSha256" }]` | `{ "nodeId", "stale" }` |

Credencial de nó (`X-Node-Id` + `X-Node-Key`) e perfil de armazenamento, como as demais rotas de
Storage do nó. A rota está na lista explícita do `WriteRouteInterceptor` (o nó não tem sessão de
navegador) e o `AutomatedWritePathsTests` a conhece.

### O que o Control Plane aceita do relatório

**Só a cópia que é deste nó.** O serviço filtra por `node.id()` antes de qualquer escrita: um nó que
liste o `objectId` de outro não muda nada. Essa guarda não é detalhe — sem ela, qualquer participante
derrubaria a durabilidade do cluster com um `POST`, que é o ataque mais barato que a rede pode ter.

**Digest esperado vem do catálogo, nunca do relatório.** O nó declara só o que **mediu**
(`actualSha256`); tamanho, réplica e o digest esperado saem da colocação. Se o nó escolhesse o
esperado, faria o próprio conteúdo passar por íntegro.

**Idempotente.** Cópia já `STALE` ou `GONE` não é regravada nem re-auditada: um nó que reenvia o
mesmo relatório não infla a auditoria nem muda o estado de novo. O estado vai para `STALE` pelo mesmo
caminho `marcarDivergente` da confirmação divergente — a ação é a mesma (re-replicar, **nunca**
apagar), e o healing do §5.1 já sabe ler `STALE`.

### O que ainda não está entregue

O nó **roda** esta varredura por conta própria: `exec_storage_scrub` em `deploy/node-runtime/node-runtime.sh`
mede o próprio disco a cada ciclo, **depois do heartbeat e antes de pedir tarefa** (uma varredura
longa não pode atrasar o heartbeat, que é a única saída do nó), em lotes, e entrega por
`POST /network/storage/scrub`. Nó sem `OBJECT_PUT` na config assinada não manda relatório vazio. A
divergência que ele aponta agora **é tratada pela cura** (§5.1.1).

**Verificação:** `scripts/tests/node-executor-test.sh` cobre o runtime (entrega o sha medido, não
declara divergência por erro de I/O, não manda relatório de nó sem `OBJECT_PUT`). `StorageNodeScrubTests` **4** (cópia própria fica `STALE`; nó não rebaixa a cópia de
outro; já `STALE` não é regravada nem re-auditada; digest esperado vem do catálogo) e
`NetworkStorageNodeScrubTests` **3** pela rota real (cópia própria `STALE`; intruso não rebaixa a do
dono; sem chave é 401). Mutações: sem filtro por dono, sem idempotência, rota fora do interceptor,
relatório engolido (`stale` sempre zero) — **4 de 4 derrubam**.
