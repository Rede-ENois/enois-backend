# J0 — Inventário e baseline da migração Java

| Fonte | Revisão congelada para comparação |
| --- | --- |
| Backend Java | `9a8062c5f5fc4482a5e14c72b12dad20bb795f8d` |
| Plataforma distribuída | [`fadda5fb3b4fcd08568342f8ce02dcd0dbab6106`](https://github.com/Rede-ENois/enois-distributed/tree/fadda5fb3b4fcd08568342f8ce02dcd0dbab6106) |
| Frontend | [`3e69d045f8f0be4ce2d4aee71c9b23ebd33ef6af`](https://github.com/Rede-ENois/enois-frontend/tree/3e69d045f8f0be4ce2d4aee71c9b23ebd33ef6af) |

Este inventário acompanha o [plano de migração da plataforma](https://github.com/Rede-ENois/enois-distributed/blob/fadda5fb3b4fcd08568342f8ce02dcd0dbab6106/docs/backend-migration/04-delivery-plan.md).
A revisão Java contém 2.142 fontes compiláveis e 71 ocorrências de `@Scheduled`.
A primeira fatia proposta é interna e depende da aceitação do M3 e da aprovação
dos mantenedores. A classificação nos manifestos identifica a etapa responsável.

| Arquivo | Uso |
| --- | --- |
| [Rotas](rotas.csv) e [mappings do Spring](rotas-framework.csv) | Método, caminho, dono, condições, autenticação observável, cabeçalhos, transporte e etapa proposta por mapping Java. |
| [Dependências por rota](rotas-dependencias.csv) | Entradas do grafo, chamadas resolvidas, fronteiras transacionais e tipos de armazenamento alcançáveis. |
| [Portas](portas.csv) e [persistência](persistencia.csv) | Declarações de portas e implementações candidatas, consumidores, repositórios, JDBC, transações e consultas. |
| [Agendamentos](agendamentos.csv), [ciclo de vida](ciclo-de-vida.csv) e [recursos locais](recursos-locais.csv) | Dono proposto de cada agenda, gatilhos fora do agendador e referências a arquivos, cache, filtros e subprocessos. |
| [Contrato HTTP e compatibilidade](compatibilidade.md) | Comportamento do cliente, limites, fatia candidata e regras de versão e reversão. |
| [Transações](transacoes.md) e [estado](estado.md) | Operações que cruzam domínios, autoridade de dados, segredos e transição dos participantes. |

Nos CSVs, `fonte` é relativa a `src/main/java/com/socialcoisa/rede/`. As chaves
de método incluem a assinatura e omitem esse prefixo Java. Os índices foram
extraídos da árvore sintática Java com resolução de símbolos pelo compilador.
Arestas de execução via interfaces incluem candidatas por classe, nome e
aridade. O grafo pode superestimar o conjunto de beans ativos e não resolve
escolha dinâmica de beans, reflexão, agendamento indireto ou publicação de
eventos como chamadas síncronas. Uma anotação de transação é evidência de
declaração. Encadeamento por proxy, rollback, autoinvocação e efeitos após
commit precisam de revisão da operação. Os registros de cabeçalhos incluem
anotações e chamadas diretas com literais ou constantes HTTP. Filtros, advice,
clientes indiretos e limites comuns estão no contrato HTTP.

## Evidência executada

- Compilação Maven com `-DskipTests compile` e alvo Java 25.
- 54 testes existentes passaram em oito classes, sem falhas ou skips:
  `SessionCookieTests`, `SessionRefreshTests`, `V1ApiAliasTests`,
  `IdempotencyFlowTests`, `OutboxWorkerTests`, `AutomatedWritePathsTests`,
  `MediaStorageProxyTests` e `TwoInstancesBroadcastTests`.
- Um teste temporário subiu o Spring com H2 e comparou 1.029 mappings Java
  por caminho, verbo e handler. Cinco registros de infraestrutura no perfil
  de teste estão no manifesto separado. A comparação cobre os mappings
  registrados. A exposição de cada caminho depende do ingresso.
- A baseline do frontend usa o cliente TypeScript e a configuração de ingressos
  da revisão acima. As provas com navegador, dois processos Java, gateway e
  MariaDB pertencem às etapas de integração. Orçamentos operacionais dependem
  de medição em ambiente representativo.

Antes de promover a fatia de candidata a baseline suportada, confirmar o
identificador e a versão da aplicação, a lista de rotas de infraestrutura, a
matriz de compatibilidade e o responsável por cada capacidade legada. Registrar
também o digest do artefato construído e os objetivos medidos de latência, erros,
fila, conexões e recuperação em staging.
