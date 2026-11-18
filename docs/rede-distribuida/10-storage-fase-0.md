# Énóis Storage — fase 0: diagnóstico e reuso da Énóis Network

O brief do Storage (`enois-storage-brief.txt`, §2 e §24) exige que o Storage seja uma **capacidade
especializada da Énóis Network**, nunca um segundo sistema. A fase 0 é o diagnóstico: o que a rede
já tem, o que o Storage reusa como está, o que precisa ser estendido e o que **não existe** hoje e
o brief pressupõe que exista. Documento de diagnóstico — descreve o código no commit em que foi
escrito, não uma intenção.

## 1. O que já existe e o Storage reusa sem inventar nada

| mecanismo | onde está | o que o Storage usa |
|---|---|---|
| **identidade e provisionamento** | `network` — `POST /network/nodes/enroll` com token de provisionamento de uso único (`ProvisionToken`, hash sha256, expira em 30 min, uso único consumido antes de criar); `NodeKeyService` valida `X-Node-Id` + `X-Node-Key` em tempo constante | o Storage Node **é** um nó da rede. O mesmo enroll, o mesmo token, a mesma chave. Nenhuma tabela de identidade de storage |
| **autorização por perfil** | `NodeProfile` + `TaskType.requiredProfile`; o admin escolhe os perfis na aprovação (`POST /admin/network/nodes/{id}/profiles`) | um novo perfil (`NODE_STORAGE`) e um novo tipo de tarefa. Um nó com processamento e disco declara os dois perfis no mesmo enroll |
| **configuração assinada** | `GET /network/nodes/me/config` — Ed25519 sobre linha canônica, `configVersion` sobe a cada mudança de permissão, pino TOFU no nó (`pinning-key`) | os limites de disco que o Storage respeita chegam pelo mesmo documento assinado (`limits.diskMb`), não por parâmetro do Storage |
| **heartbeat e saúde** | `POST /network/nodes/heartbeat` — devolve `mode` e `acceptTasks`; saúde medida (`degraded`/quarantine) separada do que o nó declara | o nó que hospeda objetos continua reportando pelo mesmo heartbeat; capacidade usada de disco entra no `observed` que já existe |
| **modos operacionais** | `NORMAL`/`HIGH_LOAD` medidos; `DEGRADED`/`EMERGENCY`/`RECOVERY` declarados pelo admin (ADR-009) | em `EMERGENCY` o Storage para de aceitar escrita nova pelo mesmo `acceptTasks=false` — sem interruptor próprio |
| **escalonamento** | `NetworkTaskService`: pull (`GET /network/tasks/next`), região como afinidade (ADR-012), alvo como filtro (§3b), folga, jitter, drenagem de nó fora de `ACTIVE` | entrada e saída de dados no nó é tarefa: mesma fila, mesma drenagem, mesmos limites de concorrência |
| **aceite por evidência** | `MediaResultVerifier` — porta por tipo de tarefa: o nó declara `output` (`objectKey`, `bytes`, `sha256`) e o CP **mede** o artefato antes de aceitar | é o padrão que o Storage copia: "gravou" só vale quando o CP conferiu bytes e hash. Upload não retornando erro não é durabilidade |
| **auditoria** | `AuditTrail` — toda ação de admin e de nó com ator, alvo e motivo escapado | cada colocação, replicação, descarte e recuperação de objeto é evento auditado, no mesmo trilho |
| **migrações** | Flyway `V305–V318` (rede); próximo livre `V319` | o catálogo do Storage nasce em `V319+`, aditivo, mesmo padrão (charset/collation explícitos) |
| **runtime do participante** | `deploy/node-runtime/` — `bootstrap.sh`, `install.sh`, `node-runtime.sh` (loop em bash), imagem local por commit | o Storage Node Runtime é **extensão do mesmo script**, não um agente novo: mesmo `$NODE_HOME`, mesma chave, mesmo loop |

## 2. O que precisa ser estendido (mudança pequena e localizada)

1. **`NodeProfile.NODE_STORAGE` e `TaskType.OBJECT_PUT` / `OBJECT_GET`** — hoje não há perfil nem
   tipo de tarefa de armazenamento. A regra "capacidade computacional não dá permissão" vale igual:
   um nó com 10 TB não recebe tarefa de storage sem o perfil.
2. **Medida de disco** — `observed` do heartbeat hoje leva CPU, memória e tarefas em execução;
   precisa levar o disco efetivamente disponível no diretório autorizado. `diskMb` declarado já
   existe e **já é um teto imposto** no runtime (R98: borda evicta, réplica para com `diskFull`).
3. **Transferência binária autenticada** — o contrato da tarefa leva JSON, não corpo binário. A
   fase 1 precisa de uma rota de corpo (upload/download) autenticada pela mesma API key do nó, que
   **não** reutilize o `MediaObjectStore` do app (aquele fala com o S3 do provedor, é o lado do
   cliente do storage, não do servidor).

## 3. O que o brief pressupõe e NÃO existe no código

Estes pontos são requisitos de fase 2 do brief. Não são reuso — são trabalho novo, e estão
registrados aqui para nenhuma fase seguinte fingir que herdou do que já havia:

| brief § | item | estado real no código |
|---|---|---|
| §2, §4 | **mTLS por certificado individual, CSR no enroll** | **parcial (V358)**. O certificado Ed25519 individual é emitido pelo Control Plane a partir de um CSR com autoassinatura conferida, e é renovado/revogado (`docs/rede-distribuida/19-certificado-mtls-do-no.md`). O que falta: a terminação TLS exigir o certificado de cliente, rota de painel de revogação, rotação automática e CRL. A autoridade de cada requisição de nó continua sendo a API key |
| §2, §5 | **reverse tunnel, conexão de saída persistente do nó** | **inexistente**. O modelo é pull curto (`GET /network/tasks/next` a cada ciclo). Não há canal aberto do nó para o CP. Isso atende NAT (o nó só faz saída), mas não serve para o CP **empurrar** um download para um nó atrás de NAT — a fase 1 deve manter o modelo pull |
| §2 | **service discovery de nós** | parcial: o catálogo de nós está no banco do CP (`network_nodes`) e é o que o scheduler usa. Não há registro de endereço acessível por outro nó — e não deve haver antes da fase 2, porque o brief proíbe cadastro de IP/hostname |
| §3.3 | **gateway S3 (`s3.enois.ia.br`)** | inexistente. O que existe é o `media` do app, que é **cliente** de um S3 de terceiro (`S3MediaObjectStore`) e um proxy de leitura (`MediaStorageProxyController`). Nada serve S3 para fora |
| §9 | **redundância de catálogo e metadados** | inexistente. Metadado da rede vive só no MariaDB do CP |

## 4. Decisão arquitetural desta fase 0

O Storage **não cria** identidade, provisionamento, heartbeat, config assinada nem malha própria —
porque os cinco já existem e funcionam. O que se acrescenta é um perfil, um tipo de tarefa, uma
medida de disco no heartbeat e uma rota de corpo binário autenticada por API key de nó, tudo dentro
do módulo `network`. mTLS e reverse tunnel **não são herdados** (não existem) e ficam explícitos como
trabalho da fase 2, com a consequência prática de que a fase 1 transporta binário por HTTPS com a
API key — decisão registrada no mesmo lugar em que o brief exigiria o contrário.