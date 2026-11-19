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
| **download por nó** | entregue | `GET /network/storage/objects/{id}` — `Content-Length`, `ETag` = sha-256, sem renderizar; serve da raiz do CP e do diretório do nó local (13 §5.5) |
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

**Capacidade de disco se decide pelo medido, não pelo declarado.** Desde `V321` o nó reporta no
heartbeat o disco livre que ele **mede** (`df -P -m` no diretório de estado), ao lado do
`declared_disk_mb` do enroll, que é a promessa. Um nó que reporta menos de
`StorageCatalogService.MIN_DISK_FREE_MB` (1 GiB) recebe **507 `STORAGE_NO_DISK_SPACE` antes de o
corpo atravessar** — mesmo critério do teto de tamanho: não escrever o que já se sabe que não cabe.
Sem medida (`null`, runtime anterior a V321) o nó **não** é recusado: não medir não é estar sem
espaço, e recusar por falta de informação tiraria do caminho nós com terabytes livres. O piso é
folga de sistema de arquivos (temporário do upload, journal), não o tamanho do objeto.

## 2.3 Cota de espaço por participante (doc 20 §10 / brief §10)

O teto de espaço que existia era o de quem **cede** — o disco livre e o espaço declarado do nó
(§7 do brief). Faltava o de quem **usa**: quantos bytes um participante pode ocupar no catálogo.
Sem ele, um único participante com credencial S3 enchia o disco e a cota dos nós, e o upload dos
outros passava a responder `507 STORAGE_NO_CEDENT_SPACE` — o vizinho pagava pelo vizinho.

| | antes | agora |
|---|---|---|
| abertura do upload (`beginUpload`) | nada | **`507 STORAGE_OWNER_QUOTA_EXCEEDED`** quando `bytesOwnedBy(actorId) >= cota` — recusa antes de o objeto nascer, para a chave não ficar presa num `PENDING` |
| fim do upload (`finishUpload`, rota S3) e gravação do nó (`store`) | nada | **`507`** quando `usado + bytes medidos > cota` — o corpo já foi medido, e recusar depois de gravar seria gravar o que não cabe |
| rota do gateway S3 | — | o mesmo `507` traduzido para **`<Code>QuotaExceeded</Code>`**, o código que o SDK entende |

A conta é `sum(bytes)` de `network_storage_objects` para o `created_by`, das versões **correntes** e
sem lápide (`StorageCatalogPersistenceAdapter.bytesOwnedBy`): versão substituída não é conteúdo que o
download entrega, lápide não é conteúdo nenhum.

**A cota é do conteúdo entregue, não do espaço ocupado** (doc 13 §5.9.1). Um objeto com
versionamento que foi reescrito dez vezes tem nove versões antigas no disco e só a corrente na conta;
e o PUT de reescrita **acrescenta** o corpo novo sem descontar a versão que saiu da corrente, então a
mesma chave pode receber `507` antes do objeto que só ela ocupa. Contar o espaço real exigiria somar
todas as versões ainda não coletadas — decisão de produto (a cota passaria a cair por reescrita de
objeto que o cliente continua vendo com um só nome). **Não é** por bucket — quem tem dez buckets não tem
dez vezes o espaço. `rede.storage.max-bytes-per-owner=0` (padrão) **desliga** a cota, que é o
comportamento que existia: a instalação que confia no limite do nó não muda de comportamento ao
atualizar.

A contagem é **somada em memória por instância** — a primeira leitura vem do banco, e cada gravação
desta instância acrescenta. Com várias instâncias de API, a conta de cada uma começa no banco
(numeração do momento em que foi lida) e a do outro não aparece até reiniciar: o teto é honesto
sozinho (uma instância vê o que ela gravou) e **não** é um teto global exato com várias instâncias.
O que realmente impede o abuso é o **`507` na abertura** consultando o banco uma vez por
participante; o que depende da memória é só o tamanho do corpo que chega logo depois. Compartilhar a
conta exige o Redis que a rede ainda não usa para limitador (a mesma nota do doc 19 §3.2).

**Verificação:** `StorageOwnerQuotaTests` **4** (cheio é recusado antes de o objeto nascer; o vizinho
não paga; cota `0` desliga; a mensagem traz os três números), `S3OwnerQuotaTests` **1** pela rota real
de PUT do gateway (primeiro objeto 200, segundo 507 `QuotaExceeded`, vizinho 200). Mutações: cota
global em vez de por participante, sem o acréscimo do tamanho, `beginUpload` sem a checagem, a soma
que nunca acontece — **4 de 4 derrubam**.

## 2.4 Chave vencida e chave removida recebem gravação nova

A leitura já tratava o objeto vencido como inexistente (`requireDownloadable` responde 404), e o
cliente S3 trata `DELETE` como "o nome está livre". O upload, não: `network_storage_objects` tem uma
linha viva por `bucket + object_key` (o índice condicional `uq_network_storage_objects_current` do
V352) e o `createPending` é **inserir-ou-ler** — ele devolvia a linha que já existia **qualquer que
fosse o estado**. Duas falhas silenciosas saíam daí:

| situação | antes | agora |
|---|---|---|
| chave vencida, coleta ainda não passou | o `beginUpload` devolvia o objeto velho, com o prazo velho; o `finishUpload` respondia `409 STORAGE_ALREADY_STORED` — e a chave não dava nem para baixar (404) nem para regravar | a versão vencida **sai aposentada** (`superseded_by` apontando para a linha nova) e a gravação segue numa linha nova, com id novo e prazo novo |
| chave com lápide (removida pelo cliente ou pelo bucket) | `409 STORAGE_KEY_DELETED` — o cliente S3 reenviava o arquivo e recebia erro num nome que ele já tinha liberado | a linha da lápide é **descartada** e a gravação nasce numa linha nova |

O que sai da chave não é lápide nova. Nada que ainda tenha conteúdo é marcado como removido por causa
de uma **abertura** de upload — que roda em retry de rede e pode nunca virar gravação. E aposentar
não é remover: a versão vencida continua `STORED`, e a coleta continua vendo nela um objeto
**expirado**, não um removido, porque o corpo dela pode estar servido por quem tem cópia. A lápide, que
já era remoção antes desta requisição, é apagada da tabela: o que ela guardava já está nas colocações
de cada nó, que é o que a coleta consulta — apagar a linha não apaga byte nenhum de nó.

O objeto vivo não muda: reenviar o mesmo `PUT` devolve o **mesmo** objeto (retry idempotente), e a
rota S3 continua respondendo `409 ObjectAlreadyStored` quando o corpo tenta substituir uma cópia já
confirmada.

**Verificação:** `StorageExpiredKeyReuseTests` **6** (chave vencida recebe objeto novo sem herdar o
prazo; a gravação nova termina e o download a devolve; o vencido não é apagado do disco do nó; o
reenvio de objeto vivo não lapida nem aposenta; a chave lapidada recebe objeto novo sem ressuscitar o
removido; objeto vivo segue recusando a gravação). `StorageCatalogCurrentKeyPersistenceTests` **4** no
banco real (a versão aposentada libera a chave; a lápide que continua corrente impede a linha nova; a
lápide descartada libera; a segunda corrente é barrada pelo índice). Mutações: sem a aposentadoria do
vencido, vencido virando lápide, lápide voltando a dar 409 — **3 de 3 derrubam**.

## 3. O que NÃO está entregue (e por quê)

| do brief | por que não agora |
|---|---|
| **gateway S3 público** (`PUT/GET` com assinatura AWS v4, `s3.enois.ia.br`) | a fase 1 entrega o **contrato de armazenamento** (catálogo + corpo autenticado por nó). A camada de compatibilidade S3 é uma interface de tradução sobre este contrato, com decisão própria de credencial de cliente (AccessKey/SecretKey) que ainda não existe no projeto. Fingir compatibilidade sem ela daria um endpoint que aceita `Authorization: AWS4-…` e não sabe validá-lo |
| **upload feito pelo cliente direto ao nó** | hoje quem recebe o corpo é o Control Plane, no papel de primeiro nó. O contrato (PUT autenticado, medido, colocação gravada) é o mesmo que o nó usará; a fase 2 decide o endereçamento — e o brief §17 proíbe IP/hostname de storage node, então essa decisão **não pode** ser tomada aqui |
| **multipart upload e retomada** | depende de o cliente falar direto com o nó; com o CP no caminho, um multipart de 5 GB passaria inteiro pelo Control Plane, que o brief §3.1 proíbe |
| **URLs assinadas** | exigem credencial de cliente (ver gateway) |
| **redundância, replicação, healing** | fase 3. A tabela de colocação já nasce com `replica` na chave primária para que a fase 3 **insira** linhas e não altere o esquema |
| **mTLS** | não existe na rede (fase 0 §3); o transporte é HTTPS com a API key do nó |
| **escolher o nó de destino pelo espaço livre** | a fase 1 tem **um** destinatário (o CP); escolher entre nós é a fase 2, e ela agora tem a medida de que precisa — o que falta é o endereçamento, não o número |

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
## A coleta da lápide (§14 do brief do Storage)

Remover um objeto marca `DELETED` e mantém a linha — mas o objeto pode voltar a ter cópias depois
disso: um upload que venceu a corrida, ou uma réplica da fase 3 que chegou atrasada. Enquanto essas
cópias existirem, o objeto "removido" continua contando na durabilidade e pode ser servido por um
caminho que não conhece a lápide.

`POST /admin/network/nodes/storage/tombstones/collect?dryRun=true` (admin) recolhe as cópias de
lápides **vencidas** (mais que `rede.storage.tombstone.retention-hours`, 72 h por padrão):

- **A janela protege o desfazer.** Lápide recente não é tocada: o cliente pode desfazer, e apagar a
  cópia agora tornaria o desfazer impossível sem um novo upload.
- **O que muda é a cópia, não o objeto.** Cópia ativa vira `GONE`; a linha do objeto fica — a lápide é
  rastro, não remoção de linha, e é ela que impede a chave de voltar a ser usada pelo mesmo objeto.
- **`dryRun` é o padrão** e mostra o que sairia sem gravar. A coleta é **ato de operador**, nunca
  agendador: cada execução muda a durabilidade de objetos removidos.
- **Objeto que voltou a `STORED` é pulado.** A página é lida, e cada objeto é relido antes de decidir:
  entre a página e a decisão, um upload pode ter marcado o objeto de volta, e recolher as cópias dele
  apagaria o corpo que o cliente acabou de gravar.
- **O cursor avança mesmo quando a página não rende.** Um lote inteiro de lápides vivas consumiria o
  teto de páginas relendo a mesma linha (medido: 20 páginas para uma lápide, antes do conserto).
- **Auditoria por operação**, não por cópia: `STORAGE_TOMBSTONES_COLLECT_RUN` diz quantas lápides e
  em qual janela; uma linha por cópia seria centenas para dizer o que o relatório diz.

### A durabilidade lê `copies`, não `placements`

A coleta expôs um defeito mais grave que a própria coleta: **`catalog.placements(objectId)` devolve só
cópias `OK`**, mas três leitores o usavam como "todas as cópias do objeto":

| leitor | o que perguntava | resultado com `placements` |
|---|---|---|
| `StorageReplicationDispatcher` | há `PENDING_REMOTE` a despachar? | lista **sempre vazia**: a réplica designada pelo upload e pelo healing nunca virava tarefa |
| `StorageHealer.temDesignacaoPendente` | já há um dono para a lacuna? | **sempre `false`**: a cura designava de novo, com um número novo |
| `StorageHealer.heal` | qual é o maior número de réplica? | lia só `OK`: o número de uma cópia `GONE` seria reaproveitado e sobrescreveria a linha |

A porta ganhou `copies(objectId)` (todos os estados) e `placements(objectId)` ficou com o nome do que
faz — só `OK`. **Corrigir os três leitores um a um** deixaria o quarto nascer errado; o nome errado
era a causa.

**Verificação:** `NetworkStorageTombstoneTests` 6 (rota real: lápide vencida recolhida, recente
intocada, `dryRun` sem gravar, objeto vivo fora, exige admin, **despacho envia a réplica pendente**) e
`StorageTombstoneCollectorTests` 6 (corrida do objeto que voltou a vivo, cursor que avança, janela
pedida ao banco, `dryRun` sem gravar nem auditar, coleta real grava `GONE` e audita). Mutações: o
despacho voltar a ler `placements`; a janela de retenção ignorada; `dryRun` gravando; a coleta não
reler o estado; o cursor preso na página pulada — **5 de 5 derrubam**.
