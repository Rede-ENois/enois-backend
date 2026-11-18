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
| Emitir URL pré-assinada | `POST /s3/{bucket}/presign?key=&method=GET\|PUT&expires=` | ✅ sessão do dono; devolve `url`, `method`, `expiresIn`, `expiresAt` |
| Usar URL pré-assinada | `GET`/`PUT /s3/{bucket}/{key}?X-Amz-Signature=…` | ✅ sem `Authorization`, sem sessão |

**Não implementado**, e o brief manda não anunciar como se existisse:

- **Multipart Upload** — recusado. Um "multipart" que grava o arquivo inteiro perde dados no retomado,
  e é exatamente o que o brief pede para não fazer.
- **CopyObject**, **ACL**, **tagging**, **`versionId`**, **bucket policy**, **`POST` de formulário**.
- **Regiões múltiplas**, **classes de armazenamento na resposta** (sempre `STANDARD`; a classe real é a
  da §8 e não é escolhida por requisição).

**Diferença semântica declarada**: o `DeleteObject` grava **lápide**, não apaga os bytes. É o mesmo
comportamento do S3 em bucket versionado — mas num bucket **sem** versionamento, o S3 real apagaria. A
escolha aqui é a do módulo inteiro: apagar byte é coletado pelo nó, nunca por requisição de cliente
(§14 e §16 da doc do Storage). Um cliente que espere "liberou espaço na hora" se engana.

## 4.0 Donagem do bucket — por verbo

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

## 4.1 URLs pré-assinadas

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

## 5. Erros

O corpo é XML no formato do S3, com o `Code` que o SDK lê:

```xml
<?xml version="1.0" encoding="UTF-8"?><Error>
  <Code>SignatureDoesNotMatch</Code><Message>...</Message><Resource>...</Resource>
</Error>
```

Códigos: `AccessDenied`, `InvalidAccessKeyId`, `SignatureDoesNotMatch`, `NotImplemented`.

## 6. O que NÃO está entregue

- **Multipart** — o maior item ausente; arquivos grandes por SDK que usa multipart por padrão
  (>8 MB no boto3, >5 MiB no SDK Java) **falham**. Documentado aqui para que ninguém descubra em
  produção.
- **Presigned URLs para `GET` e `PUT` existem**; `DELETE`/`LIST` por URL não são emitidos de
  propósito (link vazado não apaga nem lista). URL pré-assinada para **multipart** não existe porque
  multipart não existe.
- **Access Keys administradas pelo painel** — a tabela e o repositório existem; não há ainda rota de
  painel para criar/revogar (a criação hoje é por SQL/admin direto). Lacuna declarada.
- **Credenciais temporárias (STS)** — `X-Amz-Security-Token` não é processado.
- **Quotas e rate limiting por credencial** — a rota herda o limitador global, não um por Access Key.