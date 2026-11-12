# 07 — Plano incremental

Cada fase entrega algo **funcional, testável e utilizável**. Nada de meio-corte. A ordem respeita
dependência real: não existe scheduler antes de existir nó, não existe contingência antes de existir
modo operacional.

Tudo vive na branch de longa duração `feat/rede-distribuida`, criada de `origin/qa`. **Nada vai para
`qa` até o plano estar completo.**

## Fase 1 — Fundação

**Entrega:** o Énóis sabe o que é um nó, autentica um nó e revoga um nó.

| item | critério de aceite |
|---|---|
| tabelas `network_nodes`, `network_node_keys`, `network_provision_tokens` | migração nova, aditiva, idempotente, aplicada duas vezes em MariaDB 11.4 |
| módulo `network/` hexagonal | mesmo formato de `feed`, `media`, `outbox` |
| API key por nó gerada na tela de admin | `POST /admin/network/nodes/{id}/keys`; chave aparece uma vez; só hash no banco; teste cobre emissão, uso, revogação e rotação |
| token de provisionamento de uso único | segundo uso recebe 409; expirado recebe 400 |
| enroll | nó passa a existir com estado `PENDING_APPROVAL` |
| aprovação manual | admin aprova; estado vira `ACTIVE`; auditoria registrada |
| heartbeat | `POST /network/nodes/heartbeat` atualiza `last_seen_at` e capacidade observada |
| revogação | próxima requisição do nó recebe 401/403; nada mais executa |
| painel mínimo | lista de nós, estado, último heartbeat, botões de aprovar/suspender/revogar/gerar chave |
| primeiro `NODE_WORKER` | conecta, autentica, faz heartbeat, recebe tarefa trivial e devolve resultado |

**Prova:** teste de integração que sobe um nó falso (cliente HTTP), faz enroll, aprova, heartbeat,
recebe tarefa e devolve resultado; e outro que revoga no meio e exige o corte. Migração validada
duas vezes com dado antigo preservado.

**Não entra:** scheduler, fila distribuída, TLS real, painel completo.

## Fase 2 — Processamento distribuído

**Entrega:** tarefas rodam fora da infraestrutura principal, sem duplicar efeito.

| item | critério de aceite |
|---|---|
| scheduler como handler do outbox | reusa claim, backoff, DLQ e métrica; nenhum sistema paralelo |
| seleção de nó | usa capacidade observada, latência, saúde, perfis e `dataClass` |
| `taskId` estável + `attempt` | mesma tarefa redistribuída não duplica efeito no destino |
| retry com backoff exponencial + jitter | teste com falha programada mostra o intervalo crescente |
| DLQ | tarefa que esgota tentativas fica visível no painel com o erro |
| limites de concorrência por nó | nó não recebe mais do que declarou/observou — **entregue** (a rodada desconta o que já designou; antes, doze tarefas iam todas para um nó de quatro núcleos, porque cada escolha relia o heartbeat) |
| cancelamento e timeout | tarefa vencida não executa; resultado tardio é descartado |
| detecção de tarefa abandonada | claim expirado volta para a fila |
| controle de sobrecarga | sobrecarga vira `HIGH_LOAD`, medido |

**Prova:** teste que derruba o nó no meio da tarefa e mostra a redistribuição sem duplicar; contagem
de execução igual a 1 por efeito.

## Fase 3 — Infraestrutura distribuída

**Entrega:** tráfego e mídia podem ser servidos por nós.

| item | critério de aceite |
|---|---|
| **broadcast entre instâncias** (pré-requisito) | `MessageStreamHub` funciona com duas instâncias; cache invalida em todas |
| `NODE_EDGE` | entrega conteúdo público com versão; nunca decide autorização de usuário |
| `NODE_MEDIA` | transcode executa no nó; resultado verificado por hash e tamanho |
| cache distribuído | só conteúdo público; mutável com TTL curto e invalidação por evento — **entregue** (feed de mudanças V283: `GET /network/content/changes` com cursor `seq` monotônico, linha gravada na mesma transação da versão, poda em lote, cursor atrás da janela recebe `reset=true`) |
| balanceamento | nó com latência/erro perde prioridade; remoção temporária — **entregue** (saúde medida em `network_nodes`, filtro + ordem no scheduler, janela que expira sozinha, `NETWORK_NODE_QUARANTINED`/`RELEASED` na auditoria) |
| réplicas de leitura | projeção com `sourceVersion`; allowlist explícita; nunca tabela crua — **entregue** (`GET /network/replica/{kind}` enumera por chave `(criado em, id)`, devolve o @ do perfil e o id da comunidade; o corpo vem do mesmo `/network/content/{kind}/{id}`; allowlist `PROFILE`+`COMMUNITY` versionada no código; filtro da vitrine aplicado em lote; `NODE_READ_REPLICA` e `NODE_LOCAL` leem, `NODE_EDGE` não enumera) |
| reconciliação | divergência vira alerta `SYNC_DIVERGENCE` — **entregue**, inclusive sem depender de o nó pedir (varredura periódica) |

**Prova:** transcode real em nó; comparação do resultado com o local; teste de balanceamento
removendo nó doente; teste de broadcast com duas instâncias.

## Fase 4 — Contingência local

**Entrega:** a infraestrutura do Énóis absorve pico com `NODE_LOCAL`.

| item | critério de aceite |
|---|---|
| `NODE_LOCAL` | perfil próprio, prioridade de escalonamento — **entregue**: o perfil já aceitava todo tipo e toda classe de dado (permissão existia); o que faltava era o ordenador, que ignorava a origem. Agora `choose()` prefere o nó local **entre os saudáveis**, depois do filtro de degradação e antes da folga: aplicada depois da folga, a prioridade só valeria quando o local já fosse o mais vago, ou seja, nunca no caso que importa (todos com vaga). O local continua sujeito a todos os filtros — vivo, não removido, com vaga, não saturado — e não ganha tipo de tarefa nenhum |
| modos operacionais | `NORMAL`/`HIGH_LOAD`/`DEGRADED`/`EMERGENCY`/`RECOVERY` com critérios medidos — **entregue**: `GET`/`POST /admin/network/mode`, motivo obrigatório, auditoria com ator/modo anterior/motivo, `RECOVERY` termina por número, despacho obedece ao modo e o heartbeat devolve `mode`/`acceptTasks` |
| escalonamento automático | transição registrada em auditoria com o número que a motivou — **entregue** (já existia para `NORMAL`/`HIGH_LOAD`; a saída de `RECOVERY` usa o mesmo número) |
| failover | função preparada assume quando a principal cai — **fase 6** para a parte distribuída: `TaskType` já declara que `NODE_EMERGENCY` não recebe tarefa até lá, porque preparar a função é diferente de autorizá-la. A parte local (o `NODE_LOCAL` absorver o pico) está entregue acima |
| recuperação gradual | drenagem sem derrubar o que está em andamento — **entregue**: suspender/quarentenar/revogar devolve à fila, na hora, o que estava designado ao nó (`findOnNonOperatingNodes` junta com `network_nodes` e pega `status <> 'ACTIVE'`). Antes, a tarefa esperava o claim vencer — dois minutos no padrão — por um nó que já não autentica. A varredura do scheduler drena **antes** de designar; o resultado do nó drenado, se chegar, é descartado pela regra de tentativa/status que já existia |

**Prova:** simulação de carga que entra em `HIGH_LOAD`, manda tarefa para o local, normaliza e drena;
o teste exige a ordem das transições.

## Fase 5 — Rede cooperativa

**Entrega:** terceiros autorizados entram, instalam e operam sozinhos.

| item | critério de aceite |
|---|---|
| onboarding externo | fluxo completo do token de provisionamento até `ACTIVE` — **parcial**: `provision-token` → `enroll` → aprovação → `GET /network/nodes/me/config` assinado (Ed25519, com pino da chave pública e `configVersion` do ciclo de vida) estão entregues; o instalador está em `deploy/node-runtime/` (linha seguinte) e falta a solicitação de ingresso pela interface do participante |
| instalador | Docker Compose + script Linux; health check; atualização; rollback; backup de config; diagnóstico — **parcial**: enrol de três passos (`deploy/node-runtime/`: enroll → `--set-key` → `--start`), compose endurecido (`read_only`, `cap_drop: ALL`, `no-new-privileges`, limites, sem porta/socket), diagnóstico `node-doctor.sh` (PASS/WARN/FAIL, 14 cenários em `scripts/tests/node-runtime-test.sh`); falta executores por perfil, atualização/rollback com assinatura OCI e backup de identidade |
| imagens OCI assinadas | assinatura verificada no pull |
| gestão de permissões | participante define limites; a rede respeita — **parcial**: CPU (concorrência) e memória declarada respeitadas; disco e banda dependem de não haver tarefa que os consuma hoje |
| painel "Énóis Network" | todos os agregados e a página por nó com as ações administrativas |
| interface do participante | solicitar, instalar, consultar estado e contribuição, desconectar |
| distribuição geográfica | região declarada e considerada no escalonamento — **parcial**: região no enroll (duas formas aceitas, como a doc documenta) e `region` opcional na tarefa (V285) entram como **afinidade de ordenação** (degradação → origem → região → folga → jitter, ADR-012), nunca filtro; falta recuperação regional da fase 6 |

**Prova:** instalação limpa em máquina nova, do zero, sem conhecer a arquitetura interna.

## Fase 6 — Resiliência avançada

**Entrega:** a rede sobrevive à perda de uma região.

| item | critério de aceite |
|---|---|
| Control Plane redundante | promoção controlada com term; sem quórum, ninguém promove |
| recuperação regional | região afetada sai; nós de outras regiões atendem o que podem |
| replicação geográfica | só o que pode replicar; divergência detectada |
| anti split-brain | dois controladores → nó em quarentena |
| testes de desastre | cada cenário da lista de testes obrigatórios |
| chaos engineering | falha injetada com resultado previsível |

## Sequência de branches

```
origin/qa
└── feat/rede-distribuida            ← longa duração, commits por fase
    ├── feat/rede-distribuida-f1     (opcional, merges na longa)
    ├── feat/rede-distribuida-f2
    └── ...
```

Quando o conjunto estiver completo: PR (ou PRs empilhados por fase) para `qa`, com a declaração do
que mudou e o que não mudou. Merge é decisão humana.

## Critério de "pronto" do plano inteiro

1. Todos os itens das fases 1–6 com teste passando.
2. Suíte completa do backend verde.
3. Migrações validadas duas vezes em MariaDB 11.4 com dado antigo preservado.
4. Nenhum comportamento existente alterado sem declaração explícita.
5. ADRs registradas para cada decisão arquitetural relevante.
6. Instalação do runtime reproduzível em máquina limpa.
