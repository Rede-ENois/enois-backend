# Operações e fronteiras transacionais

O [grafo de persistência](persistencia.csv) lista operações de baixo nível e
entradas declaradas. O [grafo por handler](rotas-dependencias.csv) conecta os
dois ao HTTP. A coluna `jdbc_direto` identifica métodos com chamada resolvida
a Spring JDBC, `DataSource` ou JDBC. Os demais acessos incluem JPA e Flyway.
Chamadas a Spring Data fora de adaptadores aparecem em
`acesso_direto_persistencia` pelo tipo ou método herdado. Para revisar a
fronteira de uma rota, buscar o handler, as transações alcançáveis e as
chamadas de cada método no grafo. Usar as migrações e o SQL em fonte para
conferir índices de unicidade, condições de lock e alterações de contador.

| Operação candidata a J3 | Fronteira e invariantes atuais | Prova exigida na camada de dados |
| --- | --- | --- |
| Publicar post de texto com replay | `FeedController.publish` resolve sessão, calcula hash e passa a ação a `IdempotencyApplicationService.execute`. O callback cruza o proxy de `PublishPostService.publish` com propagação padrão. O serviço grava post, registros auxiliares como artigo/enquete, distribuição e intenção `PostPublished`. O controller registra presença no callback. Preservar o escopo usuário/método/rota/chave e a unicidade do replay, status `201` e corpo original. | Uma operação autoritativa com revalidação de autorização e visibilidade, estado e replay em um commit. Provar rollback durante a escrita, repetição após resposta perdida e conflito com corpo diferente. Notificação em consumidor ocorre após o commit da intenção. |
| Leitura de post e projeção | `FeedController.post` aceita visualizador opcional. A leitura aplica estado e permissões atuais, e busca dados auxiliares do post. Cache de projeção depende de invalidação e do escopo correto. | Ler após o commit em outra réplica. Comparar visibilidade anônima, autenticada e revogada e manter semântica de remoção. |
| Login e desafio | `AuthController.login` chama o caso de uso sem replay persistido. O fluxo consulta credencial, limites e estado de conta. O desafio, o consumo do segundo fator e a emissão de sessão são efeitos distintos. Cookies são escritos por advice e helpers HTTP. | Manter challenge e sessão na autoridade de identidade. Preservar status e regras de bloqueio. Comparar cookies e corpo no navegador sem gravar credenciais nas linhas de replay. |
| Renovar sessão | `AuthService.refresh` chama um helper privado que busca sessão e usa revogação condicional do refresh antes de emitir a substituta. A entrada pública não declara `@Transactional`. O CAS é prova de exclusão concorrente, e a atomicidade entre revogação e emissão exige prova própria. | Uma rotação com compare-and-set e uma só vencedora. Definir resultado recuperável quando a gravação ou a resposta falha após revogar. Preservar escopos da sessão e expiração. |
| Logout | `AuthController.logout` limpa cookies independentemente da ação. `IdempotencyApplicationService` usa escopo com hash derivado da credencial e executa a revogação no callback. | Revogação central observável por todas as réplicas, replay compatível e cookies de expiração também quando o resultado é replay. |
| Mudanças de credencial e conta | `AuthService` usa portas e JDBC direto. Cadastro/verificação e trocas de credencial atravessam perfil, credenciais, tokens e sessões. Verificar locks e unicidade por operação. Tokens externos e envio de e-mail têm semântica própria. | Manter mudança de identidade e invalidação coerentes. Efeitos externos usam intenção/recibo durável quando migrados. |
| Agenda e eventos | `EventService.attend/cancelAttendance` muda presença, fonte de compromisso e invalidação. Recorrência e avisos têm processamento próprio. | Confirmar presença e sua entrada juntas. Manter autorização e unicidade da participação no commit autoritativo. |
| Compras, mentoria, jogos e saldo | `MarketplaceService`, `MentorService` e os agendadores atravessam compras/sessões, reservas, liquidação e ledger. O grafo registra transações programáticas por item. | Liquidação e lançamento únicos por referência. Preservar a ordem dos locks, atualização relativa de saldo e compensação explícita. |
| Outbox e efeitos | `OutboxWorker` reclama e executa trabalho com transações distintas. Recibo do handler e efeito SQL têm a mesma fronteira quando executados no banco. Entrega externa tem janela entre envio e confirmação. | Claim recuperável, backoff e recibos preservados na restauração. Chave de efeito em integradores que aceitam deduplicação. |
| Catálogo e bytes | Storage separa catálogo, placements e cópia externa. Só uma colocação verificada pode servir como cópia válida. Multipart tem estado transitório próprio. | Confirmar tamanho/digest, versão e autorização antes da publicação. Preservar a última cópia e cercar resultado atrasado ou cancelado. |

No [guia de concorrência](../../CONCURRENCY.md), a linha pai é escrita antes
da filha e atualizações concorrentes de contador são feitas por delta. A
classificação de chamadas por rota serve para localizar esses pontos em
operações ainda fora da fatia. A revisão de cada contrato J3/J7 deve tornar
explícita a consistência de leitura, o conflito concorrente, o limite do
commit e os efeitos externos antes de mover a operação.
