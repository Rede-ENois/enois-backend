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
| **healing automático** | reparar exige escolher nó e reenviar o corpo — custo de disco e banda de quem contribui. A varredura **aponta** a lacuna; a decisão de reparar é de quem opera. Automático, um objeto pequeno com bugs de placement geraria tráfego em anel |
| **scrubbing que relê bytes** | exige a réplica servir o corpo ao Control Plane e o CP comparar hash com o medido. O contrato de leitura por digest já existe; falta o passo de verificação periódica |
| **domínio de falha maior que o nó** | o cadastro não tem host físico, rack nem operadora. Derivar isso de região/cidade seria deduzir topologia. "Mesmo nó" é o que o dado permite afirmar |
| **erasure coding** | da fase 4 do brief (§ distribuição inteligente), não da 3 |
| **via de entrega ao par (push do CP para o nó)** | o nó **puxa** o objeto na URL do CP, e o CP não inicia conexão para o nó — pelo mesmo motivo do §17: o CP não conhece o endereço do nó |

## 4. Verificação

| verificação | resultado |
|---|---|
| suíte completa | 3107 testes (R123) |
| `StoragePlacementServiceTests` (novo) | 8 — folga, não-repetição, nó pronto, disco, estável |
| `NetworkStorageReplicationTests` (novo) | 6 — designação ≠ durabilidade, confirmação conferida, digest, varredura |
| `storage-migration-test` | **29** asserções em MariaDB 11.4 real, migrações 2× com dados |

Mutações conferidas: confirmação sem conferir digest derruba `designacaoNaoContaComoDurabilidade…`;
aceitar `PENDING_REMOTE` como confirmável derruba o mesmo teste; varredura contando
`PENDING_REMOTE` como cópia derruba `varreduraApontaObjetoComMenosCopiasQueOPedido`.

## 5. Migração

`V323__network_storage_placement_pending_sha.sql` — `sha256` passa a aceitar nulo, porque a
designação existe antes de haver cópia. Aditiva: nenhuma linha existente muda.
