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
  não anunciados (o `PUT` que os gravaria responde `501` e não altera o objeto — §4.21).
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
- `restorePending(id, retrato…)` — devolve à chave que **já existia** o retrato que ela tinha antes
  da gravação: estado, conteúdo, digest, tipo, metadado, `Cache-Control`, `Content-Disposition` e
  prazo (§4.22). O `beginUpload` devolve a linha que o `finishUpload` vai gravar, então reler a chave
  *depois* de gravar daria o objeto recém-gravado e a donagem passaria sempre; por isso a pergunta é
  sobre **o bucket**, e por isso o retrato é lido **antes** do `saveClientHeaders` — que troca o que a
  linha declarava pelos cabeçalhos desta requisição.

Uma lápide de outra conta é recusa **antes** de abrir a chave (`recusarChaveDeOutraConta`): a chave
apagada não volta para quem não a apagou (§4.22).

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

## 4.21 Subrecurso que gravaria vira `501` (ciclo 107)

O `PUT` era despachado pela **chave da rota**, sem olhar a consulta. Um `PUT ?tagging` — a operação
com que o S3 grava etiquetas — abria um upload da chave, gravava o **XML da etiqueta** como corpo,
substituía o arquivo de verdade e respondia `200`, que é o "sucesso" da etiquetagem no S3. O cliente
via sucesso e o objeto ficava com o XML dentro. O mesmo valia para `?acl`, `?retention`, `?legal-hold`
e `?object-lock`.

### Agora

| pedido | resposta |
|---|---|
| `PUT ?tagging`, `?acl`, `?retention`, `?legal-hold`, `?object-lock` | **`501 NotImplemented`** (XML do S3, com o subrecurso), **nada gravado** |
| `GET ?acl`, `?tagging`, `?retention`, `?legal-hold`, `?object-lock` | **`501 NotImplemented`**, nenhum byte servido |
| `DELETE ?tagging` (e os demais da lista) | já respondia `501`: o `DeleteObject` apaga o objeto, não a etiqueta |

A recusa vem **antes** do catálogo: nem linha `PENDING`, nem corpo medido. A lista vizinha (ecoada em
`200` sem gravar — `?versioning`, `?encryption`, `?logging`…) continua como era: o que distingue os dois
grupos é o que acontece sem guarda. Ali um corpo vazio é confundido com objeto; aqui o objeto é
sobrescrito.

**O `GET` tinha a mesma porta.** `GET ?acl` é a leitura da ACL do objeto, mas a rota servia o
**arquivo** em `200`: o cliente lia "a ACL é o conteúdo do arquivo". O `GET` passou a usar a mesma
lista do `PUT`, antes da leitura do objeto.

**A lista é ordenada.** A mensagem cita **um** subrecurso; com dois na mesma requisição
(`?tagging&acl`), a ordem de um `Set.of` não é contrato — a mesma requisição responderia uma coisa ou
outra. Agora é `List` fixa e o primeiro da ordem responde.

**O corpo do pedido é drenado.** A recusa acontece antes de o corpo ser lido, e o corpo do `?tagging`
é o XML da etiqueta. Responder `501` com o corpo pendente, em conexão persistente, faz o servidor
fechar a conexão no meio de um pedido (RFC 9110 §15.6.2); `drenarCorpo` lê e descarta o corpo antes da
resposta. Um cliente que já cortou o envio ao receber a recusa não muda a resposta.

**Verificação:** `S3GatewayTests` **+5** — (1) `PUT ?tagging` é `501` e o `HEAD` seguinte devolve o
**mesmo ETag**; (2) `PUT ?tagging` **com** `x-amz-copy-source` também é `501`, com o destino
inexistente; (3) `?tagging&acl` responde citando `tagging` (ordem determinística); (4) `GET ?acl`
é `501` e o objeto fica com o mesmo ETag; (5) `drenarCorpo` lê o corpo inteiro (fluxo que conta as
leituras). Mutações: tirar a guarda do `PUT` (1 e 2 derrubam), pôr a guarda **depois** do desvio para
o `CopyObject` (2 derruba) e tirar a guarda do `GET` (4 derruba: responde `200`).

A guarda roda **antes** do desvio para o `CopyObject` (§4.19): um `PUT ?tagging` com
`x-amz-copy-source` não copia — o subrecurso continua `501`, e nada é gravado no destino. A guarda de
cópia que o ciclo 90 tinha no `PUT` foi substituída pela operação de verdade (§4.19); a do
`UploadPartCopy` ficou só no caminho da parte.

## 4.22 A recusa por donagem devolve o objeto de antes (ciclo 110)

O `PUT` recusa gravar num bucket que tem objeto de outra conta — a conferência é feita **depois** da
gravação (é a gravação que dá dono ao bucket) e, quando recusa, desfaz. A desfeita tinha três furos:

1. **O objeto do dono ficava sem corpo.** A volta era só `STORED → PENDING` com `bytes` e `sha256`
   apagados, e o tipo, o metadado e o nome de anexo ficavam os da gravação recusada. O objeto saía da
   listagem, respondia `404` no `GET` e no `HEAD`, e o `PENDING` que sobrava não era reescrevível —
   a gravação seguinte devolvia `409 ObjectAlreadyStored`.
2. **O metadado do dono era apagado antes da recusa.** O `saveClientHeaders` da nova gravação troca o
   que a linha declarava pelo desta requisição; um `PUT` sem `x-amz-meta-*` apagava o do dono.
3. **A lápide não ocupava a chave.** Depois de um `DELETE` do dono, um `PUT` de outra conta na mesma
   chave passava pela conferência (a lápide não conta como objeto vivo, e o bucket passava a "ser sem
   dono"): a chave ficava **inteira** com o objeto do intruso — tipo, metadado e nome de anexo — e a
   resposta era `200`. O `DELETE` não entregava o nome da chave.

### Agora

- A devolução usa o **retrato da linha antes do `saveClientHeaders`**: estado, conteúdo, digest, tipo,
  metadado, `Cache-Control`, `Content-Disposition` e prazo, **e os bytes**: o `finishUpload` guarda o
  corpo que estava na chave antes de substituí-lo, e a devolução o move de volta ao lugar. Sem isso, o
  `HEAD` reportava o retrato do dono (12 bytes, `ETag` antigo) e o `GET` servia o corpo da gravação
  recusada — o objeto voltava pela metade. O backup só é descartado quando a resposta é `200`. Um
  objeto que tinha corpo volta a `STORED`; uma linha sem corpo volta a `PENDING`. A linha é apagada
  só quando não tinha corpo — a chave nova de outra conta, que não deve ficar (é ela que daria o
  bucket ao intruso).
- A lápide é uma **tomada de posse da chave**: escrever numa chave apagada por outra conta é
  **`403 AccessDenied`**, conferido **antes** de abrir a chave — nem linha criada, nem corpo medido.
  A guarda é da escrita: o `GET` de uma lápide continua `404 NoSuchKey`.

**Verificação:** `S3GatewayTests` **+2**.

- `put_recusado_por_donagem_devolve_o_objeto_que_ja_estava_na_chave` — linha `STORED` do dono
  estacionada como `PENDING`, outra conta no bucket, o dono regrava: `403 AccessDenied` e o `HEAD`
  devolve `200` com o tamanho, o `ETag`, o tipo, o metadado, o `Cache-Control` e o nome de anexo de
  antes **e o `GET` devolve exatamente `"meu conteudo"`** — o `HEAD` prova os cabeçalhos, o `GET` prova
  os bytes. Mutações que a derrubam (todas verificadas): estado devolvido sempre `PENDING`; corpo
  zerado na devolução (`409 ObjectAlreadyStored` no reenvio); cabeçalhos de antes não devolvidos; sem a
  guarda do corpo, o `GET` devolve `"conteudo novo"` (o da gravação recusada).
- `chave_apagada_por_um_dono_nao_aceita_escrita_de_outra_conta` — o dono do bucket apaga a chave,
  outra conta grava a própria chave nele e tenta a chave apagada: `403 AccessDenied`, e a lápide fica
  com o autor, o digest, o tipo e o metadado de antes, sem o nome de anexo de quem tentou. Mutação que
  a derruba: sem a guarda da lápide a resposta é `200` e a chave fica do intruso.

## 4.23 A lápide própria lê `404`, não `403` (ciclo 111)

O `GET` e o `HEAD` provavam donagem por `bucketsOf`, que filtra `state <> 'DELETED'`. O `DELETE`
grava **lápide** (`DELETED`), então apagar a **última** chave viva do bucket tirava o bucket do dono
para a leitura dele mesmo: o `GET` e o `HEAD` da chave apagada respondiam
**`403 AccessDenied` — "Este bucket nao e do dono da credencial"**, como se o bucket fosse de outra
conta. O dono perdia o próprio objeto pelo nome errado, e só o `DeleteBucket` (ciclo 100) usava a régua
certa. O teste que existia aceitava `403` **e** `404` (`isIn(403, 404)`), então a regressão passava.

### Agora

`requireBucketDoDono` aceita o bucket do dono (`bucketsOf`, só vivo) **ou** o bucket em que ele tem
linha no catálogo, vivo ou lápide (`bucketHasObjectsOf`) — a mesma régua do `DeleteBucket` (§4.15).

| situação | resposta |
|---|---|
| leitura de chave **viva** do dono | `200` |
| leitura de **lápide** do dono | `404 NoSuchKey` |
| leitura em bucket que o dono **nunca** gravou | `403 AccessDenied` |
| `PUT` na lápide **de outra conta** | `403 AccessDenied` (§4.22, quando mergeado) |

A lápide é de quem a criou: a régua nova não abre bucket alheio, e o dono continua regravando a chave
que ele mesmo apagou.

**Verificação:** `S3GatewayTests` **+1** (`lapide_propria_le_404_e_nao_403`: grava, apaga a única
chave, e exige `404 NoSuchKey` no `GET` e no `HEAD`, `200` com `<KeyCount>0</KeyCount>` na listagem e o
regrave funcionando). O `delete_vira_lapide`, que aceitava `isIn(403, 404)`, passa a exigir `404`.
Mutações: voltar à régua só de vivos (`403`); aceitar qualquer bucket
(`escopo_nulo_nao_alcanca_bucket_de_outra_conta_por_cabecalho` cai) — **2 de 2 derrubam**.

## 4.24 Objeto cuja cópia está em outro nó: `503 SlowDown` (ciclo 112)

A cópia era procurada **dentro** do corpo da resposta (`StreamingResponseBody`), depois de os `200` e
os cabeçalhos terem saído. Duas consequências:

1. **Download truncado com `200`.** Se a cópia não podia ser aberta, o cliente recebia
   `200 Content-Length: 12` e **zero bytes** — sem erro, sem código, só o arquivo faltando no fim.
2. **A recusa real saía no vocabulário do control plane.** Com a réplica 0 podada e a colocação num
   outro nó, `readBody` recusa `409 STORAGE_REPLICA_ELSEWHERE` — que existe para o control plane, que
   usa o digest para designar o nó. O gateway repassava o **JSON do Énóis**: o SDK do S3 não
   deserializa isso, não reconhece o código e não tenta de novo.

### Agora

| situação | resposta |
|---|---|
| cópia **desta** instalação | `200`/`206`, como antes |
| cópia **em outro nó** (`STORAGE_REPLICA_ELSEWHERE`) | **`503 SlowDown`** em XML, com `Retry-After: 1` |
| objeto confirmado **sem cópia nenhuma** | `409 InternalError` em XML (como antes) |
| falha ao abrir a cópia | recusa **antes** do corpo (nunca `200` truncado) |

- O corpo é aberto **antes** do primeiro byte, no `GET` **e** no `HEAD` (o `HEAD` só confirma que a
  cópia pode ser aberta e fecha o stream; o `304` também fecha o que já abriu). Uma falha vira
  recusa, não download curto.
- `503 SlowDown` é o `ClientException` literal que o `S3Exception.retryable` do SDK repete;
  `Retry-After` é o que diz quando. O `GlobalExceptionHandler` do S3 passou a emitir esse cabeçalho
  (antes o `withRetryAfter` não aparecia no formato XML).

**Verificação:** `S3GatewayTests` **+2**.

- `copia_fora_deste_no_responde_503_em_xml` — a colocação aponta para outro nó e a réplica 0 sai da
  raiz: `GET` e `HEAD` respondem `503` com `<Code>SlowDown</Code>` e `Retry-After`. Mutações que a
  derrubam (todas verificadas): devolver a recusa crua (`409` JSON); sem `Retry-After`; procurar o
  corpo dentro do `StreamingResponseBody` (volta o `200` truncado).
- `range_com_mais_de_um_intervalo_responde_o_corpo_inteiro` — `Range: bytes=0-1,5-6` devolve `200`
  com os 12 bytes e sem `Content-Range`. **Verdadeiro antes da mudança** (o MockMvc não aplica o
  `Range`); fica como guarda do contrato declarado, não como prova da correção.

## 4.25 O `<ETag>` da parte no Complete é conferido (ciclo 113)

O `partesDoXml` lia só `<PartNumber>` e **descartava o `<ETag>`** de cada `<Part>`. O S3 exige que o
ETag declarado seja o da parte enviada; aqui, um cliente que montou o XML com o ETag de **outra** parte
(ou de um reenvio que não fez) recebia `200` e um objeto montado com os bytes que estavam no disco —
sem conferência, em silêncio. Num cliente que acha estar estendendo o arquivo, isso grava o conteúdo
errado e ninguém percebe.

### Agora

| XML do `CompleteMultipartUpload` | resposta |
|---|---|
| `<Part>` com o ETag da parte que chegou | `200` (como antes) |
| `<Part>` com ETag de outro conteúdo | **`400 InvalidPart`**, upload continua aberto |
| `<Part>` sem `<ETag>` | `200` — o número basta (**divergência** do S3: a AWS responde `MalformedXML`) |

`StorageMultipartService.complete(upload, pedidas, etags)` recebe o mapa `partNumber → ETag`; a
comparação é contra o `sha256` gravado da parte (o ETag do Storage **é** o digest). A assinatura de
duas posições continua existindo para quem conclui pelo caminho do control plane, e delega com mapa
vazio.

A leitura do XML é uma **varredura linear** (`indexOf` sobre um trecho por `<Part>`), não uma
expressão regular: a versão anterior recortava o documento inteiro com `\\s*` guloso ao lado de
`[^<]*?`, e o CodeQL marcou o retrocesso sobre entrada do chamador como alerta alto.

**Verificação:** `S3MultipartTests` **+2**: `conclusao_com_etag_de_parte_que_nao_bate_e_recusada_sem_gravar`
(duas partes, o XML declara o ETag da 2 na 1 → `400 InvalidPart`, nenhuma linha no catálogo; com os
ETags certos, `200` e 6 MiB + 1 byte) e `conclusao_sem_etag_declado_aceita_o_numero_da_parte` (fixa a
tolerância, a divergência declarada acima). Mutações: desligar a comparação; descartar o ETag na
leitura; recusar o `Part` sem `ETag` — **3 de 3 derrubam**.

## 4.28 A URL pré-assinada assina o caminho que ela carrega (ciclo 116)

O emissor assinava `"/" + bucket + "/" + key` com a chave **crua** e emitia a URL com cada segmento
passado por `uriEncode`. Numa chave com espaço, acento, `+` ou `%`, a assinatura não era a da URL
emitida: o portador recebia, numa URL recém-emitida,

```
GET /s3/<bucket>/fotos/meu%20arquivo%20%C3%A7%C3%A3o.jpg?X-Amz-Signature=…
403 AccessDenied "A URL pre-assinada expirou ou a assinatura nao confere."
```

A mensagem manda o cliente procurar relógio ou validade; o defeito era a codificação. Nome de arquivo
com espaço é o caso comum, e todos os testes de URL usavam `fotos/url.jpg`, que não tem caractere a
codificar.

### Agora

O caminho assinado é montado com o mesmo `uriEncode` por segmento que a URL carrega — e é o mesmo
texto que o `canonicalUri` do verificador produz ao recodificar o caminho recebido.

**Verificação:** `S3GatewayTests` **+1** (`url_pre_assinada_abre_chave_com_espaco_e_acento`: grava e
emite para `fotos/meu arquivo ção.jpg`, confere `%20`/`%C3%A7` na URL e abre → `200` com o corpo).
Mutação: voltar a assinar a chave crua — **derruba**.

## 4.29 O PUT avalia `If-Match` e `If-None-Match` (ciclo 117)

O PUT ignorava as duas condições. O cliente que pedia `If-None-Match: *` — o jeito do S3 de
**criar sem sobrescrever** (`aws s3api put-object --if-none-match '*'`, `.ifNoneMatch("*")` do SDK) —
recebia, numa chave já ocupada,

```
PUT /s3/<bucket>/<key>   If-None-Match: *
409 ObjectAlreadyStored
```

`409` é a confissão de que o gateway não sobrescreve; a resposta do pedido condicional é
`412 PreconditionFailed`. Um fluxo com duas fontes escrevendo o mesmo nome não consegue distinguir
"perdi a corrida" de "a condição recusou". `If-Match` era igualmente ignorado.

### Agora

A condição é avaliada contra o objeto que está na chave **antes** de abrir upload:

| pedido | chave ocupada | chave livre |
|---|---|---|
| `If-None-Match: *` ou com o ETag do objeto | `412 PreconditionFailed` | grava |
| `If-None-Match` com ETag de outra versão | segue o fluxo normal (`409` do gateway) | grava |
| `If-Match` com o ETag do objeto | segue o fluxo normal (`409`) | `412` (não há versão para casar) |
| `If-Match` com outro ETag / `*` | `412` | `412` |
| `If-Modified-Since` / `If-Unmodified-Since` | `501 NotImplemented`, como na cópia | idem |

**Verificação:** `S3GatewayTests` **+1** (`put_com_if_none_match_nao_sobrescreve_chave_ocupada`:
chave ocupada + `If-None-Match: *` → `412` e o conteúdo antigo intacto; chave livre + `*` → grava;
`If-None-Match` com ETag de outra versão → `409`; com o ETag certo → `412`; `If-Match` com o ETag
certo → `409`; com outro → `412`). Mutações: sem a conferência; `If-None-Match` só olhando o
asterisco; `If-Match` só olhando o asterisco — **3 de 3 derrubam** (a 2ª e a 3ª só depois de o teste
distinguir o ETag certo do errado).


## 4.30 `x-amz-expected-bucket-owner` é conferido (ciclo 118)

O cabeçalho era ignorado por todas as rotas. Ele existe para o caso em que duas contas têm bucket com
o mesmo nome — comum em multi-tenant: o SDK manda o dono que espera e o S3 recusa se o bucket for de
outra conta. Aqui o cliente recebia `200` e, pior, **gravava na conta errada**: o `PUT` só confere a
donagem quando o bucket já tem objeto, e um bucket novo nasce do primeiro objeto de quem chega
primeiro. O `--expected-bucket-owner` do `aws s3api` não protegia nada.

### Agora

| cabeçalho | resposta |
|---|---|
| ausente | nada muda |
| igual ao dono do bucket (`ownerUserId` da credencial) | segue o fluxo normal |
| outro valor | **`403 AccessDenied`**, antes de qualquer gravação ou leitura |

A conferência é a última do `authenticate` — nos dois caminhos (assinatura por cabeçalho e URL
pré-assinada): assinatura e escopo já foram provados, então a recusa não revela nada a quem não
assinou.

**Verificação:** `S3GatewayTests` **+1** (`expected_bucket_owner_divergente_recusa_antes_de_gravar`:
`PUT` com dono errado → `403` e nenhuma linha no catálogo; `ListObjectsV2` com dono errado → `403`;
`PUT` e listagem com o dono certo → `200`, objeto legível). Mutações: sem a guarda; aceitar qualquer
valor — **2 de 2 derrubam**.


## 4.33 `ListMultipartUploads`: `?uploads` mostra o que ficou aberto (ciclo 122)

`GET /{bucket}?uploads` caía no `ListObjects`, que ignora o subrecurso e responde um
`<ListBucketResult>` com os objetos — o cliente que pede os multiparts recebia uma lista
**silenciosamente errada**, e as partes órfãs (processo que morreu, rede que caiu) seguiam ocupando
disco até a varredura de lixo, sem rota para o operador vê-las.
## 4.35 `PutBucketVersioning` / `GetBucketVersioning` (ciclo 124)

O Storage **tem** versionamento por bucket desde o V352 (a política guarda `versioning`,
`retain_superseded_days`, `expire_after_days`, e o `restore` depende dele) — mas o protocolo S3 não
dava acesso: `PUT /{bucket}?versioning` caía no `PutObject` (`400 InvalidRequest`) e
`GET /{bucket}?versioning` caía no `ListObjects` (`200 <ListBucketResult>`, documento do tipo
errado). O `aws s3api put-bucket-versioning` não convencia ninguém.


## 4.34 `ListObjectVersions`: `?versions` mostra o histórico do bucket (ciclo 123)

`GET /{bucket}?versions` caía no `ListObjects`, que ignora o subrecurso e responde
`200 <ListBucketResult>` com os objetos **correntes**. O `aws s3api list-object-versions` mostrava
cada chave uma vez só e o operador não tinha como ver as versões substituídas ocupando disco. Só
existia o histórico **de uma chave** (`StorageCatalogService.versions`).

### Agora

| pedido | resposta |
|---|---|

| `GET /{bucket}?uploads` | `200` `<ListMultipartUploadsResult>` com `<Upload><Key/><UploadId/><Initiated/>` por upload aberto **do dono** |
| `?uploads&prefix=p` | só as chaves que começam com `p` (`%` e `_` escapados no SQL) |
| upload de outra conta | omitido (não vira erro) |

`StorageMultipartService.abertos(bucket, ownerUserId, prefix)`; ordem `created_at asc, upload_id asc`.

**Verificação:** `S3GatewayTests` **+1** (`listar_uploads_abertos_do_dono`: abre um upload, ele
aparece com chave e id, `prefix` que não casa não o traz). Vermelho antes: `ListBucketResult` com
os objetos, sem o `<UploadId>` — o cliente não via upload nenhum. Mutações: tirar o filtro por dono;
ignorar o `prefix` — **2 de 2 derrubam**.

| `GET /{bucket}?versions` | `200 <ListVersionsResult>` com `<Version>` por versão e `<DeleteMarker>` para a lápide |
| campos | `Key`, `VersionId`, `IsLatest`, `LastModified`, `ETag`, `Size` |
| `?versions&prefix=p` | só as chaves que começam com `p` (`%`, `_`, `\` escapados) |
| versão de outra conta no mesmo bucket | omitida |

`StorageCatalogService.versionsNoBucket(bucket, prefix)` →
`StorageCatalogRepository.versionsNoBucket` (ordem `object_key asc, version asc`, teto de 10 000
linhas). Versão só existe em bucket com `versioning` ligado — sem ele, reescrever a chave é `409` e
o histórico tem uma linha só.

**Verificação:** `S3GatewayTests` **+1** (`listar_versoes_do_bucket`: duas versões da chave
reescrita e uma da outra, todas com `VersionId`; uma linha de outra conta **plantada no mesmo
bucket** não aparece; `prefix` que não casa não traz nada). Vermelho antes: `<ListBucketResult>`.
Mutações: tirar o filtro por dono (a primeira rodada **passou verde** porque o teste não tinha
objeto alheio — a asserção foi tornada discriminante com a linha plantada); ignorar o `prefix`;
listar só `version > 1` — **3 de 3 derrubam**.
| `GET /{bucket}?versioning` | `<VersioningConfiguration>` com `<Status>Enabled</Status>` ou `<Status>Suspended</Status>` |
| bucket nunca configurado | `<VersioningConfiguration/>` (elemento vazio, como no S3) |
| `PUT ?versioning` com `Enabled` | `200`, liga |
| `PUT ?versioning` com `Suspended` | `200`, desliga e zera a retenção de versão substituída |
| qualquer outro `<Status>` | `400 MalformedXML`, configuração intacta |

- Grava pela mesma `putBucketPolicy` do serviço; `expire_after_days` é **preservado** ao ligar/suspender.
- **`Suspended` não apaga o histórico:** as versões antigas seguem listáveis (§4.34) e restauráveis —
  "suspenso" é parar de criar versão nova, como no S3.

**Verificação:** `S3GatewayTests` **+1** (`versionamento_do_bucket_liga_le_e_suspende`: vazio antes,
liga e confere a coluna no banco, `Enabled` na leitura, `Ligado` → `400 MalformedXML` sem mudar nada,
`Suspended` na leitura). Vermelho antes: `<ListBucketResult>`.
Mutações: sempre emitir `<Status>`; gravar `false` sempre; gravar `true` sempre; aceitar qualquer
`<Status>` — **4 de 4 derrubam**. *(A primeira rodada tinha duas mutações verdes: o teste não
exercitava o `400` e o código tinha dois ramos para o mesmo comportamento. O `código` foi
simplificado e a asserção do `MalformedXML` acrescentada; nada foi removido para "ficar verde".)*



## 4.36 `?lifecycle` fala a mesma política que o varredor (ciclo 125)

O Storage **expira objeto de verdade** pelo `expire_after_days` da política (o varredor apaga) e
**libera versão substituída** pelo `retain_superseded_days`. Nenhum dos dois era alcançável pelo
protocolo S3:

| antes | agora |
|---|---|
| `GET /{bucket}?lifecycle` → `200 <ListBucketResult>` (lista de objetos) | `200 <LifecycleConfiguration>` com `<Expiration><Days>` e `<NoncurrentVersionExpiration><NoncurrentDays>` |
| `PUT /{bucket}?lifecycle` → `400 InvalidRequest` | `200`, grava nas colunas que o varredor lê |

- Sem política, o `GET` sai com `<LifecycleConfiguration/>` vazio.
- O `PUT` preserva o **versionamento** e grava `expire_after_days` / `retain_superseded_days`.
- Regra que o gateway **não honra** é `400 InvalidRequest` e não muda nada: `<Transition>`,
  `<AbortIncompleteMultipartUpload>`, `<NoncurrentVersionTransition>`, `<Expiration><Date>`.
- **Retenção exige versionamento** (a mesma regra do serviço): `NoncurrentVersionExpiration` num
  bucket sem versionamento é `400 STORAGE_POLICY_INVALID`. O S3 real aceita e a regra nunca age;
  aqui a recusa é explícita, porque uma regra parada é pior que um erro que o operador vê.

**Verificação:** `S3GatewayTests` **+1** (`lifecycle_do_bucket_le_e_grava_a_mesma_politica_do_varredor`:
vazio antes; grava 30 dias e 7 dias não correntes e confere **as colunas do banco**; a leitura devolve
as mesmas regras; `<Transition>` → `400` sem mudar o prazo). Vermelho antes: `<ListBucketResult>`.
Mutações: não gravar a política; não emitir `<Expiration>` na leitura; aceitar `<Transition>` —
**3 de 3 derrubam**.

## 4.32 `CreateBucket`, `HeadBucket` e `GetBucketLocation` (ciclo 121)

As três chamadas que **todo cliente faz antes de subir o primeiro objeto** não existiam.

| antes | agora |
|---|---|
| `PUT /{bucket}` caía no `PutObject` → `400 InvalidRequest "Informe o bucket e a chave"` | `200` e o bucket registrado para o dono |
| `HEAD /{bucket}` sem handler | `200` para bucket do dono, `404` para o que ele não tem |
| `GET /{bucket}?location` sem handler | `200` com `<LocationConstraint>enois-1</LocationConstraint>` |

- Criar de novo o **mesmo** bucket do **mesmo** dono é `200` idempotente
  (`BucketAlreadyOwnedByYou` é sucesso no S3).
- Nome que já é de **outra conta** é `409 BucketAlreadyExists` e não muda o dono.
- O Storage não tem tabela de donos de bucket: o `CreateBucket` grava o criador em
  `network_storage_buckets.updated_by` (a linha de política, que já existia), e o dono sem
  registro sai do primeiro objeto vivo (`StorageCatalogRepository.ownerDoBucket`, `default`).
- `?location` aceita o criador registrado **antes** do primeiro objeto; depois disso vale
  `requireBucketDoDono` como no resto do gateway.
- O `HeadBucket` responde `404` (não `403`) ao bucket alheio: o S3 não revela existência.

**Verificação:** `S3GatewayTests` **+2** (`create_head_e_location_do_bucket`: criar, recriar
idempotente, `HEAD` do dono, `HEAD` alheio `404`, `?location`;
`create_de_bucket_de_outra_conta_e_409`). Mutações: não registrar; aceitar sobrescrever o dono;
`HEAD` aceitar qualquer bucket — **3 de 3 derrubam**.


## 4.37 `GetBucketAcl` e `GetBucketPolicy` dizem a verdade do gateway (ciclo 127)

Os dois subrecursos de **leitura** do bucket não tinham rota e caíam no `ListObjects`:

| antes | agora |
|---|---|
| `GET /{bucket}?acl` → `200 <ListBucketResult>` | `200 <AccessControlPolicy>` com `<Owner>` e um `<Grant>` `FULL_CONTROL` |
| `GET /{bucket}?policy` → `200 <ListBucketResult>` | `404 NoSuchBucketPolicy` |
| bucket de outra conta | `403 AccessDenied` nas duas |

- O `<Owner><ID>` é a conta dona da credencial — o Storage tem **um** dono por bucket e nenhuma ACL
  por usuário; expor isso é mais honesto que inventar `Grantee`.
- `?policy` recusa por ausência declarada: o gateway não aceita política de recurso (quem manda é a
  donagem). Devolver documento vazio/inventado faria o operador achar que há política.
- `S3Xml` ganhou `attribute(prefix, namespace, name, value)`: o `<Grantee xsi:type="CanonicalUser">`
  precisa do prefixo declarado no próprio elemento, senão o documento não é XML válido.

**Verificação:** `S3GatewayTests` **+1** (`acl_e_policy_do_bucket`). Vermelho antes: `<ListBucketResult>`.
Mutações **4/4** derrubam: não conferir donagem; `<Owner>` extra no fim; `?policy` responder `200`;
`<DisplayName>` trocado por valor fixo. A 2ª **sobreviveu** à primeira versão da asserção (que só
procurava `<Owner><ID>`), por isso ela passou a fixar ordem e fecho do documento.

## 4.38 `DELETE` de subrecurso não é `DeleteBucket`, e o criador registrado apaga o próprio bucket (ciclo 129)

O `DELETE /{bucket}` não olhava a consulta:

| antes | agora |
|---|---|
| `DELETE /{bucket}?cors` (bucket com objetos) → `409 BucketNotEmpty` | `501 NotImplemented`, nada alterado |
| `DELETE /{bucket}?cors` (bucket vazio) → `204` e o bucket some | `501 NotImplemented` |
| `DELETE /{bucket}` de bucket criado por `CreateBucket`, sem objeto → `403 AccessDenied` | `204` |
| `DELETE /{bucket}` depois de apagar a última chave → `403 AccessDenied` | `204` |

- **Apagar a CORS não apaga o bucket.** Sem a guarda, um bucket vazio respondia `204` a
  `DELETE /{bucket}?cors` e sumia — num bucket com objeto o `409` mentia sobre o motivo. A lista
  `SUBRECURSOS_DE_BUCKET_NAO_IMPLEMENTADOS` (`cors`, `tagging`, `policy`, `website`, `notification`,
  `logging`, `replication`, `encryption`, `accelerate`, `requestPayment`, `publicAccessBlock`,
  `ownershipControls`, `analytics`, `intelligent-tiering`, `metrics`, `inventory`, `object-lock`) é
  recusada com `501`, como o `PUT` de subrecurso que gravaria (§4.21).
- `?versioning` **não** está na lista: é suportado (`PUT` com `Suspended` desliga). `?lifecycle` também
  não — é a política do varredor.
- **Donagem do `DeleteBucket`:** `bucketHasObjectsOf` contava só objeto **vivo**. O bucket criado por
  `CreateBucket` (§4.32) não tem objeto nenhum — o criador recebia `403` ("não é do dono") no próprio
  bucket; e o dono que apagou a última chave também, porque a lápide não contava. Agora conta uma linha
  de qualquer estado **ou** a linha de política com `updated_by` igual ao dono.

**Verificação:** `S3GatewayTests` **+1**
(`delete_de_subrecurso_nao_apaga_o_bucket_e_o_dono_esvaziado_pode_apagar`): as 17 entradas recusadas com
objeto vivo; o objeto continua lá; o dono apaga a última chave e o bucket (`204`); o criador registrado
apaga o bucket que nunca teve objeto. Vermelho antes: `409 BucketNotEmpty`. Mutações **3/3** derrubam:
sem a guarda; sem o `updated_by` na conta; `object-lock` fora da lista (esta **sobreviveu** à primeira
versão do teste, que só exercia quatro entradas — o teste passou a percorrer a lista inteira).

## 4.39 `DeleteObjects` apaga em lote (ciclo 120)

A rota `POST /{bucket}?delete` não existia. `aws s3 rm --recursive` e o `rclone delete` mandam **um**
POST com até 1000 chaves no corpo e esperam `200` com um `<Deleted>` por chave; o gateway respondia
`500 INTERNAL_ERROR` (nenhum handler para o verbo naquele caminho). Pagar uma requisição por chave num
bucket grande é justamente o que o protocolo evita.

### Agora

| corpo | resposta |
|---|---|
| `<Delete><Object><Key>k</Key></Object>…` | `200` com `<Deleted><Key>k</Key></Deleted>` por chave |
| chave que não existe | `<Deleted>` (apagar o que não há é sucesso no S3) |
| chave de outra conta | `<Error><Code>AccessDenied</Code>` e a chave **não** é tocada |
| `<Quiet>true</Quiet>` | apaga e omite os `<Deleted>` |
| sem `<Object>`, ou mais de 1000 | `400 MalformedXML` |

**Verificação:** `S3GatewayTests` **+2** (`delete_objects_apaga_em_lote_e_reporta_cada_chave`: lote de
três chaves, duas apagadas e a terceira legível, e o mesmo com `Quiet`;
`delete_objects_sem_objetos_e_malformed_xml`). Mutações: não apagar; responder `200` ao corpo vazio —
**2 de 2 derrubam**.

## 4.40 `x-amz-request-id` em toda resposta, e o mesmo id no `<RequestId>` do erro (ciclo 130)

Toda chamada do SDK ao S3 pode trazer um id de requisição. O gateway não trazia nenhum:

| antes | agora |
|---|---|
| `200` sem `x-amz-request-id` | `200` com o cabeçalho |
| `404 NoSuchKey` / `403 AccessDenied` sem o cabeçalho | idem, com o cabeçalho |
| XML de erro **sem** `<RequestId>` | `<RequestId>` com o **mesmo** valor do cabeçalho |

- O `errorXml` do controller preenchia `<RequestId>`, mas **não era o caminho usado**: o handler de
  exceção monta o corpo por `S3ApiException.toXml`, que saía sem id — e o cabeçalho não existia em
  lugar nenhum.
- O id é sorteado **uma vez** por requisição no `S3RequestIdFilter` (16 hexadecimais maiúsculos, a
  forma da AWS), guardado no atributo do pedido e escrito no cabeçalho antes da resposta começar. O
  handler lê o mesmo atributo, então as duas metades concordam.
- Fora de `/s3` nenhuma resposta muda: o filtro se anula (`shouldNotFilter`).
- Ids de requisições diferentes são diferentes — um valor fixo não correlaciona nada, e o teste
  afirma isso.

**Verificação:** `S3GatewayTests` **+1** (`request_id_no_cabecalho_e_no_corpo_do_erro`): `200` com id;
`404` com id no cabeçalho e no corpo, iguais; ids de duas requisições diferentes. Vermelho antes:
`200 sem x-amz-request-id`. Mutações **3/3** derrubam: sem o cabeçalho; `<RequestId>` fixo; id fixo.

## 4.41 `HEAD` de objeto recusa os mesmos subrecursos que o `GET` (ciclo 131)

O `HEAD` de objeto chamava **só** a recusa dos subrecursos de leitura (`versionId`, `torrent`,
`partNumber`, §4.37). A dos subrecursos que **gravariam** (`acl`, `tagging`, `retention`,
`legal-hold`, `object-lock`, §4.21) ficava só no `GET`:

| pedido | antes | agora |
|---|---|---|
| `HEAD ?acl` (idem `?tagging`, `?retention`, `?legal-hold`, `?object-lock`) | `200`, `Content-Length: 3`, `Content-Type` do arquivo | `501 NotImplemented` |
| `HEAD ?versionId=` / `?torrent` / `?partNumber=` | `501` | `501` |
| `HEAD ?response-content-type=` | `200` | `200`, tipo **gravável** (igual ao `GET`) |

- `200` no `HEAD ?acl` é a pergunta errada com cara de certa: o cliente que consulta a ACL pelo
  `HEAD` — o caminho de quem quer só o tamanho antes de baixar — lê "existe, 3 bytes, `text/plain`" e
  conclui que a ACL do objeto é o próprio arquivo.
- O `headObject` passou a chamar **as duas** recusas, como o `getObject`, e declara `IOException`
  (a recusa dos subrecursos que gravariam lê o corpo do pedido).
- O `?response-content-*` continua aceito e **não aplicado** nos dois (§4.28), então `HEAD` e `GET` da
  mesma URL dizem o mesmo `Content-Type`.

**Verificação:** `S3GatewayTests` **+1** (`head_recusa_os_mesmos_subrecursos_que_o_get`): para cada um
dos 8 subrecursos, o `GET` responde `501` e o `HEAD` responde o mesmo, sem vazar o tamanho do arquivo;
`?response-content-type=text/html` sai `200` sem `text/html`. Vermelho antes: `HEAD ?acl` → `200`.
Mutações: sem a recusa dos subrecursos que gravariam (`a`) e sem a de leitura (`c`) derrubam; trocar a
ordem das duas (`b`) **não** derruba — é equivalência real, cada uma é idempotente e independente.

## 4.44 `Content-MD5` é conferido: MD5 errado é `400 BadDigest` (ciclo 134)

O cabeçalho de integridade do S3 era **ignorado** por completo:

```
PUT /s3/<b>/x.txt   Content-MD5: <MD5 de OUTRO corpo>   corpo "abc"   → 200, objeto gravado
PUT /s3/<b>/x.txt   Content-MD5: nao-e-base64           corpo "abc"   → 200, objeto gravado
```

O cliente que manda o MD5 para pegar corrupção no caminho — rede, proxy, retentativa — recebia
"gravado com sucesso" para um corpo que ele não mandou, e o erro só aparecia depois, no consumidor.

### Agora

| pedido | antes | agora |
|---|---|---|
| `Content-MD5` que bate | `200` | `200` |
| `Content-MD5` de outro corpo | `200`, objeto gravado | `400 BadDigest`, nada gravado |
| `Content-MD5` que não é base64 de 16 bytes | `200` | `400 InvalidDigest` |

- O valor é validado e o corpo conferido **antes** de abrir upload: a recusa não deixa linha aberta
  nem objeto `PENDING` atrás, e um `PUT` sobre chave existente com MD5 errado **não** substitui o
  conteúdo que já estava lá.
- O cliente que pede a conferência paga por ela: com o cabeçalho, o corpo é lido para memória antes
  da gravação. Sem o cabeçalho, o caminho é o de sempre (fluxo direto para o disco).
- O `CopyObject` não é afetado: lá não há corpo do cliente para conferir.

**Verificação:** `S3GatewayTests` **+1** (`content_md5_que_nao_bate_e_bad_digest_e_nao_grava`): MD5
errado → `400 BadDigest` e o conteúdo **não** aparece no GET; base64 inválido e base64 curto →
`400 InvalidDigest`; MD5 certo grava e o corpo confere; substituição com MD5 errado mantém o conteúdo
anterior. Vermelho antes: `200`. Mutações **2/2**: sem a conferência do digest; sem a validação de 16
bytes.

## 4.42 `max-keys` inválido é `400`, e `max-keys=0` é página vazia (ciclo 132; `delimiter` no 135)

`parseMaxKeys` tratava todo valor `<= 0` como "não veio":

```
GET /s3/<b>?max-keys=0    → 200 com 1000 objetos
GET /s3/<b>?max-keys=-1   → 200 com 1000 objetos
GET /s3/<b>?max-keys=abc  → 200 com 1000 objetos
```

O `S3` distingue os três: `0` é uma página vazia legítima (o cliente pede "só o total"), negativo e
não numérico são erro do pedido. Com `delimiter` era pior: a conta de "há mais" usava o tamanho
pedido e com zero nunca fechava — `?max-keys=0&delimiter=/` devolvia o bucket inteiro, com
`IsTruncated=false` e todos os `CommonPrefixes`.

### Agora

| `max-keys` | antes | agora |
|---|---|---|
| ausente | `1000` | `1000` |
| `0` | `1000` objetos | página vazia: `KeyCount 0`, sem `<Key>` nem `<CommonPrefixes>` |
| `-1` / `1.5` / `abc` | `1000` objetos | `400 InvalidArgument` |
| `> 1000` | `1000` | `1000` (corte, como no S3) |

- Com `max-keys=0` sem `delimiter` a página não vem truncada: não há "próxima" a pedir, o cliente
  queria só saber que lista vazia foi pedida.
- Com `delimiter` junto do `0`, a resposta tem `IsTruncated=true` (há conteúdo depois) e **nenhum
  marcador**: numa página vazia o último emitido é nulo, e o `S3Xml` só escreve o
  `NextMarker`/`NextContinuationToken` quando há um. O cliente que pedir a próxima a partir do zero
  recebe de novo a página vazia — declarado aqui para não parecer paginação completa.
- O corpo do erro é o XML do S3 (`<Code>InvalidArgument</Code>`), não o JSON do Control Plane.

**Verificação:** `S3GatewayTests` **+1** (`max_keys_zero_e_pagina_vazia_e_invalido_e_400`): `0` sem
`delimiter` → `KeyCount 0`, `IsTruncated=false`, sem `<Key>`; `0` com `delimiter` → o mesmo e
`IsTruncated=true`; `-1`, `1.5` e `abc` → `400 InvalidArgument` sem conteúdo; `5000` →
`<MaxKeys>1000</MaxKeys>` e o objeto presente. Vermelho antes: `0` e `abc` listavam. Mutações
**3/3** com o `parseMaxKeys` do ciclo 132 já na `qa`: `0` voltando ao padrão; sem a recusa de
negativo; `1.5`/`abc` voltando ao padrão. A conta de `IsTruncated` com `delimiter` ficou **como
estava**: a tentativa de mudá-la para "teto de linhas cheio" não é discriminada por nenhum cenário
(o laço já marca truncado ao encontrar a entrada além do teto), então não virou código.

## 4.45 `DELETE` de objeto com subrecurso não apaga a chave (ciclo 135)

O `DELETE` do objeto não olhava a consulta:

```
DELETE /s3/<b>/a.txt?versionId=1   → 204   e a chave some
DELETE /s3/<b>/a.txt?tagging       → 204   e a chave some
DELETE /s3/<b>/a.txt?acl           → 204   e a chave some
```

Quem pedia "apague a versão 1" ou "apague as tags" perdia o objeto inteiro, com `204`. É o mesmo
defeito que o `DELETE` do **bucket** tinha com `?cors` (§4.38) — e o `GET`/`HEAD` já recusavam esses
nomes (§4.37/§4.41), então as metades discordavam.

## 4.46 `x-amz-checksum-*` é conferido, e algoritmo não suportado é `400` (ciclo 136)

O SDK v2 do S3 manda `x-amz-checksum-*` por padrão. Aqui ele era ignorado:

```
PUT /s3/<b>/x.txt   x-amz-checksum-sha256: <SHA-256 de OUTRO corpo>  → 200, objeto gravado
PUT /s3/<b>/x.txt   x-amz-checksum-sha256: abcd                      → 200, objeto gravado
PUT /s3/<b>/x.txt   x-amz-checksum-crc32: DUoRhQ==                   → 200, objeto gravado
```

É o mesmo defeito que o `Content-MD5` tinha (§4.44): quem pede a conferência de integridade recebia
"gravado" para um corpo que não mandou.

### Agora

| pedido | antes | agora |
|---|---|---|
| `x-amz-checksum-sha256` correto (caixa qualquer) | `200` | `200` |
| `x-amz-checksum-sha256` que não bate | `200`, objeto gravado | `400 BadDigest`, nada gravado |
| `x-amz-checksum-sha256` que não é 64 dígitos hex | `200` | `400 InvalidArgument` |
| `x-amz-checksum-crc32` / `-crc32c` / `-crc64nvme` / `-sha1` | `200`, sem conferir | `400 NotImplemented` nomeando o algoritmo |
| `x-amz-checksum-type` | ignorado | ignorado (é rótulo do cliente, não digest) |

- A conferência acontece **antes** de abrir upload: a recusa não deixa linha aberta nem objeto
  `PENDING`, e não substitui o conteúdo de uma chave existente. Com qualquer cabeçalho de
  integridade presente, o corpo é lido para memória (quem pede conferência paga por ela).
- **Algoritmo fora do SHA-256 é recusado, não aceito em silêncio.** Conferir CRC32/SHA-1 exige mais
  código do que a sonda provou; responder `200` a um digest que ninguém olhou seria pior que dizer
  "não confiro". O cabeçalho de resposta do checksum não é ecoado no `GET`/`HEAD` — quem precisa do
  digest lê o `ETag`, que é o SHA-256 do conteúdo.

**Verificação:** `S3GatewayTests` **+1** (`checksum_do_sdk_declara_algoritmo_e_e_conferido`): digest
errado → `400 BadDigest` e o objeto não aparece no `GET`; valor curto → `400`;
`crc32`/`sha1` → `400 NotImplemented`; digest certo em maiúsculas grava; substituição com checksum
errado mantém o conteúdo anterior. Vermelho antes: `200` em todos os casos. Mutações **4/4**: sem a
conferência do digest; algoritmo não conferido aceito; sem a normalização de maiúsculas; comparação
desligada.

## 4.47 `If-Range` decide se o `Range` vale (ciclo 137)

O cabeçalho que protege um download retomado era ignorado:

```
GET /s3/<b>/a.txt   Range: bytes=0-4   If-Range: "ETag de OUTRA versão"   → 206, "01234"
```

O cliente que retoma um download manda `If-Range` com o ETag da versão que começou a baixar. Se o
objeto foi substituído no meio, o S3 responde `200` com o arquivo **novo e inteiro**, e o cliente
recomeça. Aqui ele recebia `206` com o pedaço da versão **nova** na posição da velha: o arquivo
remontado no disco do cliente ficava com os primeiros bytes de uma versão e o resto de outra, e a
corrupção só aparecia ao abrir.

### Agora

| `If-Range` | antes | agora |
|---|---|---|
| ausente | `206` | `206` |
| ETag da versão servida | `206` | `206` |
| ETag de outra versão | `206` (pedaço errado) | `200`, corpo inteiro, sem `Content-Range` |
| data anterior ao `Last-Modified` | `206` | `200`, corpo inteiro |
| data no mesmo segundo do `Last-Modified` | `206` | `206` (HTTP-date tem granularidade de segundo) |
| data posterior | `206` | `206` |
| valor que não é ETag nem data (`lixo`) | `206` | `200`, corpo inteiro |

- A data é comparada em segundos, como o `If-Modified-Since` (o `304` condicional): o `updated_at` tem
  milissegundos e o cliente devolve o `Last-Modified` que recebeu, truncado.
- A regra vive no serviço de conteúdo, não no gateway: o parâmetro novo de `respond` tem a
  sobrecarga antiga para o chamador que não passa `If-Range`.

**Verificação:** `S3GatewayTests` **+1** (`if_range_validador_que_nao_bate_devolve_o_corpo_inteiro`):
ETag certo → `206` com `01234`; ETag de outra versão, data antiga, data futura e `lixo` → `200` com
`0123456789` e sem `Content-Range`. Vermelho antes: `206` nos quatro. Mutações **3/3**: gateway sem
passar o cabeçalho; comparação de data desligada; comparação de ETag desligada.

## 4.48 SSE declarado é recusado, não ignorado (ciclo 138)

O SDK manda `x-amz-server-side-encryption` quando SSE está configurado. Aqui ele era ignorado:

```
PUT /s3/<b>/x.txt   x-amz-server-side-encryption: AES256   → 200, objeto gravado em claro
HEAD /s3/<b>/x.txt                                         → sem x-amz-server-side-encryption
```

Quem configurou SSE tinha a certeza de que a borda cifrava o objeto; quem lê a cópia em disco vê o
conteúdo. A `HEAD` não devolvia nem o eco do cabeçalho, então nem havia como descobrir pela leitura.

### Agora

| pedido | antes | agora |
|---|---|---|
| `PUT` com `x-amz-server-side-encryption: AES256` | `200`, gravado | `501 NotImplemented`, nomeia o cabeçalho |
| `PUT` com `x-amz-server-side-encryption: aws:kms` (+ chave) | `200`, gravado | `501` |
| `POST ?uploads` com SSE | `200`, upload aberto | `501`, nenhum upload aberto |
| `PUT` com `x-amz-copy-source` e SSE | `200`, cópia gravada | `501`, cópia não gravada |
| `x-amz-server-side-encryption-customer-algorithm` | ignorado | `501` |
| `PUT` sem SSE | `200` | `200` |

- A guarda roda **antes** de abrir upload e antes do `requireBucket` do multipart: com a recusa a
  chave nunca existe, e a donagem responderia `403` em vez de dizer o que não é suportado. Num bucket
  cujo dono só nasce do primeiro `PUT`, a leitura depois da recusa é `403` — o contrato do "não
  gravado" é "não veio `200` com o conteúdo".
- **Recusar em vez de cifrar:** cifrar por requisição exigiria guardar a chave na linha e decifrar no
  `GET`; a cifra em repouso aqui é a do sistema de arquivos do nó. Responder `200` a um pedido de
  cifra que ninguém aplicou é a mesma escolha do subrecurso não implementado (§4.21).

**Verificação:** `S3GatewayTests` **+1** (`sse_declarado_no_put_e_recusado_e_nao_grava`): `PUT` com
`AES256` → `501` e a leitura não devolve o corpo; `aws:kms` + chave → `501`; `POST ?uploads` → `501`;
cópia com SSE → `501` e a cópia não aparece; `PUT` sem o cabeçalho, num bucket novo, → `200`.
Vermelho antes: `200` no `PUT`. Mutações **4/4**: sem a guarda no `PUT`; sem a guarda no multipart;
guarda que aceita tudo; lista reduzida só ao KMS.

## 4.50 `Content-Type` com controle ou gigante é `400` (ciclo 140)

O tipo declarado no `PUT` era gravado como veio — e voltado como veio no `HEAD`/`GET`:

```
PUT /s3/<b>/x.txt   Content-Type: text/plain\r\nX-Injetado: 1   → 200
HEAD /s3/<b>/x.txt  → Content-Type: text/plain
                      X-Injetado: 1                    ← segunda linha do valor
```

A injeção só não virava cabeçalho novo porque o Tomcat descarta o que não é nome de campo válido; a
linha extra ia junto com o valor. Um tipo de 300 caracteres também era gravado e devolvido inteiro.
O `Cache-Control` e o `Content-Disposition` já tinham a mesma regra; o tipo — que é o que o
navegador usa para decidir o que fazer com o arquivo — não tinha.

### Agora

| `Content-Type` | antes | agora |
|---|---|---|
| `text/plain; charset=utf-8` | `200`, literal | `200`, literal |
| com `\r\n` / `\n` / `\0` | `200`, gravado | `400 InvalidArgument` |
| acima de 200 caracteres | `200`, gravado | `400 InvalidArgument` |
| em branco | `application/octet-stream` | `application/octet-stream` |
| `PUT` com `x-amz-copy-source` e `REPLACE` | gravado | mesma regra, `400` |

- A regra é a mesma função do `Cache-Control`/`Content-Disposition` (`validateHeaderValue`), aplicada
  no `beginUpload` — o multipart e a cópia passam pelo mesmo ponto, então a recusa vale para os três
  caminhos sem guarda duplicada.
- A recusa vem **antes** de abrir upload: nada é criado nem substituído.

**Verificação:** `S3GatewayTests` **+1** (`content_type_com_controle_ou_gigante_e_recusado_e_nao_grava`):
`\r\n`, `\n`, `\0` e 300 caracteres → `400 InvalidArgument`; `text/plain; charset=utf-8` grava e
volta literal no `HEAD`; tipo em branco vira `application/octet-stream`; cópia com `REPLACE` e tipo
inválido → `400`. Vermelho antes: `200` no tipo com `\r\n`. Mutações **4/4**: sem a validação; sem o
teto de tamanho; sem a recusa de controle; tipo em branco não virando o padrão.

## 4.49 Classe de armazenamento além de `STANDARD` é `501` (ciclo 139)

`x-amz-storage-class` era ignorado e todo `PUT` respondia `200`:

```
PUT /s3/<b>/x.txt   x-amz-storage-class: GLACIER        → 200, objeto em disco quente
PUT /s3/<b>/x.txt   x-amz-storage-class: DEEP_ARCHIVE   → 200, idem
```

O SDK de arquivamento manda `GLACIER`/`DEEP_ARCHIVE`. Quem obedece ao `200` acha que o objeto está
na classe fria: espera dias para restaurá-lo, conta o preço de arquivo e trata a `GET` lenta como
normal. O objeto estava em disco quente, servido na hora. A §3 já declarava que a classe não é
escolhida por requisição — o `200` dizia o contrário.

### Agora

| `x-amz-storage-class` | antes | agora |
|---|---|---|
| `STANDARD`, `standard`, ` STANDARD ` | `200` (caixa/espaço ignorados) | `200` |
| `GLACIER` / `DEEP_ARCHIVE` / `STANDARD_IA` / `ONEZONE_IA` / outra | `200`, gravado | `501 NotImplemented` nomeando a classe |
| `PUT ?partNumber&uploadId` com classe não suportada | parte aceita | não muda nada (a classe vem do `CreateMultipartUpload`) |
| `CreateMultipartUpload` com classe não suportada | upload aberto | `501`, nenhum upload aberto |
| `PUT` com `x-amz-copy-source` e classe não suportada | cópia gravada | `501`, cópia não gravada |

- A comparação é sem caixa e sem espaço das pontas — o valor de classe HTTP não é case-sensitive, e um
  cliente cru manda `standard`.
- A recusa vem **antes** de abrir upload e antes do `requireBucket` do multipart (mesma ordem do SSE,
  §4.48): com a recusa a chave não existe e a donagem responderia `403`.
- A `ListObjectsV2` continua sem `<StorageClass>`: a classe real é `STANDARD` para tudo (§8).

**Verificação:** `S3GatewayTests` **+1** (`classe_de_armazenamento_nao_implementada_e_recusada`):
`GLACIER`/`DEEP_ARCHIVE`/`STANDARD_IA`/`ONEZONE_IA` → `501` nomeando a classe; `STANDARD`, `standard`
e ` STANDARD ` (chaves distintas) → `200`; cópia e multipart com classe → `501` e a `ListObjectsV2`
só mostra o que foi gravado. Vermelho antes: `200` no `GLACIER`. Mutações **4/4**: sem a guarda no
`PUT`; sem a guarda no multipart; guarda que aceita tudo; comparação sem caixa/espaço.

## 4.56 Escopo do SigV4: serviço e região são conferidos (ciclo 146)

O escopo do cabeçalho — `<data>/<região>/<serviço>/aws4_request` — era lido e **usado** no cálculo
(a chave de assinatura deriva de região e serviço), mas nunca conferido contra o gateway:

```
GET /s3/<b>/x.txt   Authorization: ... Credential=<ak>/20260101/enois-1/s3/aws4_request ...   → 200
GET /s3/<b>/x.txt   Authorization: ... Credential=<ak>/20260101/enois-1/ec2/aws4_request ...  → 200
GET /s3/<b>/x.txt   Authorization: ... Credential=<ak>/20260101/qualquer-regiao/s3/... ...    → 200
```

O segredo é o mesmo, então a assinatura confere nos três. Mas o escopo é a única coisa que diz **para o
quê** o cliente assinou: uma credencial usada em outro serviço (ou fora da região) não deixa rastro de
erro nenhum — o cliente assinou errado e foi servido. O S3 recusa o escopo que não é o dele.

### Agora

| serviço | região | antes | agora |
|---|---|---|---|
| `s3` | `enois-1` (a da instalação) | `200` | `200` |
| `s3` | `us-east-1` (região global do S3) | `200` | `200` |
| `ec2`, `execute-api`, `s3-object-lambda`, … | qualquer | `200` | `403 SignatureDoesNotMatch` |
| `s3` | outra região que não `enois-1` | `200` | `403 SignatureDoesNotMatch` |
| vazio (`//`) em qualquer posição | | `403` "ausente" | `403` "ausente" |

- `us-east-1` é aceita porque o SigV4 do S3 é global e é o padrão de todo cliente que não escolhe
  região (`aws s3` sem `--region`). Recusar só por a instalação se declarar `enois-1` quebraria esses.
- A mesma regra vale para a **URL pré-assinada**: `authenticatePresigned` chama a mesma conferência.
  Uma URL re-assinada para `ec2` (mesmo com o segredo certo) não abre o objeto.
- A regra vive em um lugar só, `SigV4Verifier.motivoDeEscopoInvalido`, usada pelos dois caminhos.
- A mensagem nomeia o serviço/região recebidos, para o cliente ver o que assinou.

**Verificação:** `S3GatewayTests` **+1** (`assinatura_de_outro_servico_ou_regiao_e_recusada`, com
assinatura real de cada escopo) e **+1** (`url_pre_assinada_com_escopo_de_outro_servico_e_recusada`,
URL emitida pelo gateway como controle, re-assinada para `ec2` e recusada), mais
`SigV4PresignedTests` **+1** (`url_com_escopo_de_outro_servico_ou_regiao_e_recusada`). Vermelho antes:
`200` no `ec2`. Mutações **5/5**: serviço nunca conferido; região nunca conferida; `us-east-1` recusada;
URL pré-assinada sem a guarda (mutações 1–4 sobreviveram até o teste de integração da URL existir);
cabeçalho sem a guarda.

## 4.57 Metadado de objeto que o gateway não guarda é `501` (ciclo 141)

Três cabeçalhos declarados no upload eram aceitos e descartados — não há coluna para eles na linha do
objeto:

```
PUT /s3/<b>/x.txt   Expires: Wed, 21 Oct 2099 07:28:00 GMT          → 200, HEAD sem Expires
PUT /s3/<b>/x.txt   Content-Language: pt-BR                         → 200, HEAD sem Content-Language
PUT /s3/<b>/x.txt   x-amz-website-redirect-location: /outro         → 200, GET não redireciona
```

Cada um promete um comportamento: o `Expires` apaga o objeto sozinho, o redirecionamento faz o `GET`
devolver `301` (site estático), o idioma escolhe a fonte no navegador. Nenhum acontecia. A ausência do
eco no `HEAD` não avisa: o cliente que não relê a `HEAD` nunca descobre. A §3 nunca listou os três
entre o que volta — a resposta `200` era a única coisa a dizer o contrário.

### Agora

| declarado no `PUT` / `CreateMultipartUpload` / `CopyObject` | antes | agora |
|---|---|---|
| `Expires` | `200`, descartado | `501 NotImplemented` nomeando o cabeçalho |
| `Content-Language` | `200`, descartado | `501` |
| `x-amz-website-redirect-location` | `200`, descartado | `501` |
| `x-amz-meta-*`, `Cache-Control`, `Content-Disposition` | gravado e ecoado | idem (é o que a §3 promete) |

- A guarda roda **antes** de abrir upload, no `PUT` (antes do desvio para o `CopyObject`, então vale
  para `COPY` e `REPLACE`), no `CreateMultipartUpload` e, pelo mesmo ponto, na cópia.
- **Recusar em vez de guardar:** guardar o `Expires` exige coluna e migração e a varrer do objeto;
  fazer o redirecionamento é uma feature de site estático; o idioma só vale como cabeçalho servido.
  Enquanto nenhum dos três existe, `501` é o mesmo contrato do SSE (§4.48) e da classe (§4.49).

**Verificação:** `S3GatewayTests` **+1** (`metadado_de_objeto_nao_suportado_e_recusado_e_nao_grava`):
os três → `501` nomeando o cabeçalho e nada criado; `x-amz-meta-*` + `Cache-Control` +
`Content-Disposition` gravam e voltam iguais no `HEAD`; multipart e cópia (`COPY` e `REPLACE`) → `501`.
Vermelho antes: `200` no `Expires`. Mutações **4/4**: sem a guarda no `PUT`; sem a guarda no multipart
(o teste passou a cobri-la depois que a mutação sobreviveu); guarda que aceita tudo; lista reduzida só
ao `Expires`.

## 4.58 Recusa do `CompleteMultipartUpload` também é XML (ciclo 148)

O `CompleteMultipartUpload` era a única rota do multipart que chamava o serviço sem traduzir a recusa:

```
POST /s3/<b>/mp.bin?uploadId=...   (parte 1 com 5 bytes, parte 2 com 5 bytes)
→ 400 {"code":"EntityTooSmall","message":"A parte 1 tem menos de 5 MiB; ..."}   ← JSON do Control Plane
```

`CreateMultipartUpload`, `UploadPart`, `UploadPartCopy`, `Abort` e `ListParts` já saíam em XML. O
`Complete` é a recusa que o cliente vê **depois de ter enviado todos os bytes** — e a mais comum dele é
o `EntityTooSmall`, que o SDK reconhece no `<Code>` para dizer "reescreva as partes". Em JSON ele trata
como corpo que não sabe ler, e o cliente refaz o upload inteiro.

### Agora

| recusa do `Complete` | antes | agora |
|---|---|---|
| `EntityTooSmall` (parte < 5 MiB que não é a última) | `400` JSON | `400` XML `<Code>EntityTooSmall</Code>` |
| `InvalidPart` (ETag que não é de nenhuma parte) | XML (do próprio serviço) | XML, `Code` preservado |
| `NoSuchUpload` (uploadId inexistente) | XML | XML |
| `MalformedXML` (corpo que não é o XML) | XML | XML |

- A tradução é o mesmo `noVocabularioDoS3` das outras rotas; o `Code` do S3 é preservado. Todos os
  `4xx` do fluxo agora têm `Content-Type` de XML e `<RequestId>`.

**Verificação:** `S3GatewayTests` **+1** (`complete_multipart_recusa_em_xml_e_nao_em_json`):
`EntityTooSmall`, `InvalidPart`, `NoSuchUpload` e `MalformedXML`, cada um conferindo
`Content-Type` de XML, começo `<?xml`, `<Code>` e a **ausência** de `"code"` JSON. Vermelho antes:
`Content-Type: application/json` no `EntityTooSmall`. Mutações **3/3**: sem a tradução; traduzindo só
o `EntityTooSmall` (as outras vazam); traduzindo para o código errado (`InternalError`).

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