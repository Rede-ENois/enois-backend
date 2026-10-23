# Mídia no S3 — como aplicar

O storage de mídia tem dois provedores e a escolha é **por variável de ambiente**, sem deploy de
código: `imagekit` (o de sempre) ou `s3` (o padrão novo). O que já está gravado no banco não muda
nunca — cada asset guarda o próprio endereço, então **asset antigo continua abrindo pelo ImageKit**
depois da virada. É esse o "fallback": ele é estrutural, não um caminho especial no código.

> **Estado:** o upload por URL assinada está implementado, mas **não foi testado contra um bucket
> real** (o S3 só existe em produção). A primeira virada de ambiente é a estreia — vale ler a seção
> "Primeira virada, com calma". A migração dos objetos antigos ainda **não** está implementada.

## Midia servida pelo proprio app (2026-10-23)

O gateway do b7cloud nao responde CORS (`PutBucketCors` -> `InternalError`) e o bucket nao e
publico (GET anonimo -> 403), entao o navegador nao conseguia nem subir nem ler midia direto do
bucket. A saida foi tirar o bucket do caminho do navegador:

- `GET /api/storage/{chave}` — le o objeto do bucket com a credencial do pod, com `Range` (video
  arrasta a barra), `Cache-Control` longo e ETag. Publico, como o `GET /media/{id}`.
- `PUT /api/storage/{chave}` — recebe o arquivo do navegador e repassa ao bucket. Exige sessao e a
  chave exata registrada para aquele asset (nao da para escrever em qualquer lugar).
- `S3UploadSigner` devolve a URL do proxy (`publicBaseUrl`) sempre que ha endpoint custom — ou
  seja, em gateway. Na AWS real continua a URL assinada direto no bucket.
- `V169` reescreve os prefixos antigos (`https://cdn.enois.ia.br/` e
  `https://s3.b7cloud.tech/enois-bucket/`) para `https://api.enois.ia.br/api/storage/` nas colunas
  que guardam midia. O caminho depois do prefixo e o mesmo `objectKey`, entao nada e remapeado.

Consequencia operacional: a largura de banda da midia passa pelo pod. Se um dia o b7 liberar CORS
e leitura publica, basta apontar `REDE_MEDIA_S3_PUBLIC_BASE_URL` de volta para o bucket/CDN e
`REDE_MEDIA_S3_UPLOAD_THROUGH_API=false`.

## Estado verificado em 2026-10-23 (bucket b7cloud)

A infraestrutura mudou: o storage agora é um **gateway S3-compatível do b7cloud**
(`https://s3.b7cloud.tech`), não mais AWS/IRSA. O que um HAR real do navegador mostrou:

- **Prefixo duplicado na URL assinada (corrigido).** `S3UploadSigner` prefixava de novo uma chave
  que já vinha com o prefixo (`S3StorageUrlFactory.objectKey`), então o `PUT` ia para
  `.../enois-bucket/uploads/uploads/media_<id>/...` enquanto o `cdnUrl` gravado no asset apontava
  para `.../uploads/media_<id>/...`. O upload terminava e a mídia nunca aparecia. A chave agora é
  assinada como veio; `S3UploadSignerTests` compara o caminho assinado com o `cdnUrl`.
- **CORS do bucket continua bloqueando o upload do navegador.** O preflight `OPTIONS` em
  `https://s3.b7cloud.tech/enois-bucket/...` responde **403 sem nenhum header `Access-Control-*`**,
  e é o navegador que faz o `PUT` direto na URL assinada. Falta configurar CORS no bucket para
  `PUT`/`GET` com origem `https://enois.ia.br` e `content-type` nos headers permitidos.
- **Leitura pública ainda não existe.** `GET` anônimo em `.../enois-bucket/uploads/...` responde
  **403**, e a base antiga `cdn.enois.ia.br` está em **NXDOMAIN** no nameserver autoritativo. Os
  assets migrados continuam gravados com essa base antiga, então só voltam a abrir com o registro
  de DNS criado (ou reescrevendo o `cdn_url` para a base nova).
- `GET /api/media/migration` em produção: `total=3502`, `imagekit=1737`, `s3Copied=1765`,
  `s3Active=1765`, `errors=113`, worker desligado (`enabled=false`).

## Estado verificado em 2026-10-19

Verificação feita contra a produção e o código deste repo:

- **A virada ainda não começou.** O manifesto roda com `REDE_MEDIA_STORAGE=imagekit`; upload novo
  continua indo para o ImageKit e **nenhum asset foi migrado**. O "sem ninguém perceber" está
  garantido só porque nada mudou — não porque a migração esteja em andamento.
- **Bloqueio 1 — a CDN pública não serve HTTPS.** `cdn.enois.ia.br` aponta para o CloudFront
  (`f2b96876…cloudfront.net`), mas o certificado apresentado é o padrão `*.cloudfront.net`: o
  `subjectAltName` não cobre o domínio e o TLS falha na validação. Com `-k`, o CloudFront responde
  **403**. Falta configurar o **alternate domain name** + certificado ACM (us-east-1) na
  distribuição. Enquanto isso, `REDE_MEDIA_S3_PUBLIC_BASE_URL=https://cdn.enois.ia.br` não serve
  nenhuma leitura — virar o storage agora deixaria toda mídia nova quebrada.
- **Bloqueio 2 — exclusão no S3 não tem gateway.** `S3StorageConfig` cria URL factory, presigner e
  signer; **não** cria `MediaStorageGateway`. O bean do ImageKit é incondicional, então em modo s3
  o delete de mídia chamaria a API do ImageKit. Falta um `S3MediaStorageGateway` (`DeleteObject`)
  e condicionar o gateway do ImageKit ao modo `imagekit`.
- **Cópia executada em produção (2026-10-19): falhou por CREDENCIAL, não por código.**
  `GET /api/media/migration` mostrou `total=1537, imagekit=1537, s3Copied=0, errors=5` e
  `lastError="copia para o S3 falhou: Unable to load credentials from any of the providers in the
  chain AwsCredentialsProviderChain(...)"`. O pod usa a ServiceAccount **`socialthing-sa`**, que
  não tem uma role IRSA com acesso ao bucket. **Nada foi relinkado** (`cdn_url` intacto) — a
  garantia de não perder mídia funcionou na prática. A cópia ficou **pausada** (env `false`) até
  a role `s3:PutObject/GetObject/DeleteObject` ser anexada à SA (annotation
  `eks.amazonaws.com/role-arn` ou role no node/IRSA).
- **Bloqueio 3 — migração dos antigos: RESOLVIDO no código (V92, 2026-10-19).** A cópia para o S3
  agora roda em segundo plano (`MediaMigrationWorker`) e a fase de flip está implementada, mas
  **desligada por padrão** até o Bloqueio 1 (CDN) ser resolvido. O status público
  `GET /media/migration` mostra `total`, `imagekit`, `s3Copied`, `s3Active`, `errors` e `lastError`.
- **Causa de código encontrada e corrigida (2026-10-19): faltava o módulo `sts`.** A cadeia padrão
  lista `WebIdentityTokenCredentialsProvider()`, mas trocar o token do service account por
  credenciais é chamada de STS; sem `software.amazon.awssdk:sts` no classpath o provider não assume
  a role e a cadeia termina em "Unable to load credentials" mesmo com o IRSA configurado. O módulo
  entrou no `pom.xml` na mesma versão do `s3` (2.21.1), e o `dependency:tree` confirma
  `software.amazon.awssdk:sts:jar:2.21.1:compile`. Os três clientes (`S3Client` do storage, o da
  migração e o `S3Presigner`) já usavam a cadeia padrão, sem credencial em código nem provider
  fixo. **Continua dependendo do cluster:** conferir no pod `AWS_ROLE_ARN` /
  `AWS_WEB_IDENTITY_TOKEN_FILE` (IRSA) ou `AWS_CONTAINER_CREDENTIALS_FULL_URI` (EKS Pod Identity)
  com `kubectl -n forest-apps describe pod`, e a role com `s3:PutObject/GetObject/DeleteObject` no
  bucket. Só depois disso religar a migração e ver `errors` parar de crescer com `s3Copied` subindo.
- **CORS do bucket continua ausente (provado 19/10): o upload do NAVEGADOR falha.** O preflight
  `OPTIONS` contra `forest-dev-socialthing-images` responde 403 sem nenhum header
  `Access-Control-*`; o `PUT` assinado do browser é bloqueado e o composer mostra
  "NetworkError when attempting to fetch resource". **Cuidado com o status:** `s3Copied` conta
  linhas com `s3_migrated_at`, e todo upload novo em S3 NASCE com esse campo preenchido
  (`MediaAsset.pending`, `onS3 ? createdAt : null`) — as 13 unidades vistas em 19/10 são linhas
  órfãs de tentativas que falharam no PUT, não mídia no bucket. Corrigir no bucket
  (Permissions → CORS configuration) liberando `PUT` para `https://enois.ia.br`; não precisa
  deploy de código.
- **Upload assinado nunca rodou contra o bucket real** (não há credencial AWS nesta máquina); a
  primeira virada é a estreia.
- **Vídeo enviado como arquivo:** o player do Énóis pedia a URL crua do ImageKit e recebia
  `403 ELIMIT` (cota de transformação de vídeo da conta) — o vídeo não tocava. O front passou a
  pedir o **original** (`?tr=orig-true`) em todos os players e no `og:video` (commit `16152db`);
  o vídeo de teste responde **206** com Range e `readyState=4` no Chrome. Em S3 a URL fica
  intacta (sem transformação), mas o poster de vídeo continua sem derivada (limitação abaixo).

## Migração OPORTUNISTA (uso normal converte o acervo)

Além do lote, o acervo migra sozinho conforme é USADO — ligado pelo MESMO
`REDE_MEDIA_MIGRATION_ENABLED` (desligado = nada migra, nem no acesso):

- **Mídia de post/comentário**: a grade do post chama `GET /media/{id}`; essa leitura agenda a
  cópia do asset para o S3 (claim ATÔMICO no banco — `media_assets.s3_claimed_at` — garante UMA
  cópia por asset mesmo com várias réplicas; claim velho expira em 10 min).
- **Fotos de perfil**: as respostas de perfil agendam a migração de `avatar`/`banner`.
- **Demais referências** (capas de comunidade/grupo/empresa/evento/jogo, logo de vaga, página de
  criador): a varredura em lote (`media_url_migrations` + registro de colunas no
  `UrlReferencePersistenceAdapter`) agenda as que ninguém acessou.
- **Troca de referência**: só depois de a URL pública do S3 responder (`verify`), por UPDATE
  condicional (`where coluna = :antiga`) — mudança concorrente de foto nunca é sobrescrita e
  referências compartilhadas são trocadas de uma vez.
- **SSRF**: só https nos hosts do ImageKit (allowlist) ou caminho relativo `/uploads/`; nenhuma
  outra origem é baixada.
- **Falhas**: ficam registradas (`attempts`/`last_error`), referências intactas, até 5 tentativas;
  a leitura NUNCA depende da migração.

**Ordem para destravar:** (1) **credencial S3 para o pod**: role IRSA com
`s3:PutObject/GetObject/DeleteObject` no bucket/prefixo, anexada à `socialthing-sa` (é o que o
worker já acusou em `lastError`) — o módulo `sts`, que faltava no build, já foi adicionado; com o
pod novo e a role válida, religar `REDE_MEDIA_MIGRATION_ENABLED=true`; (2)
CloudFront com alternate domain + ACM e conferir o `curl -I` com certificado válido; (3)
`S3MediaStorageGateway` (delete) e condicionar o gateway do ImageKit ao modo `imagekit`; (4) ligar
o flip (`REDE_MEDIA_MIGRATION_FLIP_ENABLED=true`) e conferir o `s3Active` subindo; (5) testar um
upload real numa réplica e só então virar `REDE_MEDIA_STORAGE=s3`; (6) preservar os objetos no
ImageKit (a migração nunca apaga).

## O que a AWS precisa ter

| item | por quê |
|---|---|
| Bucket + região | onde os objetos vão morar |
| Permissão para o pod: `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject` no prefixo | assinar a subida, ler e apagar de verdade (a exclusão de mídia passa a valer no bucket) |
| CORS no bucket liberando `PUT` de `https://enois.ia.br` | o arquivo sobe **do browser** direto para o bucket (o pod não recebe bytes) |
| Leitura pública no prefixo **ou** CloudFront na frente | o `cdnUrl` é gravado no banco e usado no feed e na miniatura: tem de ser um endereço estável, não uma URL assinada que expira |

Credencial **não** vai em variável nem em código: o pod resolve pela cadeia padrão do SDK dentro do
cluster (IRSA no service account). As tarefas de permissão acima são no **role do service account**.

## Variáveis de ambiente

| variável | exemplo | observação |
|---|---|---|
| `REDE_MEDIA_STORAGE` | `s3` | sem ela, o app segue em `imagekit` |
| `REDE_MEDIA_S3_BUCKET` | `enois-media` | obrigatória quando o storage é `s3` (o app **não sobe** sem ela — melhor falhar no boot do que gravar mídia apontando para lugar nenhum) |
| `REDE_MEDIA_S3_REGION` | `sa-east-1` | padrão `us-east-1` |
| `REDE_MEDIA_S3_PREFIX` | `uploads/` | padrão `uploads/`, a mesma forma dos objetos do ImageKit (facilita a migração). Prefixo `/` significa a **raiz** do bucket |
| `REDE_MEDIA_S3_PUBLIC_BASE_URL` | `https://cdn.enois.ia.br` | vazio = endereço do bucket na região |

## Primeira virada, com calma

1. Criar bucket, CORS, leitura (ou CloudFront) e as permissões no role do pod.
2. Subir **uma** réplica com as variáveis acima (ou aplicar no deployment e deixar o rollout normal).
3. Conferir o boot nos logs: com bucket/região ausentes o app falha com mensagem explícita — é o
   comportamento desejado.
4. Publicar **uma** foto em homologação e conferir na aba Rede do navegador:
   - a resposta de `POST /api/media/uploads` traz `"provider":"S3"` e um `uploadUrl` começando no
     bucket ou no CDN;
   - a subida é um `PUT` para esse endereço, com o `Content-Type` **idêntico** ao assinado;
   - o `cdnUrl` abre no navegador depois da subida.
5. Só então virar o ambiente inteiro. **Voltar atrás** é trocar `REDE_MEDIA_STORAGE` para `imagekit`
   e reiniciar: os objetos que já foram para o S3 continuam sendo servidos pelo endereço gravado no
   asset, então nada quebra.

Se a subida falhar com **403**, quase sempre é uma das duas: CORS do bucket sem `PUT` para
`https://enois.ia.br`, ou `Content-Type` diferente do assinado.

## Migração dos objetos antigos (implementada na V92)

Duas fases, ambas idempotentes e em rodadas pequenas (sem janela de manutenção):

1. **Cópia** (`REDE_MEDIA_MIGRATION_ENABLED=true`): a cada rodada o worker pega um lote de assets
   ainda no ImageKit, baixa o **original** (vídeo/áudio com `?tr=orig-true`, senão a cota de
   transformação responde 403), sobe para o S3 com a chave em barras de verdade
   (`uploads/media_<id>/media/<uuid>/<arquivo>`) e **confere pelo HeadObject** que o tamanho bate.
   Só então grava `s3_cdn_url` e `s3_migrated_at`. O `cdn_url` **não muda** nesta fase e o objeto
   no ImageKit é preservado — nenhum usuário perde mídia. Falha incrementa
   `s3_migration_attempts`/`s3_last_error` e tenta de novo (até 10 tentativas).
2. **Flip** (`REDE_MEDIA_MIGRATION_FLIP_ENABLED=true`): só troca o `cdn_url` para o endereço do S3
   **depois de a URL pública responder** (HEAD 2xx com o content-type certo). Se o CDN estiver
   fora, o asset continua no ImageKit. É o que impede a migração de quebrar a leitura.

Variáveis (todas opcionais; o padrão é desligado):

| variável | padrão | para quê |
|---|---|---|
| `REDE_MEDIA_MIGRATION_ENABLED` | `false` | liga a fase de cópia |
| `REDE_MEDIA_MIGRATION_FLIP_ENABLED` | `false` | liga o flip (só com o CDN válido) |
| `REDE_MEDIA_MIGRATION_BATCH_SIZE` | `10` | assets por rodada |
| `REDE_MEDIA_MIGRATION_DELAY_MS` | `30000` | intervalo entre rodadas |
| `REDE_MEDIA_MIGRATION_INITIAL_DELAY_MS` | `45000` | espera depois do boot |

Progresso: `GET /api/media/migration` (contagens agregadas e o último erro; sem ids/URLs).
O asset só conta em `s3Active` quando o `cdn_url` já aponta para o S3; `s3Copied` inclui a cópia
verificada que ainda não foi relinkada.

**Fora desta fase:** as URLs gravadas fora de `media_assets` (avatar/banner de perfil, capa de
comunidade, banner de grupo, capa de evento etc.) continuam apontando para o ImageKit, que
**preserva os objetos**. O relink dessas colunas é a etapa seguinte, com o mesmo mapa
objeto→S3; nada quebra enquanto isso porque o ImageKit segue servindo.

## Limitações conhecidas

- **Sem as transformações do ImageKit.** O recorte `?tr=w-1200,h-630` e o quadro de vídeo
  `/ik-thumbnail.jpg` não existem no S3: a miniatura de post com foto passa a usar a imagem **crua**
  (o cartão de página não depende disso — ele é desenhado por nós) e vídeo fica sem poster até
  entrarem derivadas (ffmpeg no upload ou CloudFront + Lambda).
- Objetos órfãos no ImageKit continuam lá; apagar é decisão separada e depois de tudo conferido.

## Onde está o código

- `media/adapters/out/storage/S3StorageUrlFactory` — endereço dos objetos (sem rede, só composição).
- `media/adapters/out/storage/S3UploadSigner` — URL assinada (15 min, mesmo prazo da janela do asset).
- `media/adapters/out/storage/S3StorageConfig` — liga tudo isso só quando `REDE_MEDIA_STORAGE=s3`.
- `media/application/MediaService#createUpload` — devolve `provider` para o front decidir o caminho.
- front: `use-media-upload` escolhe entre `PUT` assinado (S3) e o multipart do ImageKit.
