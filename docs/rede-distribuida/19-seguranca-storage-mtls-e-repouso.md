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
| privada do **nó** | keystore do nó (gerada lá) | ninguém mais — nunca viaja |
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
  ao cliente — o tamanho conferido é o do fluxo **já decifrado**, que é o que o catálogo promete.

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

Evidência: `EnvelopeBodyCryptoTests` — 7 casos, incluindo **dois objetos do mesmo conteúdo com DEKs
diferentes**, **a DEK de um objeto não abrindo outro** e **outra mestra não decifrando**.
`StorageCatalogServiceTests` ganhou 3 casos: texto claro ausente do disco com download correto, corpo
legado em claro continuando a baixar, e truncado respondendo 410. Mutações conferidas: gravar sem cifra
derruba 2 casos; DEK da instalação em vez de por objeto derruba 1.

Evidência: `NodeBodyEncryptionTests` — 6 casos, incluindo **ler o arquivo do disco com
`Files.readAllBytes` e não achar o texto em claro** (o requisito do §10 conferido diretamente), corpo
binário de 3 MB, adulteração de um byte (falha), chave diferente (falha), corpo legado (lê) e sem
chave (claro declarado). Mutação: desligar a cifra na escrita derruba 3 casos.
