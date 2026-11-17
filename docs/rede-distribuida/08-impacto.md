# 08 — Impacto na infraestrutura atual

## 1. Resumo

**Nenhuma mudança de comportamento para o usuário final.** A rede é capacidade adicional; a
plataforma continua funcionando exatamente como hoje se não houver nenhum nó.

## 2. Por fase

| fase | toca o que já roda? | risco | mitigação |
|---|---|---|---|
| 1 | adiciona tabelas e rotas novas | baixo | tudo aditivo; rotas novas sob `/admin/**` e `/network/**` |
| 2 | adiciona um handler ao outbox | médio | handler novo não altera os existentes; desligável por config |
| 3 | **muda `MessageStreamHub` e `CacheConfig`** | alto | exige broadcast entre instâncias antes de qualquer réplica |
| 4 | adiciona modo operacional | médio | modo default `NORMAL` = comportamento atual |
| 5 | adiciona instalador e painel | baixo | nada do caminho atual muda |
| 6 | adiciona redundância de Control Plane | alto | só depois de 1–5 estáveis |

## 3. Impacto em recursos

| recurso | fase 1 | fase 2 | fase 3 |
|---|---|---|---|
| tabelas novas | 3 | 1–2 | 1–2 |
| conexões de banco | +0 (rotas admin são raras) | +0 | +0 |
| memória | desprezível | desprezível | cache de borda fora do pod |
| CPU | desprezível | scheduler consome pouco | transcode sai do pod |
| rede | heartbeat a cada 30 s por nó | despacho de tarefa | tráfego de conteúdo |

O ganho real aparece na Fase 3: o transcode de mídia — hoje CPU pura dentro do pod, limitado a 2
concorrentes — passa a poder rodar em `NODE_MEDIA`.

## 4. Impacto em segurança

| item | antes | depois |
|---|---|---|
| superfície de API | 692 mapeamentos | +~10 rotas de nó, todas autenticadas |
| credencial de serviço | `X-Deploy-Token` global | + API key por nó, com hash e revogação |
| escrita sem sessão | lista branca fechada | + rotas de nó na lista, com credencial própria |
| auditoria | ações de moderação | + toda ação administrativa de rede |

**Nenhum controle existente é enfraquecido.** `AdminRouteInterceptor` e `WriteRouteInterceptor`
continuam valendo; as rotas novas entram nas regras que já existem.

## 5. Impacto em deploy

- Fase 1–2: nenhuma mudança no pipeline. Migrações Flyway normais.
- Fase 3: broadcast entre instâncias resolvido por tabela de sinais com polling (ADR-007), sem
  Redis e sem broker. Nenhuma dependência de infra nova: a mesma MariaDB, duas tabelas novas.
  Réplicas de leitura por catálogo de chave `(criado em, id)`, sem migração nova e sem tabela
  paralela ([ADR-008](../adr/ADR-008-replica-de-leitura-por-chave.md)).
- Fase 5: novo artefato (imagem do runtime) e novo pipeline de publicação assinada.

## 6. Impacto em observabilidade

- Gauges novos com label de nó: `rede_network_nodes_total{status}`, `rede_network_node_last_seen_seconds`,
  `rede_network_tasks_pending`, `rede_network_tasks_failed_total`.
- Alertas novos: nó indisponível, certificado expirando, falha de autenticação, latência, uso
  excessivo, fila acumulada, versão incompatível, comportamento suspeito, divergência de
  sincronização, falha de replicação.
- Logs estruturados com `nodeId` e `requestId`, sem PII e sem chave.

## 7. Impacto em dados

- Nenhuma migração destrutiva.
- Nenhuma coluna existente alterada.
- Nenhum dado pessoal replicado para nó externo.
- Retenção: heartbeat e histórico de nó com janela própria, como `audit_logs` já faz.

## 8. Custo de não fazer

O host de QA já divide 7,76 GB com três outros projetos. Produção é um único `enois-node2`. Um pico
de tráfego ou a queda desse host derruba a plataforma inteira, e não existe caminho para absorver
carga sem comprar mais uma máquina no mesmo provedor, na mesma região.

## 9. O que precisa de decisão humana antes de codar

1. ~~**Broadcast entre instâncias** (Fase 3): Redis, ou tabela de eventos com polling?~~ Decidido:
   tabela de sinais com polling ([ADR-007](../adr/ADR-007-broadcast-entre-instancias-por-tabela.md)).
   Redis seria dependência de infra nova sem ganho para um aviso que dura segundos.
2. **Autoridade de certificado** (Fase 1): CA própria do Énóis, ou certificado público por nó?
3. **Política de aprovação** (Fase 1): todo nó exige aprovação manual, ou existe política automática
   para `NODE_LOCAL`?
4. ~~**Escopo de `NODE_READ_REPLICA`** (Fase 3): qual allowlist de projeções no dia 1?~~ Decidido e
   entregue: `PROFILE` e `COMMUNITY` — as duas projeções públicas que a borda já serve, por @ e por
   id. Post, comentário, mensagem e qualquer coisa que dependa de quem lê continuam de fora, porque a
   réplica não decide autorização de usuário. Ampliar a allowlist é decisão de contrato com versão
   nova, não parâmetro de requisição.

Nenhuma dessas decisões bloqueia a Fase 1 no que diz respeito a tabelas, API key e heartbeat — que é
onde a implementação começa.
