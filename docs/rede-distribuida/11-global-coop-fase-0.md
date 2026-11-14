# Énóis Global Cooperative — fase 0: diagnóstico

O brief do Global Cooperative (`enois-global-coop-brief.txt`) abre exigindo investigação antes de
qualquer implementação e manda "preservar o que já funciona, reutilizar os mecanismos existentes,
não criar sistemas paralelos". Este documento é essa investigação. Descreve o código como ele está
no commit em que foi escrito — inclusive o que **não** existe e o brief supõe que exista.

## 1. O que já existe e serve ao cooperativismo global

| requisito do brief | o que existe hoje | reuso |
|---|---|---|
| §1 master como autoridade dos dados | o Control Plane é o único que escreve no MariaDB; nó auxiliar nunca escreve no banco principal (regra fixa em `docs/rede-distribuida/README.md` §2) | direto, sem mudança |
| §2 master como gateway distribuidor | `NetworkTaskService` já enfileira, escolhe nó por saúde/origem/região/folga/jitter e entrega por pull | direto |
| §3 malha cooperativa entre réplicas | sinais entre instâncias em `network_instance_signals` (V307): cada instância publica o que mudou e as outras leem; o que viaja é **id**, nunca conteúdo | direto, é a malha que existe |
| §5 operação durante indisponibilidade | modos `NORMAL`/`HIGH_LOAD` medidos e `DEGRADED`/`EMERGENCY`/`RECOVERY` declarados (ADR-009); drenagem sem perder tarefa em voo | direto |
| §6 rede como camada única | enroll, API key por nó, config assinada, heartbeat — o Storage vai usar os mesmos | direto |
| §7 provisionamento sem IP/hostname | token de uso único; o nó só faz chamadas de saída (pull), então funciona atrás de NAT | direto |
| §9 múltiplos domínios | região declarada por nó + estado de região fora de operação (ADR-015) + cobertura por região (ADR-016) | parcial: região existe, "domínio" com autoridade própria não |
| §12 modos operacionais | ADR-009 | direto |
| §13 governança | `AuditTrail` com ator, alvo e motivo; `AdminGuard` | direto |
| §3.7 / §4.3 divergência e reconciliação | `ContentReconcileService` / `ContentReconcileWorker`: o nó declara o que tem, o Control Plane confere contra a origem e grava `CONTENT_STALE`/`CONTENT_GONE` | direto para o **conteúdo**; não para tabelas arbitrárias |
| §3 change feed | `network_content_changes` (V283): cada mudança de conteúdo ganha versão e hash na **mesma transação** em que o corpo muda; o nó lê por cursor (`seq`) e recebe ids + versão + hash | é o alicerce real da sincronização entre nós |

## 2. O ponto crítico do brief e o que realmente existe

O §4 é declarado "prioridade crítica": **uma única conexão dedicada de captura de alterações do
MariaDB**, preferencialmente por **binlog** (CDC), alimentando a distribuição pela rede, com
posição de origem persistente, checkpoints duráveis, backpressure e retomada da última posição
confirmada.

**Isso não existe.** Medido no repositório:

- nenhuma classe, configuração ou dependência de CDC/binlog (a única menção a binlog é um comentário
  em `application.properties` linha 18, sobre `binlog_format ROW` exigido para o dado ser legível —
  não há leitor de binlog no código);
- não há tabela de posição de origem, checkpoint de réplica nem consumidor central de captura;
- o change feed do §3 **não é CDC**: ele não observa o banco, ele é escrito pela própria aplicação
  no momento da mudança (`ContentSyncService.publish`). Só cobre as tabelas de conteúdo que
  chamam esse serviço; uma alteração feita por SQL direto, migração ou outro módulo **não entra** no
  feed.

Consequência: o §4 é trabalho novo, não reuso. E ele tem uma consequência de operação que precisa
estar dita antes de alguém ligar isso em produção: ler binlog exige `log_bin` ligado e
`binlog_format=ROW` no MariaDB, e o usuário da captura precisa de `REPLICATION SLAVE`/`REPLICATION
CLIENT` — permissão que o usuário da aplicação **não deve** ter. Isso é decisão de infraestrutura
do mantenedor, não efeito colateral de código.

## 3. O que o brief pressupõe e não existe no código

| brief § | item | estado real |
|---|---|---|
| §4.1 | captura única por binlog, posição persistente | **inexistente** (ver §2) |
| §4.3 | aplicação idempotente de eventos de tabela nas réplicas com checkpoint transacional | **inexistente**. O que há é o cursor do change feed de conteúdo, que avança só depois de o corpo estar em disco no nó |
| §8 | observabilidade distribuída "de todos os nós" | **parcial**: heartbeat e saúde por nó existem (`degraded`, latência EWMA, sequência de falhas, quarentena), e o painel agrega; não há coleta de métricas de host nem do runtime além do que o heartbeat carrega |
| §9 | múltiplos domínios com autoridade própria | **inexistente**. Existe região, que é ordenação de afinidade e estado fora de operação. Um segundo domínio com autoridade necessita de termo e quórum, que a F6 (ADR-013) já esboça para uma autoridade única |
| §11 | blockchain e consenso | **inexistente** e o brief não exige implementar agora; a autoridade com termo (ADR-013) é o que existe de mais próximo |

## 4. Ordem recomendada, por dependência real (não por número do brief)

1. **Observabilidade (§8)** — o que falta depende de dado que o nó já manda no heartbeat; é o menor
   passo e destrava os demais diagnósticos.
2. **CDC (§4)** — decisão de infraestrutura primeiro (ligar binlog, usuário de replicação), código
   depois. Sem essa decisão, qualquer implementação seria um segundo mecanismo sem credencial.
3. **Domínios (§9)** — só depois que o CDC existir, porque o domínio secundário precisa receber
   eventos de um domínio primário.

O Storage (fase 1) **não** depende de nada deste documento; os dois seguem em paralelo sem colidir,
porque o Storage acrescenta perfil e tipo de tarefa e o Cooperative mexe em captura e observação.