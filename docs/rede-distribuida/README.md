# Énóis Distributed Network — plano e documentação

Rede cooperativa de infraestrutura: pessoas e organizações **expressamente autorizadas** disponibilizam
servidores, VPS ou máquinas físicas para executar instâncias auxiliares do Énóis (processamento,
atendimento, distribuição de conteúdo, resiliência). Não é replicar o backend em várias VPS — é uma
arquitetura distribuída com Control Plane, Data Plane e Node Runtime.

## Estado

**Fase 0 — em andamento.** Nada vai para `qa` até o plano estar completo (fases 0–6 entregues e
testadas). O trabalho vive na branch de longa duração `feat/rede-distribuida`, criada de `origin/qa`.

## Documentos

| # | Documento | O que responde |
|---|---|---|
| 00 | [Diagnóstico](00-diagnostico.md) | O que o Énóis é hoje, com evidência de código: serviços, banco, auth, filas, deploy, observabilidade, dependências. |
| 01 | [Arquitetura](01-arquitetura.md) | Control Plane, Data Plane, Node Runtime, tipos de nó, diagramas. |
| 02 | [Modelo de ameaças](02-modelo-de-ameacas.md) | Em quem não confiamos, o que cada ataque consegue e qual controle responde. |
| 03 | [Contratos entre nós](03-contratos.md) | Endpoints, envelopes, versionamento, erros, idempotência. |
| 04 | [Identidade e autorização](04-identidade-e-autorizacao.md) | Identidade criptográfica do nó, **API key por nó gerada na tela de admin**, permissões por perfil. |
| 05 | [Sincronização e consistência](05-sincronizacao.md) | O que replica, o que não replica, e por quê. |
| 06 | [Contingência](06-contingencia.md) | Modos operacionais, contingência local e distribuída, anti split-brain. |
| 07 | [Plano incremental](07-plano-incremental.md) | Fases 1–6, cada uma com entrega funcional e critério de aceite. |
| 08 | [Impacto na infraestrutura atual](08-impacto.md) | O que muda no que já está no ar, e o que não muda. |
| 09 | [Instalação do Node Runtime](09-instalacao.md) | Como um participante instala, registra e diagnostica um nó com `deploy/node-runtime/`. |
| 10 | [Énóis Storage — fase 0](10-storage-fase-0.md) | Diagnóstico do que a rede já oferece ao Storage, o que precisa de extensão e o que o brief pressupõe e **não existe** hoje (mTLS, reverse tunnel). |
| 12 | [Énóis Storage — fase 1](12-storage-fase-1.md) | Catálogo (V319) e colocação (V320), rota de corpo por API key de nó, medida antes do aceite, e o que **não** foi entregue (gateway S3, multipart, URLs assinadas). |
| 11 | [Énóis Global Cooperative — fase 0](11-global-coop-fase-0.md) | O que o cooperativismo global reusa da rede, e o que o brief pressupõe e **não existe** (captura por binlog, checkpoints de réplica, observabilidade de host). |
| 13 | [Énóis Storage — fase 3](13-storage-fase-3-replicacao.md) | Réplica em nó de armazenamento, confirmação por digest, leitura por digest e varredura de durabilidade (V323). |
| 14 | [Global Cooperative — captura de alterações](14-coop-captura-de-alteracoes.md) | Prontidão do banco para captura por binlog, medida em vez de declarada. |
| 15 | [Inscrição para servir um nó](15-inscricao-de-no.md) | Formulário público com RG/CPF/contrato, decisão administrativa, link de uso único e chave exibida uma vez; responsável no painel. |
| 17 | [Énóis Storage — fase 4](17-storage-fase-4-dominios-e-equilibrio.md) | Região como domínio de falha na escolha de réplica, relatório de equilíbrio, tráfego e disponibilidade do nó (V345), limites de capacidade/banda ajustados pelo próprio participante, seis tipos de storage node com preferência de colocação e banda por tipo (V348, formato 7), IP de origem e mudança de endereço (V350); entrega ao cliente com `Cache-Control` por tipo, `ETag`, `Last-Modified` e `Range`/`206`/`416` (`GET /network/storage/objects/{id}/content`); versionamento por bucket, retenção, expiração automática e restauração de versão (V352); rebalanceamento progressivo com designar/confirmar/concluir e a cópia antiga só removida após a confirmação do destino, recusa de mover a última cópia `OK`, teto de banda por transferência e cancelamento sem tocar a origem (V354); o que **não** existe (URLs assinadas, invalidação de cache, escolha automática de quem mover, prioridade por importância, pausa/retomada no meio da transferência, erasure coding, série histórica de disponibilidade, tratamento diferenciado para nó doméstico, sincronização de estado de nó desconectado). |
| 18 | [Énóis Storage — gateway S3](18-storage-gateway-s3.md) | API compatível com S3 em `/s3`: Autenticação AWS SigV4 conferida contra os vetores oficiais (`awslabs/aws-c-auth`), credencial de cliente separada da chave de nó com escopo por bucket e operação e segredo cifrado pelo `SecretCipher` (V357); PutObject, GetObject (Range/206/416, 304), HeadObject, DeleteObject (lápide), ListObjectsV2/v1, ListBuckets e URLs pré-assinadas de GET/PUT (assinatura na query, validade até 7 dias, emitida pela sessão do dono); Multipart Upload com partes fora de ordem, mínimo de 5 MiB e conclusão atômica pelo mesmo caminho do PUT (V361); e Access Keys pelo painel — criar, listar sem segredo e revogar com o dono no `WHERE`; e o que **não** existe (CopyObject, ACL, tagging, versionId, bucket policy, STS, quota por credencial). |
| 19 | [Segurança do Storage: mTLS e cifra em repouso](19-seguranca-storage-mtls-e-repouso.md) | Brief §10: certificado X.509 Ed25519 individual por nó, emitido pelo Control Plane a partir de um CSR com autoassinatura conferida (prova de posse da chave), validade de 90 dias, renovação com a API key e revogação consultada no banco (V358); chave privada da CA fora do banco, por configuração; criptografia em repouso do corpo do nó (AES-256-GCM em fluxo, `EncryptedBodyFile`); envelope com chave de dados por objeto na raiz do Control Plane (`EnvelopeBodyCrypto`, DEK embrulhada pela mestra); e o que **não** existe (terminação TLS exigindo certificado de cliente, rota de painel de revogação, rotação automática, CRL/OCSP, mestra em KMS externa, envelope fim-a-fim até o storage node). |
| 21 | [Nó de reserva: cópia do banco e promoção com backup](21-no-reserva-copia-do-banco.md) | Brief cooperativo §11/§12: cópia do banco do Control Plane nos nós de reserva (V360), iniciada por cópia sob demanda, refresh a cada 6 h e sincronização completa às 03:00 (desligados por padrão), com o nó baixando o dump pela própria API key, conferindo o digest do dump inteiro, retomando por `offset` e confirmando por `POST /network/standby/complete`; promoção `promote-standby` que recusa subir o termo sem cópia `READY` e audita o nó; a **atualização automática do runtime** do nó (`bootstrap.sh --auto-update on`, cron do host — doc 09 §6.1), e o que **não** existe (cópia + binlog, runtime que consome o contrato, validação do dump pelo nó, reserva declarada no painel, cifra da cópia em repouso no nó, retenção de cópias). |

Decisões arquiteturais ficam em [`docs/adr/`](../adr/).

## Regras que valem para toda a implementação

1. **Nada de arquitetura paralela.** Tudo se apoia no que já existe: outbox transacional, idempotência,
   auditoria, `AdminGuard`, cache Caffeine, S3, actuator/Prometheus.
2. **A infraestrutura principal continua sendo a autoridade transacional.** Nó auxiliar não escreve no
   banco principal, nunca.
3. **Nenhum nó entra sem autorização humana expressa.** A API key de cada nó é gerada por um admin, na
   tela de administração, e exibida uma única vez.
4. **Conteúdo de usuário (HTML, CSS, KofScript) nunca roda no ambiente de um nó.** Continua nos
   sandboxes próprios, como hoje.
5. **Sem dependência pesada sem justificativa.** Sem consenso distribuído, criptografia própria ou
   protocolo proprietário sem necessidade comprovada.
