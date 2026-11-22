# 18. Gateway S3 (brief §11)

`https://s3.enois.ia.br` — a porta do Énóis Storage para <b>aplicações</b>, em vez de para nós da rede.

## 1. Por que existe, e o que havia antes

Até a §13 do brief, o Storage só falava `/network/storage/*` autenticado por **chave de nó**
(`X-Node-Id` + `X-Node-Key`). É a credencial de uma máquina da rede cooperativa: dá acesso a puxar
tarefa, gravar réplica designada, varrer o próprio disco. Nenhum SDK S3 fala esse protocolo — e o
objetivo do §11 é justamente "permitir utilização de SDKs S3 convencionais".

O modelo de dados já era S3-shaped desde o V319: `bucket` + `object_key` únicos, chave byte a byte
sem normalização, índice para listagem por prefixo. O gateway **reusa** esse catálogo; não há segundo
Storage.

## 2. Credencial: separada da chave de nó, de propósito

`network_storage_s3_credentials` (V357) tem Access Key, segredo e **escopo**:

| coluna | significado |
|---|---|
| `access_key` | identificador público, único — é por ele que o gateway busca |
| `secret_hash` | SHA-256, para conferência e auditoria |
| `secret_encrypted` | segredo **cifrado** com o `SecretCipher` (AES-GCM, `REDE_SECURITY_ENCRYPTION_KEY`) — o mesmo cofre do TOTP |
| `allowed_bucket` | nulo = todos os buckets do dono |
| `allowed_operations` | verbos S3 separados por espaço (`GET PUT`) |
| `revoked_at` | revogação terminal |

**O segredo tem de ser recuperável**, não só hash: o SigV4 exige recalculá-lo a cada requisição. Por
isso ele é cifrado pelo cofre que já existia — e, sem a chave de cifra, **criar credencial é 503** e
uma credencial sem segredo **não autentica**, em vez de "aceitar sem conferir".

Por que não reusar a chave do nó: o §10 proíbe compartilhar credencial administrativa com participante;
rotacionar a credencial de uma aplicação não pode derrubar um nó da rede; e o escopo é diferente (nó
opera a rede, credencial S3 alcança buckets).

## 3. SigV4

`SigV4Verifier` implementa AWS Signature Version 4, e os testes conferem contra os **vetores oficiais**
do repositório `awslabs/aws-c-auth` (`get-vanilla`, `get-vanilla-query-order-key-case`). Conferir
contra o vetor externo é o que separa "implementei" de "assinei comigo mesmo e confere".

- **Janela de relógio**: ±900 s contra o `x-amz-date` (truncado em segundos).
- **Comparação em tempo constante.**
- **Região**: uma só, `enois-1`. Ela entra na chave derivada como string; região errada simplesmente
  não confere. A rede não é particionada por "região S3" — a região de colocação é a da §2.
- **URI canônica**: o gateway tem prefixo `/s3` na instalação, mas a assinatura é sobre `/bucket/key`
  — o texto do protocolo, não o da nossa infraestrutura.
- **Payload**: hash do corpo, conferido na chave derivada. `UNSIGNED-PAYLOAD` é aceito quando o cliente
  declara o cabeçalho com esse valor, como o S3 faz.

## 4. Operações — exatamente o que existe

| operação | rota | estado |
|---|---|---|
| PutObject | `PUT /s3/{bucket}/{key}` | ✅ mesmo caminho do catálogo; ETag = digest |
| CopyObject | `PUT /s3/{bucket}/{key}` + `x-amz-copy-source` | ✅ cópia no servidor, sem o cliente enviar o corpo (§4.19) |
| UploadPartCopy | `PUT /s3/{bucket}/{key}?partNumber=&uploadId=` + `x-amz-copy-source` | ✅ parte a partir de um intervalo da origem (§4.20) |
| GetObject | `GET /s3/{bucket}/{key}` | ✅ `Range`/206/416, `304` por `If-None-Match` ou `If-Modified-Since` |
| HeadObject | `HEAD /s3/{bucket}/{key}` | ✅ `304` por `If-None-Match` / `If-Modified-Since` |
| DeleteObject | `DELETE /s3/{bucket}/{key}` | ✅ **lápide** (como o S3 com versionamento), 204 |
| ListObjectsV2 | `GET /s3/{bucket}?list-type=2` | ✅ `prefix`, `max-keys` (teto 1000), `continuation-token` |
| ListObjects (v1) | `GET /s3/{bucket}` | ✅ `marker` |
| ListBuckets | `GET /s3` | ✅ só os buckets do dono |
| DeleteBucket | `DELETE /s3/{bucket}` | ✅ `409 BucketNotEmpty` se houver objeto **vivo**; `204` se não houver (§4.15) |
| CreateMultipartUpload | `POST /s3/{bucket}/{key}?uploads` | ✅ devolve `UploadId` |
| UploadPart | `PUT /s3/{bucket}/{key}?partNumber=&uploadId=` | ✅ fora de ordem; reenvio substitui |
| CompleteMultipartUpload | `POST /s3/{bucket}/{key}?uploadId=` | ✅ monta o corpo na ordem pedida; parte ausente recusa |
| AbortMultipartUpload | `DELETE /s3/{bucket}/{key}?uploadId=` | ✅ apaga partes e registro |
| ListParts | `GET /s3/{bucket}/{key}?uploadId=` | ✅ o que já chegou |
| Emitir URL pré-assinada | `POST /s3/{bucket}/presign?key=&method=GET\|PUT&expires=` | ✅ sessão do dono; devolve `url`, `method`, `expiresIn`, `expiresAt` |
| Usar URL pré-assinada | `GET`/`PUT /s3/{bucket}/{key}?X-Amz-Signature=…` | ✅ sem `Authorization`, sem sessão |

**Não implementado**, e o brief manda não anunciar como se existisse:

- **ACL**, **tagging**, **`versionId`**, **bucket policy**, **`POST` de formulário** — sem suporte e
  não anunciados.
- **Regiões múltiplas**, **classes de armazenamento na resposta** (sempre `STANDARD`; a classe real é a
  da §8 e não é escolhida por requisição).

**Diferença semântica declarada**: o `DeleteObject` grava **lápide**, não apaga os bytes. É o mesmo
comportamento do S3 em bucket versionado — mas num bucket **sem** versionamento, o S3 real apagaria. A
escolha aqui é a do módulo inteiro: apagar byte é coletado pelo nó, nunca por requisição de cliente
(§14 e §16 da doc do Storage). Um cliente que espere "liberou espaço na hora" se engana.

### 4.0 Donagem do bucket — por verbo

`allowedBucket = null` (o padrão de toda credencial) significa **todos os buckets do dono**, nunca "todos
os buckets da rede". O escopo da credencial e a donagem do bucket são duas perguntas independentes, e
as duas precisam de "sim":

| verbo | escopo (`allowsBucket`) | donagem | ordem |
|---|---|---|---|
| ListBuckets `GET /s3` | — | ✅ por construção (`bucketsOf`) | — |
| ListObjects `GET /s3/{bucket}` | ✅ | ✅ | antes |
| GetObject / HeadObject | ✅ | ✅ | antes |
| DeleteObject | ✅ | ✅ | antes |
| DeleteBucket | ✅ | ✅ (vivo **ou** lápide, §4.15) | antes |
| PutObject | ✅ | ✅ | **depois da gravação** |

**Por que o `PUT` é o único fora de ordem.** O Storage não tem tabela de buckets: o nome nasce do
primeiro objeto (`created_by`). Conferir donagem antes da gravação recusaria o primeiro objeto de
**toda** conta, porque o bucket que ele ainda vai criar não existe para ninguém. Então o `PUT`:

1. grava (o mesmo caminho do catálogo, `beginUpload` + `finishUpload`);
2. confere que **todo objeto vivo no bucket** é do dono da credencial;
3. se houver um só de outra conta, a gravação é desfeita e a resposta é **403**.

A desfeita (`S3GatewayController.desfazer`) não é lápide: `state = 'DELETED'` sai do `bucketsOf`, e
apagar por cima do último objeto de uma conta faria o bucket deixar de ser dela. São duas portas no
catálogo:

- `discardPending(id)` — **apaga a linha** (e as localizações) do objeto **que esta gravação criou**;
  é a inversão de um `createPending`. Um objeto plantado por outra conta no bucket alheio é exatamente
  esse caso: se ficasse, passaria a "dar" o bucket ao invasor.
- `restorePending(id)` — devolve `STORED → PENDING` na chave que **já existia**. O `beginUpload`
  devolve o objeto existente, então reler a chave depois de gravar daria o objeto recém-gravado do
  próprio dono e a donagem passaria sempre; por isso a pergunta é sobre **o bucket**, e por isso o
  objeto anterior é guardado antes do upload.

**403 em vez de 404.** O S3 responde 404 a um bucket que o chamador não pode ver; aqui a resposta é 403
com `AccessDenied`. Quem tem credencial válida e recebe 404 acha que o bucket é dele e o objeto sumiu —
e no Storage não há "bucket privado": ou é do dono, ou não existe para ele. Consequência declarada: a
lápide do último objeto do dono no bucket faz o bucket deixar de ser dele, e o `GET` seguinte responde
403, não 404 (o teste `delete_vira_lapide` aceita os dois justamente para não codificar essa ordem como
contrato).

**Reescrita da mesma chave** continua recusada com **409** (`ObjectAlreadyStored`): o catálogo não
aceita duas cópias correntes da mesma chave — o mesmo gate da rota do nó. A recusa não toca no objeto
que já estava lá.

### 4.1 URLs pré-assinadas

`POST /s3/{bucket}/presign?key=fotos%2Fgato.jpg&method=GET&expires=900` com `X-Session-Token` do dono
devolve:

```json
{"url": "https://.../s3/fotos/...?X-Amz-Algorithm=...&X-Amz-Signature=...", "method": "GET",
 "expiresIn": 900, "expiresAt": "2026-11-18T16:00:00Z"}
```

O portador usa a URL sem `Authorization` e sem sessão — é o que permite ao navegador ler um objeto sem
receber credencial. Regras, todas com teste:

- **Credencial:** a do dono da sessão, **e o bucket tem de ser do dono** — conferido contra
  `bucketsOf(owner)` antes de olhar credencial. `allowedBucket = null` significa "todos os buckets do
  dono", não "todos os buckets da rede": sem essa conferência, escopo nulo emitiria URL de `PUT` para
  o bucket de outra conta, e a URL **sai das mãos do dono**.
- **Verbo:** só `GET` e `PUT`; o escopo da credencial continua mandando (uma URL de `PUT` com credencial
  só de `GET` é recusada ao usar).
- **Validade:** 1 s até 7 dias (`X-Amz-Expires`). Fora disso a emissão recusa e a leitura também.
- **Assinatura:** `UNSIGNED-PAYLOAD` (como a AWS em URL); a consulta canônica inclui **todos** os
  parâmetros da URL menos `X-Amz-Signature`, então acrescentar `response-content-disposition` ou trocar a
  chave quebra a assinatura.
- **`Host` assinado** é o que o portador manda ao usar a URL; o prefixo `/s3` é da instalação e entra só
  como caminho — assiná-lo junto é o erro que fazia a URL emitida não abrir.

A chave vai em parâmetro (`?key=`), não no caminho: `/{bucket}/{*key}` engole o resto do caminho no mesmo
verbo, e uma rota `/{bucket}/{*key}/presigned` colide com ela.

### 4.2 Multipart Upload (V365)

Até a V365 o gateway recusava `CreateMultipartUpload` com 501 — e isso **quebrava cliente real**: todo
SDK usa multipart por padrão acima de 5–8 MB (boto3: 8 MB; SDK Java: 5 MiB), então um vídeo enviado
pelo SDK falhava num arquivo que o protocolo inteiro sabe enviar.

O desenho, em três decisões:

| decisão | por quê |
|---|---|
| **nada vira objeto antes da conclusão** | a chave só entra no catálogo no `Complete`, e a conclusão grava pelo mesmo `beginUpload`/`finishUpload` do PUT simples; um upload pela metade nunca é servível por `GET` |
| **parte ausente recusa a conclusão** (`InvalidPart`) e o upload **continua aberto** | concluir "com o que existe" serviria um arquivo truncado como se estivesse completo; aberto, o cliente reenvia só ela |
| **as partes moram em `storageRoot/multipart/<uploadId>/`** | o catálogo não tem onde guardar "parte de um objeto que ainda não é objeto"; é o mesmo volume do corpo, não um segundo depósito |

Regras do S3 que o gateway aplica: número de parte 1..10000; toda parte que não é a última tem
**5 MiB mínimo** (conferido na conclusão, que é quem sabe qual é a última — recusar no envio faria
falhar quem manda a última primeiro); parte vazia recusada; reenvio da mesma parte substitui; ordem de
chegada não importa, o número é a ordem.

**ETag.** O ETag do objeto é o **sha-256 do corpo montado** — o mesmo valor de um `PUT` simples com o
mesmo conteúdo. O S3 clássico usa um ETag próprio do multipart (`md5(digests)-N`), legado do MD5 que
este gateway não usa. A escolha é consciente: a identidade do objeto no Storage é o sha-256 do que o
cliente enviou, e um objeto multipart tem de ser o mesmo objeto, compare o cliente por qualquer
caminho.

**Credencial.** A donagem do bucket é provada no `Create` (é ele que cria o bucket do dono); cada parte
carrega a credencial do upload, e upload de outra conta responde 404 `NoSuchUpload` — não "403", porque
para ela o upload não existe.

**Abandono.** Partes de upload esquecido ocupam disco no nó. O prazo é gravado no início
(`rede.storage.multipart.ttl-hours`, padrão 24 h; o cliente pode pedir menos) — e até o ciclo 72
nada o aplicava a um cliente que morresse no meio: o prazo só era conferido na **próxima parte**,
que esse cliente nunca manda. A coleta existe agora, e está no §4.2.1.

**Prova:** `S3MultipartTests` (7) — arquivo montado volta inteiro no GET; fora de ordem + reenvio;
parte ausente recusa e o upload segue aberto; parte pequena que não é a última recusa; chave não
existe no catálogo antes da conclusão; outra conta não usa; abort apaga. Mutações conferidas: aceitar
parte ausente ou ignorar o mínimo de 5 MiB derrubam o teste correspondente.

### 4.2.1 Coleta do upload abandonado (ciclo 72)

**Antes:** a frase do §4.2 dizia que `sweep()` descartava o vencido, e o `sweep()` não existia. Um
cliente que cai no meio do upload deixava, para sempre, a linha em
`network_storage_multipart_uploads`, as partes em `network_storage_multipart_parts` e os bytes em
`storageRoot/multipart/<uploadId>/`. Nada tinha prazo: o disco de quem contribui só crescia, e o
painel não mostrava a conta.

**Agora há duas rotas de admin e uma varredura automática:**

| rota / gatilho | o que faz |
|---|---|
| `GET /admin/network/storage/multipart?limit=` | quantos uploads estão abertos, quantos venceram, quais venceram (com `ageMinutes`) e o total de partes. **Só lê** |
| `POST /admin/network/storage/multipart/sweep?dryRun=&limit=` | coleta as vencidas; padrão **seco** |
| `@Scheduled` (1 h, ligado de fábrica) | a mesma coleta, sem ninguém pedir |

As decisões que valem estar ditas:

- **vence o prazo gravado no início, não o da instalação.** O varredor lê `expires_at` de cada linha
  e não recalcula nada: um upload começado com 1 h de prazo vence em 1 h, e um de 24 h não é apagado
  por quem configurou menos. O `ttl-hours` continua sendo só o padrão do início;
- **o vencido é apagado pelo mesmo `abort` do cliente.** Não há segunda forma de remover um upload:
  partes (o `ON DELETE CASCADE` da V365 leva as que restarem), linha e o diretório inteiro;
- **diretório sem linha é recolhido na mesma passada.** É a sobra de uma passada interrompida (linha
  apagada, processo caiu antes do disco). Só o diretório **imediatamente** dentro de `multipart/` e
  com o padrão do id é candidato — o varredor não desce por recursão para descobrir alvos, então não
  tem como chegar a um caminho que o serviço não criou. Um nome fora do padrão (com `.`, por exemplo)
  nunca é tocado;
- **a coleta nasce ligada, ao contrário do auto-heal do Storage.** Ela não gera tráfego nem escolhe
  o que replicar: só apaga o que o próprio cliente declarou vencido. Desligada, volta a ser o buraco;
- **nada aqui toca o catálogo.** Um upload multipart que vence nunca virou objeto — é o desenho
  ("nada vira objeto antes da conclusão") —, então não há cópia, durabilidade nem réplica a repor;
- **uma auditoria por upload apagado** (`STORAGE_MULTIPART_EXPIRED`, com bucket, chave, dono e idade)
  e uma por passada com algo removido (`STORAGE_MULTIPART_SWEEP_RUN`). Bucket e chave vêm do cliente:
  entram escapados no JSON.

**Prova:** `AdminStorageMultipartSweepTests` (4) — o seco mostra o vencido e não apaga; o real apaga
só os vencidos (o do prazo curto sai, o do prazo normal fica) e os bytes saem do disco enquanto os do
upload vivo ficam; o diretório sem linha é recolhido e o de upload vivo e o de nome fora do padrão não
são; conta comum recebe `401`/`403`. Mutações vermelhas: o seco apagando; sem filtro de vencimento;
órfão recolhido sem consultar o banco; sem o filtro de nome; a rota sem recolher órfão; vencido
contado como zero; leitura marcando tudo vivo.

**Mutação equivalente, registrada:** fazer o `discard` não limpar o disco fica **verde**, porque a
varredura de órfãos da mesma passada recolhe o diretório que sobra. O efeito observável (bytes fora do
disco, linha fora do banco) é o mesmo; os dois chamadores de `abort` do gateway passam pelo `discard`,
que apaga a árvore — a limpeza em dois lugares é redundância, não defeito.

### 4.3 Access Keys pelo painel

Criar credencial S3 era SQL direto no banco: o segredo passava por histórico de terminal e só quem
tinha acesso ao banco abria um bucket. Agora três rotas, autenticadas por **sessão** (não por admin —
a credencial é do dono logado; um admin criando credencial de outra conta emprestaria a sessão):

| rota | o que faz |
|---|---|
| `POST /network/storage/access-keys` | cria; o corpo é `{bucket?, operations?}` (nulo = todos os buckets do dono; `operations` padrão `GET`) |
| `GET /network/storage/access-keys` | lista as do dono — **sem segredo** |
| `DELETE /network/storage/access-keys/{id}` | revoga; idempotente |

**O segredo volta uma vez.** A resposta do `POST` é `{credential:{...}, secretKey:"..."}`; ele é
gravado cifrado (AES-GCM do `SecretCipher`) e não é recuperável — a listagem mostra access key, escopo e
estado, nunca o segredo. É o que permite o dono saber o que tem sem que a credencial vaze por tela,
log ou histórico.

**Escopo validado.** Verbo fora de `GET PUT DELETE LIST HEAD` é `400 InvalidArgument` e **nada é
gravado**: uma credencial que "tem permissão" para um verbo que o gateway não entende seria uma
credencial que falha depois, na primeira requisição do SDK. `operations` vazio é recusado por omissão
(padrão `GET`), porque credencial sem verbo não faz nada e quem a cria acha que criou acesso.

**Revogação com o dono na instrução.** O `UPDATE` leva `owner_user_id` no `WHERE` (`revoke(id,
owner, agora)`): credencial de outra conta responde `404` e não é tocada — nem por uma corrida entre
listar e alterar. É o defeito que o teste pegou antes: um `where id = ?` sozinho revogava o id de
qualquer conta. Revogar duas vezes é o mesmo estado final, não erro.

**Teto.** 20 ativas por conta (`409 STORAGE_ACCESS_KEY_LIMIT`). Revogadas não contam e não são podadas
— são a trilha de quem usou o quê.

**Prova:** `StorageAccessKeysTests` (6) — a credencial criada autentica no gateway (assinatura SigV4
passa e a recusa é de autorização, nunca `SignatureDoesNotMatch`/`InvalidAccessKeyId`); listagem sem
segredo e banco sem claro; revogada para de autenticar (`403 InvalidAccessKeyId`); credencial de outra
conta não é revogada e continua viva; verbo desconhecido não grava; sem sessão é `401`. Mutação
conferida: tirar o dono do `SELECT` da revogação derruba o teste da outra conta (antes: 404 → depois:
200, a conta errada revogando).

### 4.4 Metadado e cabeçalhos do objeto (V367)

**Antes:** o gateway guardava tipo, tamanho e digest e descartava o resto. Três coisas sumiam em
silêncio entre o `PUT` e o `GET`: o `x-amz-meta-*` que o cliente declarou, o `Cache-Control` do
objeto (o `GET` respondia a política do TIPO) e o `Content-Disposition` (o anexo vinha sem nome).

**Agora:**

| declarado no `PUT` / `CreateMultipartUpload` | volta no `HEAD` e no `GET` | guardado em |
|---|---|---|
| `x-amz-meta-<nome>` (até 10 pares, nome ≤ 40, valor ≤ 256, conjunto ≤ 2 KB) | `x-amz-meta-<nome>` normalizado em **minúsculas** | `network_storage_objects.metadata_json` |
| `Cache-Control` (≤ 200 caracteres) | o declarado **vence** a política do tipo | `cache_control` |
| `Content-Disposition` (≤ 200 caracteres) | igual ao declarado | `content_disposition` |

Regras que não são estilo:

- **Metadado fora dos limites é `InvalidArgument`, nunca truncado.** Cortar metadado de quem confia
  nele é pior que recusar. O nome tem alfabeto restrito (`A-Za-z0-9._-`) porque volta como cabeçalho
  HTTP: um `\r\n` nele seria injeção de resposta.
- **Cabeçalho com caractere de controle é recusado ANTES de gravar** — e por isso nada é criado: o
  `PUT` que falha por `Content-Disposition` inválido não deixa `PENDING` decorado com o que não foi
  aceito.
- **Nulo é "não declarou"**: objeto antigo (e objeto sem metadado) sai como antes, com a política do
  tipo no `Cache-Control` e sem `x-amz-meta-*`. Nenhum cabeçalho vazio inventado.
- **O metadado é gravado no começo do upload** (`beginUpload` devolve a linha `PENDING`), não na
  conclusão: reenvio de um `PUT` interrompido reaproveita a linha, e cada upload declara o seu.

Prova: `S3GatewayTests.o_metadado_e_os_cabecalhos_declarados_no_put_voltem_no_head_e_no_get`,
`objeto_sem_metadado_nao_ganha_cabecalho_de_metadado`,
`cabecalho_declarado_com_quebra_de_linha_e_recusado`, `ObjectMetadataTests` (6) e a migração
`V367` aplicada duas vezes em MariaDB 11.4 por `scripts/tests/storage-metadata-migration-test.sh`
(PASS 14), com um objeto gravado antes da migração preservado.

### 4.3.1 A listagem não mostra o que o `GET` nega (ciclo 81)

O download de um objeto vencido responde `404` desde a fase 1 — a leitura filtra o prazo. O
`ListObjects` (v1 e v2) não: o `SELECT` da listagem só excluía lápide, então a chave vencida aparecia
ali e sumia no `GET`. Para quem espera a varredura, é "existe mas dá 404"; para um sincronizador de
diretório é o pior dos dois mundos — ele pede, recebe `404` e repete, ou apaga a cópia local achando
que o remoto venceu.

Agora o `SELECT` da listagem tem o **mesmo predicado da leitura** (`expires_at` nulo ou no futuro).
Ele fica no `where`, não é filtrado depois: a listagem é por página, e descartar linhas depois de ler
faria `KeyCount` e `IsTruncated` mentirem (o cliente receberia página "cheia" vazia).

Quem consome a mesma consulta herda a régua: o `GET /network/storage/buckets/{bucket}` do Control
Plane (a listagem interna, usada pelo painel) e a varredura de durabilidade por bucket — que já
pulava o objeto não servível, e agora nem o visita.

**Verificação:** `S3ListExpiryTests` **3** pela rota real (vencido sai da lista e o `GET` responde
`404`; dentro do prazo continua; sem prazo continua). Mutações: sem o filtro de prazo, e o filtro
invertido (listar só os vencidos) — **2 de 2 derrubam**.

### 4.3.2 O `ListBuckets` não mostra o bucket que não abre (ciclo 82)

O ciclo 81 fez a listagem de objeto esconder o vencido. O `ListBuckets` (`GET /s3`), que lista os
**buckets** do dono, seguiu com o critério antigo (só lápide fora). Um bucket cujo único objeto venceu
continuava aparecendo, e aí o nome não fechava com nada:

| pedido | resposta |
|---|---|
| `GET /s3` | o bucket aparece |
| `GET /s3/{bucket}` | lista **vazia** (`KeyCount=0`) |
| `GET /s3/{bucket}/chave.jpg` | **404** |
| `DELETE /s3/{bucket}` | **409** — "ainda tem objetos" |

Agora `bucketsOf` usa o **mesmo predicado** da listagem de objeto (`expires_at` nulo ou no futuro):
bucket sem nenhum objeto vivo sai do `ListBuckets`, que passa a concordar com o que as outras três
rotas já diziam. O bucket com **um** objeto vivo (mesmo tendo outro vencido ao lado) continua listado —
vencimento é por objeto, não por bucket.

**Verificação:** `S3ListBucketsExpiryTests` **4** pela rota real (bucket inteiro vencido sai da lista e
a listagem de objeto dele já vinha vazia; bucket com um vivo e um vencido fica; bucket sem prazo fica;
bucket de outra conta não aparece). Mutações: `bucketsOf` sem o filtro de prazo; filtro invertido;
`bucketsOf` ignorando o dono — **3 de 3 derrubam**.
### 4.3.3 O `LastModified` da listagem no formato que o SDK lê (ciclo 83)

A S3 manda `LastModified` como `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` — **sempre** três casas decimais e
sempre `Z`. O gateway emitia `Instant.toString()`, que:

| instante gravado | antes (na resposta) |
|---|---|
| `2026-11-01T08:00:00Z` (fração zero) | `2026-11-01T08:00:00Z` — **sem** as três casas |
| `2026-11-01T08:00:00.123456789Z` | `2026-11-01T08:00:00.123456789Z` — **nove** casas |

Os dois são instantes válidos em Java, e é por isso que passaram: o SDK da AWS desserializa o campo com
um formatter de milissegundos e recusa o documento inteiro — a listagem que abre no navegador falha no
cliente. O instante com fração zero é o caso **comum** de um item recém-gravado cujo carimbo veio do
relógio em segundo redondo.

Agora o gateway formata com um `DateTimeFormatter` fixo (`S3_INSTANT`), com o milissegundo
**truncado** — truncar e não arredondar: `999999` ns não pode virar o segundo seguinte e dar a um
objeto a data de outro. A fonte é `updatedAt` quando existe, senão `createdAt`.

**Verificação:** `S3ListLastModifiedTests` **2** pela rota real (instante redondo sai
`2026-11-01T08:00:00.000Z`; instante com nanossegundos sai `2026-11-01T08:00:00.123Z`) — o par é o
discriminante, porque cada um cobre um extremo diferente do defeito. Mutações: voltar o
`Instant.toString()`, formato sem as casas, formato com nanossegundos — **3 de 3 derrubam**.

### 4.5 O `uploadId` tem de ser desta chave — nas quatro rotas (ciclo 84)

O `uploadId` identifica o upload; a rota identifica o objeto. As duas coisas têm de coincidir, e a S3
responde **`404 NoSuchUpload`** quando não coincidem: para a chave pedida, aquele upload não existe.

**Antes**, só metade das rotas conferia:

| rota | conferia a chave? | consequência com a chave errada |
|---|---|---|
| `UploadPart` | sim | `404` (certo) |
| `CompleteMultipartUpload` | sim | `404` (certo) |
| `ListParts` | **não** | `200` com as partes de **outro** arquivo |
| `AbortMultipartUpload` | **não** | **apagava** o upload |

O `Abort` é o caro: um cliente que concatena a chave errada (bug de retomada) perdia as partes já
enviadas sem nunca ter pedido isso, e só descobria no `Complete` seguinte.

**Agora** as quatro passam por `uploadDaChave(credential, bucket, request)`, que confere bucket e chave
num único ponto. Reunir é o que impede a divergência de voltar pela quinta rota: a conferência existe
uma vez e toda rota nova que recebe `uploadId` a chama.

**Verificação:** `S3MultipartTests` **+2** pela rota real — `ListParts` da chave certa devolve as partes
e da chave errada é `404 NoSuchUpload`; o `Abort` da chave errada é `404` e o upload continua inteiro
(as partes seguem lá e o `Complete` funciona). Mutações: helper conferindo só o bucket; helper nunca
recusando — **2 de 2 derrubam**.

### 4.6 `IsTruncated` diz "há mais", não "página cheia" (ciclo 85)

O S3 trunca quando **há mais**, não quando a página veio cheia. O gateway decidia por
`found.size() == maxKeys`, que confunde as duas coisas: um bucket com exatamente `max-keys` objetos
saía com `IsTruncated=true` e `NextContinuationToken`, e o cliente — SDK, sincronizador, `aws s3 sync`
— pedia a página seguinte e recebia zero. O custo é uma requisição a mais em todo diretório do tamanho
exato da página; o preço é o cliente não saber quando terminou.

Agora o serviço pede `maxKeys + 1` linha ao banco (`list(..., espiar=true)`) e o gateway olha se veio a
extra:

| linhas que voltaram | `IsTruncated` | o que sai |
|---|---|---|
| menos que `maxKeys + 1` | `false` | todas |
| `maxKeys + 1` | `true` | as `maxKeys` primeiras, com o token da última entregue |

A linha de folga é **uma linha a mais na mesma consulta indexada** — não uma consulta extra, e não um
`count(*)`. `max-keys` inválido ou `<= 0` cai no padrão de 1000, e nada passa de 1000 (o teto da S3).

**Verificação:** `S3ListPaginationTests` **3** pela rota real — três objetos com `max-keys=3`:
`IsTruncated=false`, sem token; quatro objetos com `max-keys=3`: primeira página truncada com token, a
seguinte conclui com um objeto e sem token; um objeto com `max-keys=10`: última página. Mutações:
voltar `found.size() == maxKeys`; nunca truncar; espiar mas devolver a linha extra — **3 de 3
derrubam**.

### 4.7 A parte multipart conta contra a cota <b>no envio</b> (ciclo 87)

A cota por participante era conferida na abertura do upload (`beginUpload`) e na conclusão
(`finishUpload`) — e **nunca nas partes**. Um participante com cota de 1 MB abria um multipart, mandava
10 000 partes de 5 MiB (o máximo de numeração do protocolo) e nunca concluía: **50 GiB de disco
reservados**, com a cota só olhando quando já era tarde. O `AbortMultipartUpload` existe, mas quem abusa
não o chama, e o coletor de vencidos só age depois do TTL.

Agora `UploadPart` confere `usado + partes abertas da conta + esta parte` contra a cota, com o `507` da
conclusão:

| quando | o que é conferido |
|---|---|
| parte com `Content-Length` | a conta fecha **antes** de ler — nada é gravado para depois ser recusado |
| parte sem tamanho declarado (chunks) | a conta fecha com o tamanho **medido**, antes de o temporário virar a parte |
| reenvio da mesma parte | a parte anterior sai da conta, então reenviar não soma duas vezes |

Dois detalhes que o ciclo fechou junto:

- **o teto de objeto para de ler** no multipart, como no `PUT` simples (ciclo 86): `lerNoMaximo` pede no
  máximo o que falta até "teto + 1", em vez de puxar mais um bloco de 64 KiB para responder `413`;
- **a recusa sai no vocabulário do S3**: `507 QuotaExceeded` em XML, não o JSON do Control Plane. O `PUT`
  simples traduzia e o `UploadPart` não; a tradução virou um ponto único (`noVocabularioDoS3`).

**Verificação:** `S3MultipartTests` **+1** com cota de 20 MiB: três partes de 6 MiB passam, a quarta recebe
`507 QuotaExceeded`, e o upload continua inteiro (as partes 1–3 seguem lá). Mutações: a parte não
confere a cota; a cota ignora as partes abertas; sem a tradução ao SDK — **3 de 3 derrubam**.
### 4.8 A listagem mostra a chave <b>uma vez</b>, e só o que abre (ciclo 88)

O filtro da listagem era `state <> 'DELETED'`. Uma chave regravada tem **duas linhas** — a versão
corrente e a nova, ainda `PENDING` —, e as duas passavam. O `ListObjects` devolvia então:

- a **mesma chave duas vezes na mesma página** (`["a.jpg", "a.jpg"]` na primeira página, com exatamente
  a cardinalidade da página, o que fazia o `IsTruncated` e o `KeyCount` contarem a chave em dobro); e
- a chave em `PENDING`, que o `GET` recusa — a listagem entregava o que o download não abre.

Como **o cursor da paginação é a chave**, a duplicata é pior que um resultado errado: um cliente que
segue o `NextContinuationToken` comparando chaves pode não convergir, e um sincronizador de diretório
pode ver a mesma chave em duas páginas seguidas. Se a regravação é de uma chave **antes** do cursor, a
chave sai depois dele na ordem e a página seguinte nunca a alcança — o laço termina e a chave fica fora
da cópia local até a próxima passada inteira.

Agora a listagem usa **a régua do `GET`**:

```sql
state = 'STORED' and superseded_by is null and (expires_at is null or expires_at > ?)
```

`PENDING` é gravação em curso: não existe para quem lista, como não existe para quem baixa. A chave
reaparece quando a gravação termina. `superseded_by is null` é a garantia de **uma linha por chave**,
que o cursor exige. É a mesma régua da varredura (`listAll`) e do painel, que já filtravam assim.

**Verificação:** `S3ListPaginationTests` **+1** — quatro objetos, `a.jpg` regravada (linha nova
`PENDING`, antiga `superseded_by`) com o cursor em `b.jpg`: primeira página `["b.jpg", "c.jpg"]`, segunda
`["d.jpg"]` e fim, `a.jpg` em nenhuma delas; terminada a gravação, `["a.jpg", "b.jpg", "c.jpg", "d.jpg"]`
numa página só. Mutações: voltar `state <> 'DELETED'`; aceitar `PENDING` sem a conferência de versão —
**as duas derrubam**.

### 4.9 O espaço reservado em partes conta na abertura, não só na gravação (ciclo 89)

O ciclo 87 fez a cota morder no **envio** de cada parte. Faltava a outra ponta: a **abertura** do
próximo upload (`beginUpload`) olhava só o que já havia virado objeto. Quem tinha a cota inteira em
partes de multiparts abertos abria outro upload sem recusa — a chave nascia `PENDING`, o corpo
atravessava a rede e só a gravação recusava, depois de o participante ter pago o upload inteiro. O
limite deixava de ser um limite.

Agora as três medidas do multipart usam **uma conta só** (`exigirCotaDoParticipante`):

| momento | a conta | para quê |
|---|---|---|
| abertura (`beginUpload`) | `usado + partes abertas` **≥** cota → recusa | a chave nem nasce; o corpo nem vem |
| envio da parte (`UploadPart`) | `usado + partes abertas + esta parte` > cota → recusa | antes de ler (`Content-Length`) ou de gravar |
| conclusão (`CompleteMultipartUpload`) | `usado + corpo montado` > cota → recusa | **sem** somar as partes deste upload |

O `>=` na abertura é o da regra original: uma conta exatamente no limite não abre upload que só pode
terminar em `507`. Com uma gravação em curso, a conta é `>` — cabe exatamente o que sobra.

**A conclusão não soma as próprias partes.** Elas **são** o corpo que está chegando, já cobradas no
envio. Somá-las outra vez fazia dois multiparts de 10 MiB numa cota de 20 MiB nunca concluírem: o
primeiro somava 10 (seu) + 10 (o outro) contra a cota e recusava, com o corpo inteiro já montado em
disco. `beginUpload(..., cobrarPartesReservadas=false)` e `finishUpload(..., false)` existem para isso.

**A recusa da abertura fala S3.** O `PUT` simples traduzia a cota no `catch` da *gravação*, que a
abertura nunca alcança: o `507` de lá saía no JSON do Control Plane, e o SDK não reconhece o `Code`.

**Verificação:** `S3MultipartTests` **+2** — 20 MiB reservados em dois multiparts: o `PUT` de 4 MiB
recebe `507 QuotaExceeded` com `20971520 em partes abertas` e **a chave não nasce**; abortado um, a
mesma gravação passa. Dois multiparts de 10 MiB: o primeiro **conclui** com o corpo de 10 MiB gravado.
Mutações: a abertura ignora o reservado; a conclusão cobra as partes de novo; a abertura sem a tradução
ao S3 — **3 de 3 derrubam**.

### 4.10 A guarda de cópia do ciclo 90 (histórico)

Até o ciclo 90, o cabeçalho `x-amz-copy-source` era **ignorado** no `PUT` e no
`PUT ?partNumber=&uploadId=`: com o corpo vazio da cópia, a resposta era `400` — como se o cliente
tivesse enviado um arquivo ou uma parte vazia — e, com corpo, os bytes do cliente viravam a "cópia".
O ciclo 90 fez o gateway recusar com `501 NotImplemented` o que ainda não sabia fazer; os ciclos 108 e
109 implementaram as duas operações — `CopyObject` (§4.19) e `UploadPartCopy` (§4.20). **A guarda não
existe mais**, e os testes que a fixavam foram substituídos pelos das duas operações.

**Verificação:** `S3GatewayTests` cobre a cópia inteira (§4.19) e `S3MultipartTests` cobre a parte
copiada com e sem intervalo (§4.20).

### 4.11 O corpo montado sai do disco quando a conclusão falha (ciclo 91)

A conclusão do multipart monta as partes num arquivo só, dentro de
`<storageRoot>/multipart/<uploadId>/`, e só então chama o `finishUpload`. O `finally` que apagava
esse temporário cobria apenas a releitura do arquivo. Quando a **gravação** recusava — cota do
participante, falha de I/O do catálogo, disco cheio —, a exceção subia por fora dele e o corpo
inteiro, em claro, ficava no diretório do upload.

O varredor de sobras não o recolhia: ele só apaga diretório **sem** upload correspondente, e a linha
do upload existe (o cliente ainda pode enviar outra parte). O upload aberto passava a ocupar o dobro
do próprio tamanho, sem que a cota, o catálogo ou o relatório soubessem.

Agora a montagem, o sha do arquivo, a abertura e a gravação estão num `try` só, com a limpeza no
`catch` (qualquer `RuntimeException`) e no caminho de sucesso. O diretório do upload fica com as
partes — e só com elas — até o cliente concluir ou abortar.

**Verificação:** `StorageMultipartBodyCleanupTests` **+2** — a conclusão com a gravação recusando
deixa o diretório com `part-1` e nenhum `.montado-*.tmp`; a conclusão recusada antes da montagem
(parte que não chegou) nem cria o diretório. Mutação: devolver o `finally` só na releitura (sem a
limpeza no `catch`) — **derruba** ("but some elements were not expected": o `.montado-0xxx.tmp`).

### 4.12 A lista de partes da conclusão é estritamente crescente (ciclo 92)

O `CompleteMultipartUpload` recebe a lista de partes na ordem em que elas formam o arquivo. A
conclusão aceitava **qualquer** sequência cujos números existissem. Com `[1, 1]` ela montava
`part-1` duas vezes e gravava um objeto com o dobro do conteúdo do upload: o cliente recebia `200` e
um arquivo que não mandou. Numeração fora de ordem (`[2, 1]`) era aceita do mesmo jeito e montava o
corpo em ordem diferente da que a lista descreve.

O S3 exige lista **estritamente crescente** e recusa com `InvalidPartOrder`. Agora é o mesmo: número
que não cresce em relação ao anterior recebe `400 InvalidPartOrder`, antes de qualquer montagem — o
upload segue aberto, com as partes dele.

**Verificação:** `S3MultipartTests` **+1** — `[1, 1]` recebe `400 InvalidPartOrder`, nada é gravado
(`network_storage_objects` sem a chave) e o upload continua com a parte; a conclusão correta depois
grava o tamanho certo (6 MiB + 1 byte, não o dobro). Mutação: tirar a guarda de ordem — **derruba**.

### 4.13 A leitura de objeto confirma o formato do S3 também quando falta a cópia (ciclo 93)

O `GET` e o `HEAD` respondiam com o JSON interno do control plane quando o catálogo dizia que o objeto
existe e **nenhuma cópia estava registrada** (`STORAGE_PLACEMENT_MISSING`). O SDK S3 lê o `Code` do
XML; um documento que ele não reconhece vira falha opaca, e o cliente não distingue "tente de novo" de
"não existe". A tradução de vocabulário só cobria o `PUT`, porque nas outras rotas a recusa nasce
dentro do serviço de conteúdo, não no controller.

Agora as três rotas passam a recusa pela mesma tradução (`comVocabularioDoS3`), e esse estado responde
`409` com `<Code>InternalError</Code>` e a mensagem de "confirmado no catálogo, sem cópia registrada;
tente de novo" — que é o que o cliente precisa para tentar outra vez em vez de desistir.

**Verificação:** `S3GatewayTests` **+1** — objeto confirmado com as localizações removidas responde
`409` com `<Code>` no `GET` (sem `STORAGE_PLACEMENT_MISSING` no corpo) e `409` com
`Content-Type: application/xml` no `HEAD`, que não tem corpo. Mutações: tirar a tradução inteira do
`GET` e do `HEAD` — **2 de 2 derrubam**. Estado real de onde vem: a falha que o registro da cópia pode
deixar depois de o objeto ser confirmado.

## 4.14 `delimiter` agrupa em `CommonPrefixes` (ciclo 98)

`GET /{bucket}?list-type=2&delimiter=/` era aceito e **ignorado**: toda chave saía como
`<Contents><Key>…</Key></Contents>`, inclusive as de dentro de subpasta. A maioria dos clientes
(rclone, boto3, aws-cli e os SDKs em geral) pede `delimiter=/` por padrão para navegar "pastas"; com
o parâmetro ignorado, o que devia ser a pasta `fotos/2025/` aparecia como centenas de arquivos soltos,
e a navegação nunca oferecia a subpasta.

### Agora

- chave que tem o delimitador **depois do `prefix`** não vira `Contents`: o trecho até a primeira
  ocorrência vira `<CommonPrefixes><Prefix>…</Prefix></CommonPrefixes>`, uma vez por grupo, na ordem
  das chaves;
- o que está direto no `prefixo` continua `Contents` (`prefix=fotos/&delimiter=/` e a chave
  `fotos/raiz.jpg` é chave, não grupo);
- o `<Delimiter>` ecoa na resposta;
- **cada grupo conta como uma entrada do `MaxKeys`**, como no S3, e o `NextContinuationToken`/
  `NextMarker` é o último emitido — chave ou prefixo. Sem essa conta, uma página de `max-keys=2`
  com dez arquivos dentro do mesmo grupo estouraria o teto, e o token repetiria o grupo na página
  seguinte: um cliente que pagina nunca termina. Por isso a leitura do banco pede mais linhas quando
  há agrupamento (cada grupo pode consumir várias), limitada a `MAX_LIST_LIMIT`.

`delimiter` vazio equivale a ausente, e qualquer delimitador de um caractere vale (`/` é o comum).

**Verificação:** `S3GatewayTests` **+2** — `prefix=fotos/&delimiter=/` devolve o grupo `fotos/2025/` e
`fotos/2026/` uma vez cada (e as chaves deles fora de `Contents`), com `KeyCount` contando chave +
prefixo; e a paginação com `max-keys=2` entrega `p/a.txt` + `p/sub/` na primeira página
(`IsTruncated=true`, token `p/sub/`) e `p/z.txt` na segunda. Mutações: grupo não consumindo entrada do
`MaxKeys`, e o grupo não agrupando (comportamento antigo) — **2 de 2 derrubam**.

## 4.15 `DeleteBucket` apaga de verdade (ciclo 100)

Até aqui a rota respondia **`501 NotImplemented` em todos os casos**, com a justificativa "não há bucket
vazio a apagar". A premissa estava errada: o Storage não tem tabela de buckets — o nome **existe
enquanto tem objeto vivo** (`bucketsOf` filtra `state <> 'DELETED'`) —, então um bucket cujas chaves
foram todas removidas continuava aparecendo em `ListBuckets` e o cliente não tinha como tirá-lo. Pior:
depois da última lápide, o dono recebia `403 AccessDenied`, porque a conferência de donagem usava a
mesma lista de vivos — ele **perdia o próprio bucket** ao esvaziá-lo. A rota não tinha nenhum teste.

### Agora

| situação | resposta |
|---|---|
| bucket com objeto **vivo** | `409 <Code>BucketNotEmpty</Code>` |
| bucket **sem objeto vivo**, com objetos do dono no catálogo (vivo ou lápide) | `204`, e o nome sai de `ListBuckets` |
| bucket que o dono da credencial **nunca gravou** | `403 <Code>AccessDenied</Code>` |

A donagem do `DELETE` é provada por `bucketHasObjectsOf(bucket, dono)`, que conta **vivo e lápide**:
é o que sobra depois que o dono esvazia o bucket. O `bucketsOf` (só vivo) continua sendo a resposta
certa para ler e gravar, e a errada para apagar.

O `204` não apaga linha do catálogo: o bucket "deixou de existir" no instante em que ficou sem objeto
vivo, que é a mesma regra que o esconde do `ListBuckets`. Não há tabela de bucket a remover — e não
precisa haver.

**Verificação:** `S3GatewayTests` **+3** — `DeleteObject` seguido de `DeleteBucket` responde `204` e o
nome some de `ListBuckets`; bucket com objeto vivo responde `409 BucketNotEmpty`; bucket que o dono
nunca gravou responde `403 AccessDenied`. Mutações: voltar ao `501` incondicional, conferir donagem só
pela lista de vivos, e remover a recusa `409` — **3 de 3 derrubam**.

## 4.16 Toda recusa do Storage sai em XML (ciclo 101)

O gateway traduzia **duas** recusas vindas dos serviços (`QuotaExceeded` e
`STORAGE_PLACEMENT_MISSING`); todas as outras saíam como o **JSON do Control Plane**. O SDK escolhe o
deserializador pelo `Content-Type`: JSON num gateway S3 chega como corpo irreconhecível, e o cliente
trata como falha de rede ou erro opaco em vez de agir — `NoSuchUpload` (recomece o upload),
`InvalidArgument` (o `expires` pedido é longo demais), `InvalidPartOrder` (reenvie na ordem), etc.

Rotas afetadas, na prática: `ListParts`/`AbortMultipartUpload` de upload inexistente ou vencido,
`UploadPart` de upload vencido, `CompleteMultipartUpload` sem parte / fora de ordem, e o `POST
?uploads` com `expires` fora do intervalo (ou não numérico).

### Agora

`noVocabularioDoS3` traduz **qualquer** `ApiException` que não seja uma das duas específicas para
`S3ApiException` mantendo o status e o `Code`. Os códigos internos dos serviços de Storage são, na
maioria, os próprios do S3 (`NoSuchUpload`, `InvalidArgument`, `MalformedXML`, `InvalidPart`,
`InvalidPartOrder`), então o cliente os reconhece. Os que não são (`STORAGE_*`) saem com o status certo
e um `Code` que ele não conhece — ainda assim XML, e o status diz se vale retentar.

O `POST ?uploads` também passou a traduzir a recusa do `create` e a recusar `expires` não numérico com
`400 InvalidArgument` no XML (antes era um `NumberFormatException` → `500`).

**Verificação:** `S3MultipartTests` **+2** — `ListParts` de upload inexistente responde `404` com
`Content-Type: application/xml`, corpo começando em `<?xml` e `<Code>NoSuchUpload</Code>` (e sem
`"code"`); `POST ?uploads` com `expires=99999999` e com `expires=abc` responde `400
<Code>InvalidArgument</Code>` em XML. Mutações: remover o `catch` do `create`, remover o `catch` do
`uploadDaChave` (`require` cru), e devolver a `ApiException` crua na tradução geral — **3 de 3
derrubam**.

## 4.17 Objeto de zero byte é aceito (ciclo 105)

O `PUT` com corpo vazio respondia **`400 STORAGE_EMPTY_BODY`**. A regra é da fase 1 (doc 12 §2.2):
o upload **do nó** com corpo vazio é sinal de gravação que não aconteceu, e recusá-lo evita objeto
sem conteúdo no catálogo. Mas o gateway S3 é compatível com o S3, e o S3 **aceita** 0 byte: é o
marcador de "pasta" que `aws s3 cp --recursive` e `rclone` criam, e o resultado de uma escrita vazia
legítima. Um cliente que sincronizava uma árvore inteira recebia `400` em cada marcador — e tratava a
sincronização toda como falha, com todos os arquivos já gravados.

### Agora

- `PUT` com corpo vazio pelo **gateway** grava o objeto com `bytes = 0`, ETag do corpo vazio
  (`sha256("")`), réplica 0 `OK` e designação de cópia como qualquer outro;
- o upload **do nó** (rota do nó, `store`) continua recusando `STORAGE_EMPTY_BODY` — ali o corpo
  vazio é falha de transporte, não um arquivo;
- `HEAD` responde `200` com `Content-Length: 0`; `GET` responde `200` com corpo vazio.

**Verificação:** `S3GatewayTests` **+1** — `PUT` de 0 byte é `200`, `HEAD` devolve `Content-Length: 0`
e `GET` devolve `200` sem bytes. Mutações: o gateway voltar a recusar 0 byte (derrubada pelo teste
novo) e a regra do vazio ser liberada também para o nó (derrubada por `StorageObjectCeilingTests`) —
**2 de 2 derrubam**.

## 4.19 CopyObject copia no servidor (ciclo 108)

`aws s3 cp --recursive`, o `rclone copy` e o `aws s3 sync` copiam **no servidor**: o cliente manda só
o nome da origem e o do destino, e os bytes não passam pela rede até ele. Até o ciclo 107 o gateway
recusava o cabeçalho com `501 NotImplemented` (§4.10), então copiar sem baixar e reenviar não existia:
quem não caía para o caminho lento falhava no item, e quem caía gastava a banda duas vezes.

### Agora

- `PUT /s3/{bucket}/{chave}` com `x-amz-copy-source: /{bucket}/{origem}` e corpo vazio copia o objeto.
  A origem é lida por `readBody` — o mesmo caminho do download, que só entrega o que o catálogo tem
  como servível e confere a réplica 0 — e atravessa o **mesmo** `finishUpload` do `PUT`. Nada é
  carregado em memória: o corpo vai do disco de origem ao temporário da gravação em blocos de 64 KiB.
- A resposta é o XML do S3:

  ```xml
  <CopyObjectResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
    <ETag>"sha256 do destino"</ETag><LastModified>...</LastModified>
  </CopyObjectResult>
  ```

  O ETag é o **do que foi escrito**, não uma promessa copiada da origem.
- **Metadado:** `x-amz-metadata-directive: COPY` (padrão) traz `Content-Type`, `x-amz-meta-*`,
  `Cache-Control` e `Content-Disposition` da origem; `REPLACE` usa o que veio na requisição de cópia.
  Em `REPLACE`, `Cache-Control` e `Content-Disposition` ausentes voltam à política do tipo — não herdam
  os da origem.
- **Condições da origem:** `x-amz-copy-source-if-match` e `-if-none-match` são conferidos contra o
  ETag; divergência é `412 PreconditionFailed` e nada é gravado. As condições por data respondem `501`
  em vez de serem ignoradas — uma condição aceita e não aplicada seria uma resposta errada com cara de
  certa. `?versionId=` também é `501`: a versão corrente é a única que o gateway endereça.
- **Donos:** a origem tem de ser do dono da credencial (mesma regra do destino). Origem de outra conta
  é `403 AccessDenied` **sem** reservar a chave de destino; origem inexistente é `404 NoSuchKey`
  (o código do S3, não o interno `STORAGE_NOT_FOUND`).
- **Mesmo objeto:** copiar a chave sobre ela mesma é `400 InvalidRequest` — o S3 só permite com troca
  de metadado, e aqui a troca é um `PUT`.
- **Chave ocupada:** copiar para uma chave com cópia confirmada é `409 ObjectAlreadyStored`, como no
  `PUT`. A recusa acontece **antes** de `saveClientHeaders`: o objeto que a resposta diz não ter sido
  tocado continua com o conteúdo **e o metadado** de antes. Em bucket versionado, a cópia cria a
  versão nova, também como o `PUT`.
- **Origem codificada:** o cabeçalho é um caminho, não uma query — `%2B` e `+` literal são os dois a
  chave com mais, e `%20` é espaço. A instalação aceita as duas formas de chave (a que o `PUT` guardou
  na URI e a já decodificada) e procura as duas antes de responder `404`.
- **Contabilidade:** a leitura da origem conta `GET` e a gravação do destino conta `PUT` no painel
  (§5.1); não é uma operação nova, é um `GET` e um `PUT` que o cliente não precisou trafegar.

**Verificação:** `S3GatewayTests` **+8** — cópia grava os bytes e devolve o `CopyObjectResult` com o
ETag do destino; `COPY` traz metadado e `REPLACE` troca o declarado sem trocar o corpo; origem
inexistente é `404 NoSuchKey` sem linha de destino; origem de outra conta é `403` sem gravar; cópia
sobre si mesma é `400` e preserva o conteúdo; `+` literal e `%2B` chegam à mesma chave; `if-match`
divergente é `412` sem gravar; chave já confirmada é `409` e o objeto mantém conteúdo e metadado.
Mutações: corpo da cópia vazio (o teste do conteúdo derruba), dono da origem não conferido (o teste
das duas contas derruba), `REPLACE` ignorado (o teste do metadado derruba) e a tradução de
`STORAGE_NOT_FOUND` para `NoSuchKey` removida (o teste da origem ausente derruba) — **4 de 4
derrubam**.

## 4.20 UploadPartCopy copia um intervalo para dentro do multipart (ciclo 109)

O `aws s3 cp` acima de 8 MB e o `rclone` copiam arquivos grandes em partes: cada `UploadPart` pode vir
da origem em vez do corpo do cliente. Até o ciclo 108 essa parte respondia `501` (§4.10) — o mesmo
arquivo que o `CopyObject` já copiava de uma vez não podia ser copiado em pedaços.

### Agora

- `PUT /s3/{bucket}/{chave}?partNumber=&uploadId=` com `x-amz-copy-source` grava a parte a partir da
  origem. `x-amz-copy-source-range: bytes=primeiro-ultimo` (inclusive, base zero) recorta o intervalo;
  sem o cabeçalho, a parte é o objeto inteiro.
- O recorte é lido por `readBody` (mesma régua do download: réplica 0 servível) e entra pelo mesmo
  `receivePart` do envio direto — cota pelo tamanho anunciado, digest, temporário e reenvio da mesma
  parte substituindo. Nada é carregado em memória: o fluxo é limitado ao intervalo e lido em blocos de
  64 KiB.
- A resposta é o `CopyPartResult` do S3:
  `<ETag>"sha-256 da parte"</ETag><LastModified>...</LastModified>`.
- A origem segue a mesma régua do `CopyObject` (§4.19): dono da credencial, escopo `allowedBucket`,
  `if-match`/`if-none-match` → `412`, condições por data e `versionId` → `501`. Origem ausente é
  `404 NoSuchKey`.
- Intervalo fora do objeto, invertido ou malformado é `400 InvalidArgument`, e **nenhuma parte** é
  gravada. A conclusão continua aplicando o mínimo de 5 MiB às partes intermediárias — um intervalo
  menor que isso só vale como última parte.

**Verificação:** `S3MultipartTests` **+4** — intervalo `bytes=2-5` de `0123456789` conclui o objeto
`2345` e o XML traz o ETag do recorte; sem intervalo, a parte é o objeto inteiro; origem ausente é
`404 NoSuchKey` sem linha em `network_storage_multipart_parts`; `bytes=5-99` e `bytes=9-2` são
`400 InvalidArgument` sem parte. Mutações: ignorar o início do intervalo; ignorar o fim (gravar o
resto da origem); não conferir o limite superior; responder só o `ETag` sem o XML — **4 de 4
derrubam**.

## 5. Erros

O corpo é XML no formato do S3, com o `Code` que o SDK lê:

```xml
<?xml version="1.0" encoding="UTF-8"?><Error>
  <Code>SignatureDoesNotMatch</Code><Message>...</Message><Resource>...</Resource>
</Error>
```

Códigos: `AccessDenied`, `InvalidAccessKeyId`, `SignatureDoesNotMatch`, `NotImplemented`,
`InvalidArgument`, `InvalidRequest`, `NoSuchKey`, `PreconditionFailed` e `ObjectAlreadyStored`.

## 5.1 Indicadores de operação no painel (V366)

O §17 do brief pede **operações por segundo** e **latência**. Até a V366 o painel não tinha nenhum dos
dois — não por falta de tela, mas porque **nada contava operação**: capacidade, uso e integridade são
estado que o catálogo já grava; operação é evento.

Agora cada operação do catálogo grava uma linha por `(minuto, operação, resultado)`:

| campo | o que é |
|---|---|
| `operation` | `PUT`, `GET`, `HEAD`, `DELETE`, `LIST` — os verbos que o gateway e o painel usam de fato |
| `outcome` | `OK`, `CLIENT` (4xx) ou `SERVER` (5xx). Separar os dois é o que distingue "clientes quebrados" de "Storage falhando" sem abrir log |
| `req_count` | quantas operações no minuto |
| `total_ms` | soma dos milissegundos — a média sai **na leitura** (`total_ms / req_count`) |
| `max_ms` | o pior caso do minuto |

**Por que soma e não média no banco.** Gravar a média por minuto e depois tirar a média das médias
pesa um minuto parado igual a um minuto de pico: 10 operações de 10 ms e 1 de 1000 ms dariam 505 ms em
vez dos 100 ms verdadeiros. O teste fixa exatamente esse número.

**Por que não cada requisição.** A tabela é série, não log. Um SDK que lista mil vezes no mesmo minuto
soma na mesma linha (UPSERT atômico no banco — duas instâncias do Control Plane no mesmo minuto não se
perdem).

Leitura: `GET /admin/network/storage/operations?minutes=15` (teto de 1440). Devolve `from`, `to`,
`seconds` (o tempo medido; **zero** quando não houve operação), `operations`, `opsPerSecond`,
`averageMs`, `maxMs` e os mapas `byOperation` / `byOutcome`. Janela fora de 1–1440 é **recusada com
400**, não truncada: quem pediu 30 dias e recebeu 1 acha que a instalação parou.

Falha ao registrar não derruba a operação do usuário — a métrica é observação, e perder uma amostra
não pode virar erro de upload. A retenção (30 dias, `rede.storage.metrics.retention-days`) é varrida
uma vez por dia por `StorageOperationMetricsWorker`; sem ela o `purge` seria código morto.

Prova: `StorageOperationMetricsTests` (9 — conta ponderada, "sem dado" ≠ zero, 400/500 separados,
normalização para `OTHER`, UPSERT, retenção, falha que não propaga), `StorageCatalogMetricsTests` (7 —
a fiação no caminho real: GET, HEAD independente de GET, LIST, 5xx como `SERVER`, exceção não-`ApiException`
como `SERVER` e nunca `OK`, catálogo sem contador continua servindo),
`AdminNetworkStorageMetricsTests` (6 — rota, banco e guarda de admin) e
`scripts/tests/storage-operations-migration-test.sh` (V366 aplicada **duas vezes** em MariaDB 11.4 real,
UPSERT somando 10 gravações no mesmo minuto, `max_ms` preservado, média ponderada na consulta,
retenção). Mutações conferidas: média das médias, "sem dado" virando a janela inteira, 500 contado como
`CLIENT`, `LIST` sem contagem e chave primária sem `outcome` — todas derrubam.

## 5.1.1 O multipart conta no mesmo painel (ciclo 103)

Até aqui só o catálogo de objetos registrava operação (`PUT`/`GET`/`HEAD`/`DELETE`/`LIST`). O
`StorageMultipartService` — que serve justamente os arquivos grandes — **não registrava nada**: um
cliente enviando gigabytes em partes aparecia no painel com "sem operação", e a operação que mais
consome banda era a única invisível. O teto de requisição por Access Key §5.2 conta as mesmas rotas.

### Agora

| rota | verbo contado |
|---|---|
| `POST /{bucket}/{key}?uploads` (abrir) | `PUT` |
| `PUT /{bucket}/{key}?partNumber=` (cada parte) | `PUT` |
| `GET /{bucket}/{key}?uploadId=` (ListParts) | `LIST` |
| `DELETE /{bucket}/{key}?uploadId=` (Abort) | `DELETE` |
| `POST /{bucket}/{key}?uploadId=` (concluir) | **não conta no sucesso** |

A conclusão não conta em separado porque a gravação do objeto já conta `PUT` no catálogo — contar de
novo faria todo upload grande valer dois. Ela conta **quando falha**, que é o que o painel precisa ver.
A leitura interna das partes na conclusão usa `doParts` (sem contagem): `LIST` ali seria uma listagem
que ninguém pediu.

Resultado é `OK`, `CLIENT` (4xx) ou `SERVER` (5xx), como no catálogo. Serviço construído sem contador
continua servindo.

**Verificação:** `S3MultipartTests` **+1** — abrir + uma parte + `ListParts` + concluir produzem
**4** amostras novas (antes: **1**, só a do catálogo na conclusão). Mutações: abrir sem contar, ListParts
sem contar, parte sem contar — **3 de 3 derrubam**.

## 5.2 Teto de requisição por Access Key (ciclo 74)

O doc 19 §3.2 já limitava **assinatura que falhou** (`s3-auth`, 20 por 5 min): protege o segredo
contra chute. Nada limitava o consumo de quem **provou** o segredo — e todo upload e download do
gateway passa pelo mesmo Control Plane. Uma credencial vazada, ou um cliente em laço, ocupava a
instalação inteira para todo mundo.

Agora há uma segunda conta, separada de propósito:

| ação | chave da conta | padrão | o que conta |
|---|---|---|---|
| `s3-request` | Access Key | 600 / minuto | requisição **assinada e válida** (cabeçalho **ou** URL pré-assinada) |

- **Contas separadas, não uma só.** A `s3-auth` só soma falha — se o acerto gastasse o mesmo balde, o
  cliente legítimo seria barrado pelo próprio sucesso. A `s3-request` soma a requisição aceita.
- **O ritmo é conferido depois da assinatura**, nunca antes: uma assinatura inválida não consome o
  orçamento de quem é legítimo, e a recusa por assinatura continua com o código do protocolo (`403`),
  não com o do teto.
- **O balde é da credencial, não do IP.** Quem ataca troca de IP; a Access Key, não. E um escritório
  atrás do mesmo IP não paga pelos vizinhos.
- **A URL pré-assinada entra na mesma conta.** Ela é a credencial portada por quem recebeu o link;
  fora da conta, bastaria emitir um link para furar o teto.
- **`503 SlowDown`**, que é o código do S3 para "reduza o ritmo" — o mesmo que a AWS devolve. Passada a
  janela, o cliente volta a ser servido sem intervenção.
- **Padrão folgado (600/min) e desligável (`0`).** Um SDK em multipart manda uma parte por requisição;
  barrar cliente são custa mais que o laço que se quer impedir. É proteção contra desperdício, não
  contra um atacante que já tem o segredo legítimo — para esse, o caminho é revogar a credencial.

**Verificação:** `AuthAttemptRateLimitTests` +2 (o teto é da credencial: outra Access Key continua
servida e a conta de assinatura não foi gasta; passada a janela, volta). `S3RateLimitTests` **5** pela
rota, com `s3-request.limit=3` (a credencial recebe `503 SlowDown` e a vizinha continua servida; a
requisição válida não gasta o orçamento de assinatura; a URL pré-assinada entra na conta; o `GET`
também entra; assinatura inválida continua na conta própria). Mutações (**6 de 6 derrubam**): sem
ritmo no cabeçalho; sem ritmo na URL; ritmo antes da validação; um balde só para toda credencial; `503`
trocado por `403`; e ritmo só no caminho do cabeçalho.

## 6. O que NÃO está entregue

- **Presigned URLs para `GET` e `PUT` existem**; `DELETE`/`LIST` por URL não são emitidos de
  propósito (link vazado não apaga nem lista). URL pré-assinada **por parte** do multipart não existe:
  o upload multipart é feito com credencial, não por link.
- **Credenciais temporárias (STS)** — `X-Amz-Security-Token` não é processado.
- **Cota de espaço por credencial** — a cota é **por participante**, não por Access Key
  (`rede.storage.max-bytes-per-owner`, doc 12 §2.3): o upload que estoura responde `507 QuotaExceeded`
  no XML do S3, com o número na mensagem. Duas credenciais do mesmo dono dividem a mesma cota.
  O **rate limiting por credencial existe** desde o ciclo 74 (§5.2).