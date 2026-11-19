# 19 — Segurança do Storage: certificado mTLS do nó e cifra em repouso (brief §10)

O §10 do brief lista "mTLS" e "certificados individuais" — e o doc
[04-identidade-e-autorizacao.md](04-identidade-e-autorizacao.md) §1 já descrevia **duas credenciais por
nó**, com o certificado na coluna da esquerda. Até aqui só existia a da direita.

## 1. O que passou a existir

| peça | onde |
|---|---|
| emissão do certificado a partir de um CSR | `NodeCertificateIssuer` |
| CA da rede (chave na configuração, nunca no banco) | `NodeCertificateService` |
| tabela `network_node_certificates` | `V358__network_node_certificates.sql` |
| CSR no enroll | `POST /network/nodes/enroll` com o campo `csr` |
| renovação (rotação) | `POST /network/nodes/me/certificate` |
| revogação | `POST /admin/network/...` — ver §5 |

O certificado é **Ed25519**, com validade de 90 dias e `notBefore` 5 minutos no passado (relógio de
máquina doméstica atrasada recusava o certificado recém-emitido). O sujeito é `CN=ENOIS-NODE-<id>`,
o emissor `CN=ENOIS-NETWORK-CA`, e as extensões são `basicConstraints CA:false` (o nó não emite
certificado) e `extendedKeyUsage` serverAuth+clientAuth.

## 2. A prova de posse do CSR

O CSR é autoassinado por natureza — qualquer um gera um. O que o torna confiável é o Control Plane
**conferir a autoassinatura contra a chave pública que o CSR alega** antes de copiar essa chave para
dentro de um certificado com o nome do nó. Sem essa conferência, o emissor carimbaria qualquer chave
pública que chegasse — e um certificado emitido assim é exatamente o que o mTLS usa.

A asserção que prende isso: `csr_com_autoassinatura_falsa_e_recusado` gera um CSR sintaticamente
perfeito, com a chave pública do "nó" e a assinatura de **outra** chave. Com a verificação desligada
(mutação), o certificado sai válido e o teste falha.

A única chave pública herdada do CSR é essa — conferida. Nome, validade, número de série e emissor
são construídos no Control Plane; o cliente não escolhe o nome do próprio certificado.

## 3. Onde as chaves vivem

| chave | onde | quem lê |
|---|---|---|
| privada do **nó** | `$NODE_HOME/node.key` (gerada lá pelo `install.sh`, §5.1) | ninguém mais — nunca viaja |
| privada da **CA** | `REDE_NETWORK_CA_KEY` (PKCS#8 Ed25519 em base64) | só o emissor, no Control Plane |
| pública da **CA** | `REDE_NETWORK_CA_PUBLIC` (SPKI Ed25519 em base64) | emissor (conferir) e nós (pino) |

A privada da CA **não é gravada no banco**. É o que o brief §10 pede quando diz "Considerar que
operadores podem acessar fisicamente os discos" e "Não distribuir chaves mestras aos participantes":
com a chave fora do banco, quem copia o disco lê certificados públicos, não assina.

Sem as duas variáveis, `NodeCertificateService.enabled()` é falso e a emissão responde **503
`NODE_CA_UNAVAILABLE`** com a causa. Não há CA gerada em repouso: uma CA que o processo cria sozinho
seria uma segunda autoridade escondida, e o comportamento de produção dependeria de um arquivo que
apareceu sem ninguém decidir.

## 3.1 Quem pode entrar: o estado do nó manda

`accepts(nodeId, certificado)` — a decisão que a terminação TLS vai usar — recusa **antes de olhar o
certificado** quando o nó não é `ACTIVE`. Só o estado `ACTIVE` opera, o mesmo critério do despacho de
tarefa (`NodeStatus.canOperate`): `PAUSED`, `SUSPENDED`, `QUARANTINED`, `PENDING_APPROVAL` e `REVOKED`
ficam de fora.

O certificado prova **quem é**; o estado do autorizador decide **se pode entrar**. Sem essa ordem, a
revogação do nó mataria a API key e deixaria o certificado vigente — e o handshake aceitaria de volta,
por TLS, o nó que o admin derrubou. Revogar o nó também revoga o certificado na mesma transação
(`NetworkNodeService`), então as duas barreiras concordam com o banco.

## 3.2 Credencial errada tem teto (brief §10, "detecção de comportamento anormal")

A API key do nó e a Access Key do S3 são **públicas por natureza**: o id do nó aparece no painel, no
cadastro e no log; a Access Key é a metade pública do par SigV4. O segredo é a outra metade, e até
aqui nada contava as tentativas erradas contra ele — o custo de um atacante era a banda, e o do
Control Plane era uma comparação de hash por requisição.

Duas contas novas em `IpRateLimiter`, separadas de tudo o que já existia:

| ação | chave da conta | padrão |
|---|---|---|
| `node-auth` | `nodeId` | 10 falhas / 5 min |
| `s3-auth` | Access Key | 20 falhas / 5 min |

Regras que as duas carregam, e por quê:

- **Só a falha conta.** `permiteTentarAutenticacao` consulta e `registraFalhaDeAutenticacao` soma —
  o acerto não registra nada. Se o acerto gastasse o orçamento, o nó saudável (que consulta a fila
  a cada poucos segundos) chegaria ao teto sozinho, e a correção de um cliente legítimo seria barrada
  pelo próprio sucesso;
- **é por credencial, não por IP.** Quem ataca troca de IP; o alvo do teste é a credencial. O teto
  por IP já existe para as rotas públicas (`node-join`, `node-reveal`) e continua lá;
- **sem orçamento, nem a comparação acontece.** A recusa é antes da busca por hash: é o que mantém o
  Control Plane respondendo a quem tem a chave certa enquanto alguém testa a errada;
- **a resposta diz que é teto, não que a chave está errada.** `429 NODE_AUTH_RATE_LIMITED` no nó e
  `429 SlowDown` no S3 (o código que o SDK entende como "espere"). Responder 401 ali esconderia do
  operador que o bloqueio é de tentativas e não de credencial;
- **teto `0` desliga.** É a forma de desligar sem flag própria — a operação que confia na borda;
- **a janela expira.** Cinco minutos depois, as falhas saem da conta. Um bloqueio permanente puniria
  quem colou a chave errada.

Os parâmetros são `rede.security.rate-limit.node-auth.{limit,window-minutes}` e
`rede.security.rate-limit.s3-auth.{limit,window-minutes}`.

**O que ainda falta nesta frente** (declarado, não escondido): o teto é **em memória e por
instância** — com várias instâncias de API, cada uma tem a sua conta, e o atacante distribuído ganha
o produto das contas. Uma conta compartilhada exige o Redis/`network_instance_signals` que a rede
ainda não usa para limitador. E a falha **não é auditada como evento**: com 10 por 5 min e um id de
nó público, cada varredura de log enche a trilha. O que se observa hoje é a contagem no limitador; o
evento auditado ("muitas credenciais erradas para o nó X") é o passo seguinte.

**Verificação:** `AuthAttemptRateLimitTests` **6** (teto exato, acerto não gasta, isolamento por
credencial, contas separadas, janela expira, zero desliga), `NetworkNodeAuthRateLimitTests` **3**
pela rota real (décima primeira chave errada é 429 com código próprio e o vizinho continua entrando;
vinte acertos nunca barram; a chave certa depois de nove erros entra) e o caso novo de
`S3GatewayTests` (vinte assinaturas erradas, a seguinte é `429 SlowDown`). Mutações: serviço sem teto,
acerto gastando orçamento, gateway sem teto na assinatura, falha não contada — **4 de 4 derrubam**.

## 4. O que NÃO está implementado

Esta entrega é a **identidade criptográfica**: o certificado existe, é emitido com prova de posse, é
revogado e é renovado. Ela **não** é a terminação TLS. Honestamente:

- **Não há `server.ssl` nem `clientAuth` no Spring.** O Control Plane continua atendendo HTTP na
  aplicação; a terminação TLS é do proxy da borda (`docker-prod4`), e ela ainda não exige certificado
  de cliente nas rotas `/network/`. Enquanto isso, a autorização de cada requisição de nó é a API key,
  como sempre foi — o certificado **acrescenta** uma camada que o proxy pode passar a exigir, não
  substitui a que existe.
- **Não existe rota de painel dedicada para revogar só o certificado.** O serviço tem `revoke`, mas a
  revogação que existe é a da **transição do nó**: revogar (`REVOKED`) ou desconectar (`SUSPENDED`) já
  revoga o certificado na mesma transação, junto com a API key.
- **Não há rotação automática.** O nó renova quando quiser, pela rota de renovação; não há worker
  avisando "faltam 15 dias". A validade de 90 dias e o `notAfter` na resposta dão ao nó a informação
  para se programar, mas quem decide renovar é ele.
- **CRL/OCSP não existem.** A revogação é consultada no banco (`revoked_at`), o que funciona para
  decisão do Control Plane e não para um verificador offline.
- **Criptografia em repouso do corpo do nó: implementada** (ver §7). **Envelope (chave por objeto ou
  por bucket, com a chave fora do storage node) ainda não existe** — hoje a chave é única da instalação
  e vive na configuração, que é exatamente o que o envelope precisa mudar.

## 5. Operação

```bash
# 1. uma vez, no Control Plane: gerar a CA (fora do banco, num cofre)
#    o par é Ed25519; o que vai para as variáveis é o DER em base64
#    (use o comando da sua operação: openssl genpkey -algorithm ED25519 ...)

# 2. configurar no ambiente
REDE_NETWORK_CA_KEY=<pkcs8-der-base64>
REDE_NETWORK_CA_PUBLIC=<spki-der-base64>

# 3. o nó gera o par e o CSR, e manda no enroll
POST /network/nodes/enroll
{ "provisionToken": "...", "csr": "-----BEGIN CERTIFICATE REQUEST-----..." }

# ou, com a API key já emitida:
POST /network/nodes/me/certificate   (X-Node-Id + X-Node-Key)
{ "csr": "-----BEGIN CERTIFICATE REQUEST-----..." }
```

### 5.1 O instalador gera a chave e leva o CSR

O passo 3 acima é executado pelo `install.sh`, não pelo operador:

| arquivo em `$NODE_HOME` | o que é | permissão |
|---|---|---|
| `node.key` | chave privada Ed25519 do nó (PKCS#8), gerada na primeira instalação | `600` até o `--start`, depois `640` (uid 10001 do container) |
| `node.csr` | o CSR Ed25519 enviado no enroll (público; pode ser recriado do `node.key`) | `600` |

- **A privada nunca sai da máquina.** O corpo do enroll leva só a pública, dentro do CSR; a chave
  privada não é serializada em lugar nenhum.
- **A chave é preservada** se já existir (`install.sh` não gera outra): gerar uma nova a cada execução
  trocaria a identidade por baixo do certificado vigente.
- **`--backup` arquiva `node.key` junto com `node.json` e `api-key`** e o `--restore` a devolve com
  `600`. Restaurar a identidade sem a chave daria um certificado que o nó não consegue usar.
- **`openssl` é pré-requisito do enroll** e o instalador recusa sem ele, com o nome do comando — em
  vez de enrolar sem CSR e deixar o nó sem identidade criptográfica.

`node-doctor.sh` checa a chave como checa a API key e o pino: presente com `600` (ou `640`, o que o
`--start` deixa para o uid do container) é PASS; outra permissão é WARN com o `chmod` a rodar; ausente
é WARN — nó instalado antes desta versão opera só com a API key, e o doctor diz isso em vez de deixar o
operador achar que a identidade criptográfica existe.

O que **não** existe: verificação da combinação `node.key` × certificado guardado no nó (o
`node.json` traz o PEM e a série, mas nada confere que a chave corresponde) e uso da chave pelo
runtime (`node-runtime.sh` ainda não assina nem decifra com ela — é o passo do envelope em §7.1).

`REDE_NETWORK_CA_PUBLIC` ausente com a privada presente **derruba o boot** com mensagem explícita:
emitir certificado que não se consegue conferir depois recusaria todos os nós no handshake, e o
motivo apareceria longe da causa.

## 6. Evidência

- `NodeCertificateIssuerTests` — 5 casos: emissão, CSR forjado (discriminante), algoritmo não-Ed25519,
  CSR vazio/ilegível, e que a chave privada da CA não sai na resposta.
- `NodeCertificateServiceTests` — 7 casos: emissão + auditoria, re-emissão revoga a anterior, banco só
  guarda o público, CSR de outra chave recusado sem gravar, 503 sem CA, nó revogado não recebe, revogar
  sem vigente é 404 sem auditoria.
- `NetworkNodeCertificateTests` — 3 casos de rota real: enroll com CSR devolve certificado assinado pela
  CA e o nó renova com a chave (lista branca do `WriteRouteInterceptor` inclusa; sem ela, 401 — mutação
  conferida); CSR forjado é 400 e não grava; renovação sem chave é 401.
- `scripts/tests/storage-migration-test.sh` em **MariaDB 11.4 real**: **82 PASS / 0 FAIL**, aplicação 2×,
  incluindo "a tabela não guarda chave privada", unicidade do fingerprint, rotação com histórico e
  preservação dos dados entre as duas aplicações.
- `scripts/tests/node-runtime-test.sh` — **103 asserções** (94 + 9 desta rodada), com `openssl` real no
  PATH (um openssl simulado não provaria CSR nenhum): `node.key` nasce `600` e é Ed25519, o CSR
  autoassina e é Ed25519, o enroll leva o CSR como PEM e **nunca** leva `PRIVATE KEY`, e o `--restore`
  devolve a **mesma** chave (comparação da pública, não só da existência).
  Mutações conferidas, cada uma derrubando **só** a asserção dela: tirar o `csr` do corpo do enroll
  (`enroll não levou o CSR`); tirar `node.key` do `--backup` (`a chave do nó volta com o restore`);
  gerar uma chave que não é Ed25519 (`a chave do nó é Ed25519`). Do doctor (108 asserções): sem o ramo
  da chave (`doctor avisa a falta da chave do nó`), aceitando só `600` (`640 (uid do container) é
  aceito`) e sem ler a permissão (`chave legível por outros é WARN, não silêncio`).

## 7. Criptografia em repouso do corpo do nó

O brief §10 pede que "a retirada física do disco" não revele os arquivos. O corpo que o nó grava em
disco (`NodeBodyStore.write`) passa a ser cifrado **antes** de receber o nome final:

- **AES-256-GCM em fluxo**, pela classe `EncryptedBodyFile`. O arquivo nunca é carregado inteiro na
  memória — é um vídeo de centenas de MB, e o `SecretCipher` (que opera em `String`) não serve.
- **Layout**: `ENOISENC1` (9) | versão (1) | IV (12) | GCM(corpo). O cabeçalho é o que permite ao nó
  distinguir corpo cifrado de corpo legado.
- **A chave é a mesma** de `REDE_SECURITY_ENCRYPTION_KEY` — a que já protege o segredo da credencial S3
  e o TOTP. Um segundo material de chave seria um segundo segredo para o operador proteger.
- **Autenticidade, não só sigilo**: o GCM autentica; um byte trocado no disco faz a leitura falhar com
  `IOException` em vez de servir conteúdo forjado.
- **Sem a chave, grava em claro e diz isso** (`encryptionAtRest()` é falso). Não finge cifrar.
- **Corpo legado continua legível**: arquivo sem o cabeçalho é servido como está, para que ligar a
  cifra numa instalação com réplicas não reprove todos os nós de uma vez.
- **A leitura decifra os dois formatos**: `openVerified` reconhece o envelope (`ENOISENV1`) e a réplica
  local é lida pelo próprio `NodeBodyStore.read` (`ENOISENC1`). O catálogo não devolve arquivo cifrado
  ao cliente.
- **A conferência de tamanho é do texto claro** (`NodeBodyStore.plainBytesOf`: tamanho do arquivo menos
  cabeçalho e tag no caso cifrado, o próprio tamanho no corpo legado). Ela é feita antes de abrir o
  fluxo — percorrer o objeto para contá-lo custaria o tamanho dele em heap, que é o que o caminho de
  5 GiB do envelope evita.

### 7.1 Envelope: chave por objeto

A cifra acima resolve "disco perdido não revela". Não resolve "operador hostil também não": a chave é
da instalação, então quem a tem lê todos os corpos. O **envelope** muda a unidade da chave.

O corpo gravado pela raiz do Control Plane (`StorageCatalogService.store`) agora sai **cifrado com uma
DEK própria do objeto**, embrulhada pela chave mestra (`EnvelopeBodyCrypto`):

- **DEK** — AES-256 aleatória, uma por objeto. Só existe em memória durante a leitura ou a escrita
  daquele objeto; nunca em arquivo em claro.
- **KEK** — a mestra da instalação (`REDE_SECURITY_ENCRYPTION_KEY`, a mesma do §7). Embrulha a DEK com
  AES-GCM e um IV próprio.
- **Layout**: `ENOISENV1` (9) | versão (1) | IV (12) | DEK embrulhada (60) | GCM(corpo). Tudo o que um
  processo com a mestra precisa, e nada que um processo sem ela consiga usar.
- **O digest continua sendo do texto claro**: `sha256` e `bytes` no catálogo descrevem o que o cliente
  mandou, não o que está no disco. É por esse digest que a réplica referencia a cópia.
- **`Content-Length` é do texto claro**: a rota do nó entrega o corpo decifrado, então o `OBJECT_PUT`
  de uma réplica mede exatamente o que o Control Plane prometeu.
- **Corpo em claro continua legível**: arquivo sem o cabeçalho (`ENOISENV1`) é servido como está —
  ligar a mestra não reprova as réplicas que já existem.
- **Truncamento responde 410**: o tamanho do arquivo é conferido contra "cabeçalho + corpo + tag", e a
  integridade real é a tag do GCM, conferida ao fim do fluxo.
- **Mestra errada não entrega bytes**: falha ao desembrulhar a DEK é `IOException`, nunca lixo servido
  como se fosse o objeto.

**O que ainda falta** — e é declarado aqui, não escondido:

1. **A mestra ainda vive no mesmo processo.** O ganho é de granularidade (revogar um objeto não derruba
   os outros, backup de disco sem a mestra não abre nada), não de separação de confiança. Mover a
   embrulhadora para uma **KMS / serviço de segredos** é o que separa "o operador não lê o disco" de
   "ninguém além da KMS lê".
2. **O storage node participante cifra com a chave da instalação dele** (`NodeBodyStore`, §7), não com
   uma DEK embrulhada pelo Control Plane. A DEK não desce ao nó; o que desce é o corpo em claro pelo
   canal autenticado (§1–§6). Envelope fim-a-fim até o nó exigiria embrulhar a DEK para a chave pública
   do nó — é o passo seguinte, e depende da rotação de certificado que já existe ser usada para isso.

**O que o envelope fim-a-fim até o nó exigiria, e onde ele está.** A chave pública do nó já existe
(`network_node_certificates.public_key`, DER SPKI, provada pela posse do CSR) e a privada correspondente
agora nasce no instalador (§5.1). Falta a parte criptográfica:

- o emissor deriva um segredo ECDH entre uma chave efêmera e a pública do nó, cifra a DEK com ele e manda
  o cabeçalho junto com o corpo;
- o nó deriva o mesmo segredo com a privada e abre a DEK;
- o runtime guarda o corpo com **essa** DEK, não com a chave da instalação, e o `scrub` passa a cobrir a
  chave do envelope.

Uma investigação foi feita e **não** foi entregue, porque a primeira versão estava errada: ela lia os
bytes da chave Ed25519 do nó como se fossem um ponto de curva de troca, o que produz uma chave com
formato válido e segredo diferente — o nó nunca abriria o corpo, e o erro só apareceria em execução.
O caminho correto é a conversão birracional Ed25519 → X25519 (derivada da semente com SHA-512 e
clamp) para o lado privado e o mapeamento do ponto para o público, ou um segundo par X25519 no nó,
que muda o contrato do certificado. É **decisão de arquitetura**, não de implementação, e não foi
tomada aqui. Até ela existir, o corpo continua descendo em claro pelo canal autenticado e o nó cifra
com a própria chave — que é o estado atual, declarado.

Evidência: `EnvelopeBodyCryptoTests` — 7 casos, incluindo **dois objetos do mesmo conteúdo com DEKs
diferentes**, **a DEK de um objeto não abrindo outro** e **outra mestra não decifrando**.
`StorageCatalogServiceTests` ganhou 3 casos: texto claro ausente do disco com download correto, corpo
legado em claro continuando a baixar, e truncado respondendo 410. Mutações conferidas: gravar sem cifra
derruba 2 casos; DEK da instalação em vez de por objeto derruba 1.

Evidência: `NodeBodyEncryptionTests` — 6 casos, incluindo **ler o arquivo do disco com
`Files.readAllBytes` e não achar o texto em claro** (o requisito do §10 conferido diretamente), corpo
binário de 3 MB, adulteração de um byte (falha), chave diferente (falha), corpo legado (lê) e sem
chave (claro declarado). Mutação: desligar a cifra na escrita derruba 3 casos.
