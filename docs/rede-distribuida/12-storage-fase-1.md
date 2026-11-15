# Énóis Storage — fase 1: núcleo do catálogo e rota de corpo

O brief do Storage (`enois-storage-brief.txt` §22) define a fase 1 como "Catálogo de objetos,
Storage Gateway, API S3 básica, upload e download, identificadores lógicos, primeiro Storage Node,
integração com a Énóis Network". Este documento diz o que **existe** depois desta entrega, o que
ficou fora e por quê, e como cada afirmação foi verificada.

## 1. O que está entregue

| item do brief | estado | onde está |
|---|---|---|
| **integração com a Énóis Network** | entregue: nenhuma identidade nova | o nó do Storage é um nó da rede (`X-Node-Id` + `X-Node-Key`, provisionamento por token de uso único, aprovação pelo admin) |
| **perfil de armazenamento** | entregue | `NodeProfile.NODE_STORAGE`; `TaskType.OBJECT_PUT` e `OBJECT_GET` exigem esse perfil |
| **catálogo de objetos** | entregue | `network_storage_objects` (V319) — `id` lógico próprio, `bucket`, `object_key`, `content_type`, `bytes`, `sha256`, `state` (`PENDING`/`STORED`/`DELETED`), autor, datas |
| **identificadores lógicos** | entregue | `StorageObject.id` (`sob_…`) estável, independente de qualquer nó; a chave S3 é do cliente e não é normalizada |
| **colocação (onde está a cópia)** | entregue | `network_storage_placements` (V320) — `(object_id, replica)` → `node_id`, `bytes`, `sha256`, `state`, `verified_at` |
| **upload por nó** | entregue | `PUT /network/storage/objects/{id}` — bytes crus, credencial de nó, perfil obrigatório |
| **download por nó** | entregue | `GET /network/storage/objects/{id}` — `Content-Length`, `ETag` = sha-256, sem renderizar |
| **medida antes do aceite** | entregue | o Control Plane calcula tamanho e SHA-256 **enquanto grava**; o declarado não é lido |
| **teto de tamanho** | entregue | `rede.storage.max-object-bytes` (padrão 5 GiB); acima do teto o corpo é recusado |
| **primeiro Storage Node** | parcial: o Control Plane faz esse papel | grava no diretório `rede.storage.root`; ver §3 |
| **API S3 básica / gateway** | **não entregue** | ver §3 |

## 2. Decisões que valem por si

**"Gravou" só vale quando foi medido.** O tamanho e o hash que entram no catálogo são calculados
sobre o corpo que chegou — não há campo onde o cliente ou o nó declarem `bytes`. É o mesmo critério
do transcode de mídia: um upload que responde 200 sem conferência é confiança, não durabilidade.
Consequência prática: o `ETag` do download é o sha-256 do que **foi gravado**, e um upload
interrompido não deixa objeto servível.

**O corpo é escrito antes do catálogo.** O upload vai para um temporário (`*.part` no diretório
autorizado) e só é renomeado para o destino depois de medido; o catálogo é atualizado por último,
com transição condicional (`PENDING → STORED`). Registrar "tem cópia" apontando para um arquivo que
ainda não existe daria um objeto servível sem bytes.

**Lápide, não `delete`.** Remover marca `DELETED` e mantém a linha: a chave continua ocupada e as
cópias nos nós podem ser varridas depois. Reabrir a mesma chave enquanto a lápide existe é recusado
(`STORAGE_KEY_DELETED`) — senão o cliente reenvia o arquivo e nada aparece no download.

**A chave S3 não é normalizada.** `Foto.JPG` e `foto.jpg` são objetos diferentes, como no S3.
Bucket, ao contrário, só aceita minúscula: aceitar `Fotos` e `fotos` daria duas grafias do mesmo
bucket e a unicidade do banco esconderia a colisão.

**Disco não é permissão.** Um nó com 10 TB e sem `NODE_STORAGE` recebe 403. É a mesma regra que já
vale para CPU e memória: capacidade não autoriza nada.

## 3. O que NÃO está entregue (e por quê)

| do brief | por que não agora |
|---|---|
| **gateway S3 público** (`PUT/GET` com assinatura AWS v4, `s3.enois.ia.br`) | a fase 1 entrega o **contrato de armazenamento** (catálogo + corpo autenticado por nó). A camada de compatibilidade S3 é uma interface de tradução sobre este contrato, com decisão própria de credencial de cliente (AccessKey/SecretKey) que ainda não existe no projeto. Fingir compatibilidade sem ela daria um endpoint que aceita `Authorization: AWS4-…` e não sabe validá-lo |
| **upload feito pelo cliente direto ao nó** | hoje quem recebe o corpo é o Control Plane, no papel de primeiro nó. O contrato (PUT autenticado, medido, colocação gravada) é o mesmo que o nó usará; a fase 2 decide o endereçamento — e o brief §17 proíbe IP/hostname de storage node, então essa decisão **não pode** ser tomada aqui |
| **multipart upload e retomada** | depende de o cliente falar direto com o nó; com o CP no caminho, um multipart de 5 GB passaria inteiro pelo Control Plane, que o brief §3.1 proíbe |
| **URLs assinadas** | exigem credencial de cliente (ver gateway) |
| **redundância, replicação, healing** | fase 3. A tabela de colocação já nasce com `replica` na chave primária para que a fase 3 **insira** linhas e não altere o esquema |
| **mTLS** | não existe na rede (fase 0 §3); o transporte é HTTPS com a API key do nó |

## 4. Verificação

| verificação | resultado |
|---|---|
| `StorageCatalogServiceTests` | 10 testes: o medido, não o declarado; cópia no nó que gravou; corpo no disco; perfil obrigatório; corpo vazio; teto; pendente não serve; lápide não ressuscita; upload repetido idempotente; chave não normalizada; bucket minúsculo |
| `NetworkStorageTests` | 6 testes pela rota real: upload com `/admin/network/nodes/provision-token` → enroll → aprovação → chave → heartbeat, corpo binário, download com `ETag`, 403 sem perfil, 401 sem credencial, 404 para pendente e inexistente, listagem do nó |
| `scripts/tests/storage-migration-test.sh` | **26 asserções** num MariaDB **11.4.13** real: aplica, grava dados, aplica de novo, confere que nada mudou (objetos, tamanho, colocação), colunas, chave única, chave primária composta, FK e índices |
| mutações | 3 conferidas: catálogo aceitando tamanho fixo → falha "o_que_fica_no_catalogo_e_o_medido"; upload sem gravar o arquivo → falha "a_copia_fica_registrada…e_o_corpo_esta_no_disco"; sem checagem de perfil → falha "sem_perfil_de_storage" |

### Defeito encontrado pelo harness, não pelo H2

A primeira versão do `V319` criava índice sobre `object_key varchar(1024)` inteiro. Em `utf8mb4`
isso são 4096 bytes e o InnoDB recusa acima de 3072 (`ERROR 1071`). O H2 aceitou; o MariaDB real
não. O índice passou a ser **prefixo de 512 caracteres**, com a unicidade garantida pela constraint
da tabela. Sem o harness contra o banco real, esse defeito só apareceria no deploy.

## 5. Estrutura

```
network/
  domain/            StorageObject, StoragePlacement, StorageObjectState,
                     NodeProfile.NODE_STORAGE, TaskType.OBJECT_PUT|OBJECT_GET
  application/       StorageCatalogService (regras: mede, limita, audita, lapida)
  application/port/  StorageCatalogRepository (a fronteira que esconde o nó físico)
  adapters/out/      StorageCatalogPersistenceAdapter (JDBC; insert-ou-ler e transições condicionais)
  adapters/in/web/   NetworkStorageController (/network/storage/**)
db/migration/        V319 (objetos), V320 (colocações)
```