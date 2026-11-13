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
| **broadcast entre instâncias** (pré-requisito) | `MessageStreamHub` funciona com duas instâncias; cache invalida em todas — **entregue** (tabela de sinais com polling por instância, [ADR-007](../adr/ADR-007-broadcast-entre-instancias-por-tabela.md), V278): a escrita atendida numa instância limpa o cache dela e grava o sinal na mesma transação do efeito; cada instância lê `where seq > posição and origin_instance <> eu`, entrega no próprio estado (limpar cache, avisar o stream) e anuncia presença em `network_instance_readers`; sinal perdido é coberto pelo TTL e pelo reconnect. **Prova em duas camadas**: `InstanceBroadcastTests` exercita o consumidor desta instância (stream recebe o aviso da outra, cache limpa, chave única evita o resto, o próprio sinal não se reentrega); `TwoInstancesBroadcastTests` sobe um <b>segundo</b> emissor + poller + cache de verdade sobre a mesma tabela e fecha o lado que a simulação não cobria — a emissão de produção (`CacheInvalidator`) limpa em A, o sinal sai com o id de A, o poller de B limpa em B; instância não entrega o próprio sinal; cada poller registra a própria posição |
| `NODE_EDGE` | entrega conteúdo público com versão; nunca decide autorização de usuário — **parcial**: o cache está entregue no nó **e alimentado** (`node-runtime.sh` executa `EDGE_CACHE` consumindo `GET /network/content/changes`, baixa só o que mudou pelo mesmo `/network/content/{kind}/{id}`, guarda corpo+versão em `$NODE_HOME/edge/`, confirma `/synced`, trata `reset=true` como recarga do zero e 404 como remoção; o produtor existe em dois caminhos — `POST /admin/network/nodes/{id}/edge-cache` para o pedido imediato e `EdgeCacheWorker` a cada 2 min para todo nó de borda sem pedido aberto — e a tarefa nasce **com alvo**, `network_tasks.target_node_id` V287, porque o cursor é do disco daquele nó: alvo é filtro no despacho, então nó alvo fora dos candidatos deixa a tarefa na fila em vez de a mandar para o nó errado); **entrega entregue e verificada**: `EDGE_DELIVER` é a prova de que aquele nó entrega aquele conteúdo **agora** — não um ouvinte HTTP, porque o nó não expõe porta e o modelo é pull. O nó revalida com `?version=`, baixa o corpo quando a origem está à frente, grava **antes** de confirmar, reconfirma `/synced` e responde `{"kind","contentId","version","contentHash","deliverable","source"}`; versão positiva sem confirmação prévia é recusada, e versão/hash diferentes do snapshot do próprio nó viram `EDGE_DELIVER_MISMATCH` |
| `NODE_MEDIA` | transcode executa no nó; resultado verificado por hash e tamanho — **entregue**: o Control Plane já fixava a origem (tamanho/hash) e media o resultado recebido; o nó agora executa de fato a receita do `MediaTranscodeSpec` e devolve o arquivo (ver linha do instalador) |
| cache distribuído | só conteúdo público; mutável com TTL curto e invalidação por evento — **entregue** (feed de mudanças V283: `GET /network/content/changes` com cursor `seq` monotônico, linha gravada na mesma transação da versão, poda em lote, cursor atrás da janela recebe `reset=true`) |
| balanceamento | nó com latência/erro perde prioridade; remoção temporária — **entregue** (saúde medida em `network_nodes`, filtro + ordem no scheduler, janela que expira sozinha, `NETWORK_NODE_QUARANTINED`/`RELEASED` na auditoria) |
| réplicas de leitura | projeção com `sourceVersion`; allowlist explícita; nunca tabela crua — **entregue** (`GET /network/replica/{kind}` enumera por chave `(criado em, id)`, devolve o @ do perfil e o id da comunidade; o corpo vem do mesmo `/network/content/{kind}/{id}`; allowlist `PROFILE`+`COMMUNITY` versionada no código; filtro da vitrine aplicado em lote; `NODE_READ_REPLICA` e `NODE_LOCAL` leem, `NODE_EDGE` não enumera; **o lado do nó também está entregue**: `node-runtime.sh` executa `READ_QUERY` percorrendo o catálogo por cursor, lendo só o que mudou com `?version=`, guardando corpo+versão em `$NODE_HOME/replica/` e confirmando `/synced`; 404 na origem descarta o guardado em vez de servir o que a vitrine esconde) |
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
| onboarding externo | fluxo completo do token de provisionamento até `ACTIVE` — **parcial**: `POST /network/participant/join` (o participante solicita pela própria interface; token de uso único com o dono forçado à sessão, perfis negociados na aprovação, limite de 3 tokens vivos por conta, auditado `NODE_JOIN_REQUESTED`) → `enroll` → aprovação → `GET /network/nodes/me/config` assinado (Ed25519) estão entregues; o instalador está em `deploy/node-runtime/` (linha seguinte) |
| instalador | Docker Compose + script Linux; health check; atualização; rollback; backup de config; diagnóstico — **parcial**: enrol de três passos (`deploy/node-runtime/`: enroll → `--set-key` → `--start`), compose endurecido (`read_only`, `cap_drop: ALL`, `no-new-privileges`, limites, sem porta/socket), diagnóstico `node-doctor.sh` (PASS/WARN/FAIL, cenários em `scripts/tests/node-runtime-test.sh`), **backup da identidade entregue** (`install.sh --backup` arquiva node.json + api-key + pinning-key num tar.gz 600 com o nodeId no nome; `--restore` devolve em máquina nova, recusa pisa identidade existente e o nó restaurado autentica com a mesma identidade), **loop de execução entregue** (`node-runtime.sh`: heartbeat → drena sem pedir em modo sem despacho → config assinada conferida contra o pino → poll → executa só `allowedTasks`, recusa o resto com `TASK_NOT_ALLOWED` → resultado com a tentativa vigente), **pino TOFU entregue** (o pino da config assinada é gravado no enroll quando a config já tem `publicKey`, ou no primeiro `--start` com credencial provada — e nunca re-pinado depois, nem com o CP devolvendo chave rotacionada; sem pino o executor recusa toda config com instrução, e o doctor aponta o remédio), **executor de mídia entregue** (`node-runtime.sh` executa `MEDIA_TRANSCODE` de verdade: baixa a origem designada, roda a receita do `MediaTranscodeSpec` — mesmos filtros/encoders/threads/formatos, `-protocol_whitelist file`, faststart no mp4 — envia o resultado e declara as medidas que o Control Plane devolveu ao medir o arquivo; falha com código próprio: `TASK_EXECUTOR_MISSING`, `TASK_PAYLOAD_INVALID`, `TRANSCODE_FAILED`; o rascunho vai para o volume do nó, não para o tmpfs de 64 MB de /tmp; 68 asserções em `scripts/tests/node-executor-test.sh`), **executor de cache de borda entregue** (`node-runtime.sh` executa `EDGE_CACHE` consumindo o feed de mudanças: baixa só o que mudou, guarda corpo+versão em `$NODE_HOME/edge/`, confirma `/synced`, `reset=true` descarta o cache e recomeça do zero, 404 descarta a entrada, cursor só avança com a entrada em disco), **executor de entrega entregue** (`node-runtime.sh` executa `EDGE_DELIVER` revalidando a entrada com `?version=`, baixando o corpo quando a origem está à frente e gravando antes de confirmar, reconfirmando `/synced` para o veredicto atestar o estado atual; 404 descarta a entrada e devolve `deliverable:false` com `CONTENT_GONE`; o Control Plane compara o veredicto com o snapshot do nó e recusa `EDGE_DELIVER_UNCONFIRMED`/`EDGE_DELIVER_MISMATCH`; 112 asserções em `scripts/tests/node-executor-test.sh`), **atualização/rollback com assinatura OCI entregue** (`install.sh --update [--version v]` resolve o **digest** da imagem — nunca a tag, que o registry reescreve — e verifica `cosign verify --key` sobre ele antes de trocar; sem chave pública das imagens o update recusa; falha de verificação é fail-secure e o runtime atual continua; o digest verificado fica em `runtime-image` e o `--start` o reusa, então restart não volta à tag reescrita; `--rollback` re-verifica o anterior com a chave atual e preserva o atual como novo anterior; 43 asserções no instalador); falta o executor de `API_REQUEST` (ainda no eco de ciclo de vida) e o pipeline que publica/assina as imagens |
| imagens OCI assinadas | assinatura verificada no pull — **parcial**: o lado do nó está entregue (`--update`/`--rollback` só trocam imagem com `cosign verify` válido sobre o digest, contra a chave pública pinada em `image-pubkey`); falta o pipeline que constrói e assina as imagens |
| gestão de permissões | participante define limites; a rede respeita — **parcial**: CPU (concorrência) e memória declarada respeitadas; **disco agora é respeitado por quem escreve** (`limits.diskMb` assinado vira teto do estado de conteúdo no runtime: borda evicta a entrada mais antiga, réplica para com `diskFull`/`complete:false` sem avançar o cursor, identidade fora da conta, `0` = sem teto), provado em 3 cenários novos do harness; **config assinada agora é verificada, não só comparada**: o runtime reconstrói a linha canônica e confere a assinatura Ed25519 contra o X.509 do pino (`openssl pkeyutl -verify -rawin`), então `allowedTasks`/`limits`/`profiles` injetados no corpo por um Control Plane comprometido ou um proxy no caminho não passam — a assinatura da permissão nova não existe; provado em 2 cenários novos (assinatura que não é do pino e corpo adulterado após assinado) com Q0 por mutação da verificação; **banda agora é respeitada por quem transfere** (`limits.bandwidthMbps` assinado vira `curl --limit-rate` em toda transferência de corpo do nó — réplica, cache de borda, entrega e as duas pontas do transcode; `Mbps × 125` kB/s, `0` = sem teto, controle de heartbeat/poll/config fora do limite), provado em 2 cenários novos com 2 Q0 independentes |
| painel "Énóis Network" | todos os agregados e a página por nó com as ações administrativas — **parcial**: `GET /admin/network/nodes/dashboard` devolve os agregados do topo (total, ativos, indisponíveis, quarentena, vivos, degradados, capacidade declarada e vagas livres, médias de CPU/memória com contagem de amostras, distribuição por região/perfil/versão); página por nó com as ações de ciclo de vida, chave, **alterar capacidade** (`POST /{id}/capacity`) e **modificar perfis** (`POST /{id}/profiles`) — ambas compostas `configVersion`, então o runtime aplica na próxima leitura da config assinada; **histórico por nó entregue** (`GET /{id}/history` lê a trilha de auditoria de volta, mais nova primeiro, sobre o índice `(target_id, created_at)` da V286); falta o front no design system |
| interface do participante | solicitar, instalar, consultar estado e contribuição, desconectar — **parcial**: consultas entregues (`GET /network/participant/nodes` e `/{id}`: estado, capacidade declarada ao lado da observada, último heartbeat, versão do runtime e `tasksDone` — a contribuição medida em tarefas concluídas) e desconexão voluntária (`POST /{id}/disconnect`: SUSPENDED reversível, chaves caem na mesma transação, auditada `NODE_DISCONNECTED` com o participante como ator; nó alheio é 404); falta a solicitação de ingresso pela interface (hoje o token nasce no painel do admin) e o front no design system |
| distribuição geográfica | região declarada e considerada no escalonamento — **entregue**: região no enroll (duas formas aceitas, como a doc documenta) e `region` opcional na tarefa (V285) entram como **afinidade de ordenação** (degradação → origem → região → folga → jitter, ADR-012), e a região **fora de operação** entra como filtro (fase 6, ADR-015): um nó de região evacuada não recebe tarefa, não conta vaga e não pede trabalho, e o que estava na mão dele volta à fila na hora |

**Prova:** instalação limpa em máquina nova, do zero, sem conhecer a arquitetura interna.

## Fase 6 — Resiliência avançada

**Entrega:** a rede sobrevive à perda de uma região.

| item | critério de aceite |
|---|---|
| Control Plane redundante | promoção controlada com term; sem quórum, ninguém promove — **entregue (R101)**: `term`/`authority_id`/`promoted_at` em `network_state` e eleitorado em `network_authorities` (V288), CAS na promoção, quórum de maioria do DECLARADO com vivacidade de `network_instance_readers`, `POST /admin/network/authority/promote` auditado, e `term` na linha assinada do formato 2 (`executor` recusa termo menor) |
| recuperação regional | região afetada sai; nós de outras regiões atendem o que podem — **entregue (R103)**: `network_region_states` (V289) guarda a declaração humana (região normalizada, ator, motivo, desde quando) por `POST /admin/network/regions`, com `DELETE` para a volta e `GET` para o painel. Declarada a região, **quatro portas** fecham: `choose()` filtra o nó (filtro, não afinidade — ADR-015), `availableCapacity`/painel não contam a vaga do nó, o heartbeat responde `acceptTasks=false` (e `nextFor` devolve lista vazia), e `drainEvacuatedRegions` devolve o que estava na mão dos nós à fila **na hora**, sem esperar o claim TTL. Antes disso a tarefa presa voltava por claim vencido e era designada ao mesmo nó da região caída, em loop, até morrer de tentativas. Tarefa com alvo não roda em outra região (alvo é vínculo, V287). `awaitingEvacuation` no `/overview` e `outOfServiceRegions` no dashboard mostram o incidente antes da drenagem rodar |
| replicação geográfica | só o que pode replicar; divergência detectada — **entregue (R104)**: a replicação era toda por nó, e a dimensão geográfica faltava nas duas pontas. (1) A fila da reconciliação (05 §7) filtrava só `n.status = 'ACTIVE'`, então o nó da região declarada fora de operação (V289, R103) era conferido e gravava `CONTENT_STALE` — acusando nó saudável por uma decisão do Control Plane no meio do incidente; agora a fila exclui as regiões evacuadas (guarda normalizada, e o nó volta à fila sozinho quando a região volta). (2) `GET /admin/network/content/coverage` mede, por tipo da allowlist, quantas **regiões distintas** têm cópia utilizável (`regionsWithCopy`) contra quantas a rede tem (`totalRegions`), mais as cópias (`copies`) — "operável" = `ACTIVE` + heartbeat recente + região não evacuada; nó sem região conta como região própria. Prova: `NetworkReplicationGeographicTests` (10 testes; Q0 — sem a guarda, 4 falhas; contando nós, 1; ignorando heartbeat, 1) |
| anti split-brain | dois controladores → nó em quarentena — **entregue (R102)**: o nó reporta o termo aceito no heartbeat (`observedTerm`); termo MAIOR que o da autoridade vigente põe o nó em `QUARANTINED` com motivo `TERM_CONFLICT`, auditoria com os dois termos, `acceptTasks=false` na mesma resposta. Sem prazo de relógio (divergência não se resolve esperando): a volta é reaprovação administrativa. Depois de `QUARANTINED` o heartbeat segue 200 para o termo continuar subindo; quarentena manual segue 403 |
| testes de desastre | cada cenário da lista de testes obrigatórios da doc 06 §7 — **entregue (R105 + R106)**: revogação durante processamento (`NetworkDisasterTests.revogacaoNoMeioDaTarefaDescartaOResultadoEAudita`, ADR-017) e Control Plane fora por mais que o TTL de config (R106, ADR-018). O segundo exigia expiração na config assinada antes de virar teste: o formato 3 leva `ttlSeconds` na linha canônica, o runtime guarda a última config verificada com o instante da verificação e, passado o prazo sem conseguir revalidar, para de pedir tarefa e drena; recusa HTTP do Control Plane **nunca** cai no cache (revogação não é ressuscitada por arquivo local); o cache é reconferido contra o pino e fica fora do backup. Antes disso, a última permissão valia para sempre e um blip de 5s derrubava nó saudável. Prova: 6 cenários bash novos (133 asserções no total) com 4 Q0 independentes, 2 testes Java novos e 50 testes de alvo verde |
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
