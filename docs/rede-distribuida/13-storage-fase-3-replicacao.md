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

**A escolha é estável.** Ordem por folga de disco medida (decrescente) e desempate por id. O mesmo
objeto, reenviado, escolhe o mesmo conjunto enquanto a rede não mudar; um conjunto que mudasse a
cada retry espalharia réplicas e deixaria cópias órfãs.

**Sem medida de disco, o nó não é recusado.** Não medir não é estar cheio — a mesma regra do resto do
módulo. Nó com disco medido abaixo do piso **e** do tamanho do objeto não recebe cópia.

## 3. O que NÃO está entregue (e por quê)

| item | por que não |
|---|---|
| **healing automático (agendado)** | o healing **existe** como ato de operador — `POST /admin/network/nodes/storage/heal` designa a lacuna a nós (ver §5.1). O que não existe é agendador: designar gera tráfego de corpo, e o custo de disco e banda é de quem contribui |
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
| `storage-migration-test` | **29** asserções em MariaDB 11.4 real, migrações 2× com dados |

Mutações conferidas: confirmação sem conferir digest derruba `designacaoNaoContaComoDurabilidade…`;
aceitar `PENDING_REMOTE` como confirmável derruba o mesmo teste; varredura contando
`PENDING_REMOTE` como cópia derruba `varreduraApontaObjetoComMenosCopiasQueOPedido`.

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

**Verificação:** `StorageHealerTests` 8 e `NetworkStorageHealTests` 4 (caminho real: objeto com
lacuna → `scrub` acha → healing designa → nó confirma → `scrub` para de acusar). Mutações: `dryRun`
gravando, réplica reaproveitando o número 1, `STALE` contando como origem, designar mais que a
lacuna, pendente sendo reescolhida e designar já como `OK` — cada uma derruba o seu, e a de `OK`
derruba 2 no teste de rota.

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
| 2 | baixa por `GET /network/storage/objects/{id}` com a **mesma** chave de nó e o mesmo `--limit-rate` do teto de banda declarado |
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

**Verificação:** `StorageLocalBodyServingTests` 6 (serve do nó local; 409 sem corpo; raiz do CP tem
prioridade; truncado é 410; sem nó local lê a raiz; réplica 0 não cai para o nó);
`StorageScrubberReadBackTests` 5 (1 novo: sumiço da réplica 0 não é escondido e a cópia de outro nó
não é acusada); `NetworkStorageReplicationTests` 8 (1 novo pela rota real). Mutações: sem fallback,
ordem invertida, sem conferência de tamanho, réplica 0 com fallback, varredura relendo todas as
cópias — 5 de 5 derrubam.
