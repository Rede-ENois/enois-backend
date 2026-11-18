# 19 — Certificado mTLS do nó (brief §10)

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

## 4. O que NÃO está implementado

Esta entrega é a **identidade criptográfica**: o certificado existe, é emitido com prova de posse, é
revogado e é renovado. Ela **não** é a terminação TLS. Honestamente:

- **Não há `server.ssl` nem `clientAuth` no Spring.** O Control Plane continua atendendo HTTP na
  aplicação; a terminação TLS é do proxy da borda (`docker-prod4`), e ela ainda não exige certificado
  de cliente nas rotas `/network/`. Enquanto isso, a autorização de cada requisição de nó é a API key,
  como sempre foi — o certificado **acrescenta** uma camada que o proxy pode passar a exigir, não
  substitui a que existe.
- **Não existe rota de painel para revogar certificado.** O serviço tem `revoke` e `revokeAllForNode`,
  e a revogação do nó (`NodeKeyService.revokeAllForNode` + status `REVOKED`) já derruba a potência de
  operar pela API key; ligar a revogação do certificado na mesma transição é a próxima peça.
- **Não há rotação automática.** O nó renova quando quiser, pela rota de renovação; não há worker
  avisando "faltam 15 dias". A validade de 90 dias e o `notAfter` na resposta dão ao nó a informação
  para se programar, mas quem decide renovar é ele.
- **CRL/OCSP não existem.** A revogação é consultada no banco (`revoked_at`), o que funciona para
  decisão do Control Plane e não para um verificador offline.
- **Nada de criptografia em repouso nem envelope.** Falta implementar: chaves por objeto ou por bucket,
  re-`SecretCipher` do lado do nó, e a separação "chave fora do storage node" do brief §10. O
  `SecretCipher` (AES-GCM, `REDE_SECURITY_ENCRYPTION_KEY`) já existe e é o que protege o segredo da
  credencial S3 — é a base a reusar, não um segundo sistema.

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