# Énóis Storage — fase 3: réplica, digest e varredura

Documento do que **existe** no código. A fase 1 (catálogo e rota de corpo) está em
[`12-storage-fase-1.md`](12-storage-fase-1.md).

## 1. O que está entregue

| item do brief (§ fase 3) | estado | onde está |
|---|---|---|
| **réplica** | entregue: o Control Plane designa nós e eles buscam o objeto na própria URL do CP | `StoragePlacementService`, `POST /network/storage/objects/{id}/replica` |
| **domínios de falha** | parcial: **nunca duas cópias no mesmo nó**; domínio maior que o nó (host físico, rack, operadora) não existe — ver §3 |
| **checksums** | entregue: o sha-256 medido no CP é a identidade da cópia; a confirmação da réplica é **conferida** contra ele | `StorageCatalogService.confirmReplica` |
| **scrubbing** | parcial: encontra objetos com menos cópias `OK` que o pedido; **não** relê bytes — ver §3 | `StorageScrubber`, `GET /admin/network/nodes/storage/scrub` |
| **healing** | **não entregue** — ver §3 | |
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
arquivo de cada cópia `OK` que este Control Plane hospeda e compara **tamanho e sha-256** com o
medido que o catálogo guarda. O relatório ganhou `bodiesRelidos` e `divergent[]` (objeto, réplica,
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
