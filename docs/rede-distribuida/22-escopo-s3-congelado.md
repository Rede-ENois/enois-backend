# Escopo do Énóis Storage v1 — congelado por decisão (ciclo 170)

Decisão do mantenedor registrada no encerramento do Storage v1 (ciclos 153/154): **"Storage
concluído" são os 12 itens do §25 do brief**, não a conformidade total com o S3 — que não tem fim. O
que está fora desse escopo responde `501 NotImplemented` **de propósito**: é recusa declarada, não
lacuna em aberto. Este documento é a lista fechada; mudá-la é outra decisão, e muda o teste que a
protege (ver §3).

## 1. O que fecha o critério — os 12 itens do §25

O ciclo de aceitação (`scripts/tests/storage-acceptance.sh`) percorre a sequência pela rota, com o
Control Plane e o executor de nó de verdade, contra um MariaDB 11.4 efêmero:

| §25 | critério | onde é medido |
|---|---|---|
| §25.1 | o admin gera a chave de provisionamento | `storage-acceptance.sh` |
| §25.2 | o participante se vincula só com essa chave | idem (três participantes) |
| §25.3 | grava e recupera pela API S3 (SigV4) | idem |
| §25.4 | ingressa atrás de NAT sem IP nem porta configurados | idem (modelo pull: o nó só abre saída ao CP) |
| §25.7 | o objeto é distribuído entre os nós | idem (alvo `rede.storage.replicas=3`) |
| §25.8 | a integridade é verificável (sha-256) | idem |
| §25.10 | derrubar **um** nó não compromete o objeto | idem (pausa de um de três; o objeto continua legível) |
| §25.11 | o sistema recupera a durabilidade | idem (cura repõe a cópia, nó volta `ACTIVE`) |
| §25.12 | a revogação impede novas operações | idem |
| §25.5, §25.6, §25.9, §25.13 | diretório com capacidade limitada; consumo sem saber onde está; redundância validada; gestão pelos mecanismos da rede | testes de unidade do módulo + os mesmos mecanismos de provisionamento/identidade/mTLS da Énóis Network (`docs/rede-distribuida/18-storage-gateway-s3.md`, `19-seguranca-storage-mtls-e-repouso.md`) |

## 2. O que fica fora — e responde `501 NotImplemented`

Cada linha abaixo é recusada **antes** de ler o corpo ou tocar o catálogo, com o nome do subrecurso na
mensagem. O motivo de ser decisão, e não falta:

| fora do escopo | por quê é decisão |
|---|---|
| `PUT/GET/DELETE ?tagging` (objeto e bucket) | etiqueta é indexação de terceiros; o catálogo do Storage não tem consumidor de etiqueta. Aceitar como sucesso seria afirmar um índice que ninguém mantém |
| `PUT/GET/DELETE ?acl` (objeto e bucket) | permissão é do escopo da credencial (`GET PUT LIST HEAD DELETE`), conferida na borda; ACL por objeto seria um **segundo sistema de autorização** — proibido pelo §2 do brief |
| `PUT/DELETE ?retention`, `?legal-hold`, `?object-lock` | retenção exige versionamento e relógio confiável de imutabilidade; sem versionamento é promessa vazia |
| `GET ?torrent` | BitTorrent não é transporte da rede (o modelo é pull pelo Control Plane) |
| `GET ?partNumber` fora de multipart | sem `uploadId` não há parte a devolver; responder um objeto inteiro seria mentir sobre o intervalo |
| `DELETE /{bucket}?<qualquer subrecurso>` | o `DELETE` do bucket é `DeleteBucket`; subrecurso ali seria apagar o bucket em resposta a outra operação |
| `?cors`, `?policy`, `?website`, `?notification`, `?logging`, `?replication`, `?encryption`, `?accelerate`, `?requestPayment`, `?publicAccessBlock`, `?ownershipControls`, `?analytics`, `?intelligent-tiering`, `?metrics`, `?inventory`, `?location`, `?versioning`, `?lifecycle` (no bucket), `?uploads`, `?versions` | políticas de provedor central; a replicação do Storage é a colocação da Énóis Network, e o ciclo de vida é a varredura + cura. Duas no mesmo nome seria segunda fonte de verdade |
| cabeçalhos de SSE por cliente (`x-amz-server-side-encryption*`) | o repouso é decidido pelo Control Plane (`docs/rede-distribuida/19-seguranca-storage-mtls-e-repouso.md`); aceitar o cabeçalho prometeria criptografia escolhida pelo cliente |
| `x-amz-copy-source-if-*` por data | as condições por ETag são aplicadas; as por data não têm relógio de origem confiável no catálogo |
| classes de armazenamento (`GLACIER`, `ONEZONE_IA`, …) | a rede tem uma política de colocação; classes seriam uma segunda política sem medição |

**O que NÃO está na lista acima e está implementado:** `PUT`, `GET`, `HEAD`, `DELETE` (lápide),
`LIST` v1/v2, `ListBuckets`, `DeleteBucket`, `CopyObject` e `UploadPartCopy`, multipart completo,
`Range`, `Content-Type`/`Cache-Control`/ETag/`Content-Length`, URLs assinadas (`/s3/{bucket}/presign`),
versionamento lido (`?versionId=` na leitura) e a política de ciclo de vida do varredor
(`?lifecycle`).

## 3. O que impede este documento de envelhecer

`S3GatewayTests.o_escopo_congelado_responde_501_nomeando_o_subrecurso_e_sem_efeito` percorre as
listas do §2 e exige, para cada uma, `501` com `<Code>NotImplemented</Code>` e o nome do subrecurso, e
que o objeto e o bucket fiquem intactos. A lista é literal de propósito: **implementar** um subrecurso
congelado derruba o teste e obriga a mudar a decisão (aqui e no teste) no mesmo commit. O inverso
também: tirar um item da lista sem implementá-lo deixa um comportamento sem contrato escrito.

Mutações conferidas no ciclo 170 (cada guarda removida derruba o teste): `PUT` de subrecurso que
gravaria, `GET ?torrent`/`?partNumber`, `DELETE ?acl`/`?retention`/… e `DELETE` de subrecurso no bucket.