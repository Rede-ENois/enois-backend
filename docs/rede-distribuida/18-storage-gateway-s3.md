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
| GetObject | `GET /s3/{bucket}/{key}` | ✅ `Range`/206/416, `304` por `If-None-Match` |
| HeadObject | `HEAD /s3/{bucket}/{key}` | ✅ |
| DeleteObject | `DELETE /s3/{bucket}/{key}` | ✅ **lápide** (como o S3 com versionamento), 204 |
| ListObjectsV2 | `GET /s3/{bucket}?list-type=2` | ✅ `prefix`, `max-keys` (teto 1000), `continuation-token` |
| ListObjects (v1) | `GET /s3/{bucket}` | ✅ `marker` |
| ListBuckets | `GET /s3` | ✅ só os buckets do dono |
| DeleteBucket | `DELETE /s3/{bucket}` | ⚠️ recusa 409 se houver objeto; **501** para bucket vazio (não há tabela de buckets vazios) |
| CreateMultipartUpload | `POST /s3/{bucket}/{key}?uploads` | ✅ devolve `UploadId` |
| UploadPart | `PUT /s3/{bucket}/{key}?partNumber=&uploadId=` | ✅ fora de ordem; reenvio substitui |
| CompleteMultipartUpload | `POST /s3/{bucket}/{key}?uploadId=` | ✅ monta o corpo na ordem pedida; parte ausente recusa |
| AbortMultipartUpload | `DELETE /s3/{bucket}/{key}?uploadId=` | ✅ apaga partes e registro |
| ListParts | `GET /s3/{bucket}/{key}?uploadId=` | ✅ o que já chegou |
| Emitir URL pré-assinada | `POST /s3/{bucket}/presign?key=&method=GET\|PUT&expires=` | ✅ sessão do dono; devolve `url`, `method`, `expiresIn`, `expiresAt` |
| Usar URL pré-assinada | `GET`/`PUT /s3/{bucket}/{key}?X-Amz-Signature=…` | ✅ sem `Authorization`, sem sessão |

**Não implementado**, e o brief manda não anunciar como se existisse:

- **CopyObject**, **ACL**, **tagging**, **`versionId`**, **bucket policy**, **`POST` de formulário**.
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
| DeleteBucket | ✅ | ✅ | antes |
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

## 5. Erros

O corpo é XML no formato do S3, com o `Code` que o SDK lê:

```xml
<?xml version="1.0" encoding="UTF-8"?><Error>
  <Code>SignatureDoesNotMatch</Code><Message>...</Message><Resource>...</Resource>
</Error>
```

Códigos: `AccessDenied`, `InvalidAccessKeyId`, `SignatureDoesNotMatch`, `NotImplemented`.

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

## 6. O que NÃO está entregue

- **Presigned URLs para `GET` e `PUT` existem**; `DELETE`/`LIST` por URL não são emitidos de
  propósito (link vazado não apaga nem lista). URL pré-assinada **por parte** do multipart não existe:
  o upload multipart é feito com credencial, não por link.
- **Credenciais temporárias (STS)** — `X-Amz-Security-Token` não é processado.
- **Quotas e rate limiting por credencial** — a rota herda o limitador global, não um por Access Key.
  A **cota de espaço por participante** existe desde o ciclo 66 (`rede.storage.max-bytes-per-owner`,
  doc 12 §2.3): o upload que estoura responde `507 QuotaExceeded` no XML do S3, com o número na
  mensagem.