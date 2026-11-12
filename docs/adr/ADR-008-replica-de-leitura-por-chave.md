# ADR-008 — Catálogo de réplica de leitura por chave

**Status:** aceita (Fase 3)
**Data:** 2026-11-11

## Contexto

A borda (`NODE_EDGE`) descobre conteúdo **por id**: alguém pede `GET /users/ana`, o nó pede
`PROFILE/ana` e guarda com a versão. Ela nunca precisa saber *quem existe*. A réplica de leitura
(`NODE_READ_REPLICA`) tem a necessidade oposta: para servir sem a infra principal, ela precisa
**enumerar** o catálogo público e manter cópia dele. Sem enumeração, "réplica" seria só um cache com
outro nome.

O plano da Fase 3 exige "projeção com `sourceVersion`; allowlist explícita; nunca tabela crua"
([07 — Plano incremental](../rede-distribuida/07-plano-incremental.md)), e o
[08 — Impacto](../rede-distribuida/08-impacto.md) §9 deixou aberto **qual allowlist no dia 1**.

Além da allowlist, havia uma decisão silenciosa e cara: **como enumerar**. Há dois primitivos no
repositório e nenhum deles serve sozinho:

- `UserProfileRepository.findAllIds(offset, limit)` é posição pura (`limit`/`offset`). Ele existe
  para o job de ranking, sobre uma tabela que só cresce. A réplica percorre o catálogo de um sistema
  vivo: entre a página 3 e a 4 alguém apaga uma conta, o `offset` desloca e uma conta nunca é lida;
  alguém cria outra e uma conta é lida duas vezes. Numa réplica isso não é imprecisão estatística, é
  conteúdo sumindo do ar sem aviso.
- `CommunityRepository` não tinha enumeração nenhuma: só rolagem "para trás" da vitrine
  (`before`, mais novas primeiro), que não percorre o catálogo em direção única.

## Decisão

**Enumeração por chave `(criado em, id)`, catálogo separado da leitura, allowlist fixa de duas
projeções.**

1. **Chave, não posição.** Cada página é "as primeiras N linhas depois de `(at, id)`". A posição de
   quem já foi lido não muda quando alguém entra ou sai antes dele. As duas portas dos módulos donos
   (`replicaPage`) recebem a chave e devolvem uma linha a mais que a página, para saber se há
   continuação sem contagem.
2. **Catálogo e leitura são rotas diferentes, e o corpo vem da rota de conteúdo.** `GET
   /network/replica/{kind}` devolve **ids** (o @ do perfil, o id da comunidade); o corpo de cada um
   vem de `GET /network/content/{kind}/{id}`, já existente, com a versão cunhada e o hash do corpo.
   Uma rota só de catálogo evita um segundo caminho de serialização da mesma projeção — e é o que
   garante que o `sourceVersion` que a réplica confirma em `/synced` é exatamente o que a borda
   confirmaria.
3. **O catálogo devolve o @, não o id do usuário.** O id interno não abre o conteúdo; devolvê-lo
   obrigaria uma tradução por item. A chave que o catálogo entrega é a chave que a leitura aceita.
4. **`hasMore` e cursor são independentes.** `hasMore` diz "há mais agora"; o cursor diz "li até
   aqui" e é devolvido também no fim. Curso nulo no fim faria a réplica recomeçar do zero na rodada
   seguinte e rebaixar o catálogo inteiro.
5. **O catálogo nunca é mais largo que a vitrine.** Conta suspensa, conta removida, comunidade de
   conta suspensa e comunidade com texto de ataque saem pelo mesmo critério da lista pública, dito no
   módulo dono (`ProfileService.accountsVisible`, `CommunityService.communitiesVisible`) e aplicado
   **em lote**: uma consulta de sanção por página, não uma por item. A réplica é superfície pública;
   se ela recebesse o que a vitrine esconde, o escondido só mudaria de porta.
6. **Allowlist do dia 1: `PROFILE` e `COMMUNITY`.** São as duas projeções públicas que a borda já
   serve. Post, comentário, mensagem e tudo que dependa de quem lê ficam fora, porque o nó não decide
   autorização de usuário. Ampliar é decisão de contrato com versão nova, não parâmetro de requisição.
7. **Perfil de nó é a permissão.** `NODE_READ_REPLICA` e `NODE_LOCAL` enumeram; `NODE_EDGE` autentica
   e recebe 403. A borda não precisa da lista de todo mundo para operar — dar a ela seria entregar
   um catálogo de contas a quem só responde a id.

## Consequências

- Migração **não** foi necessária: o catálogo é uma consulta ordenada, e a chave já existe nas
  tabelas (`users.created_at`, `communities.created_at`). Um índice composto `(created_at, id)` é a
  evolução natural quando o catálogo crescer; hoje o volume não o justifica, e índice sem medida é
  custo de escrita especulativo.
- `NODE_READ_REPLICA` passou a ser aceito também na rota de conteúdo e de confirmação
  (`requireContentReader`), que antes exigia borda ou contingência: a réplica precisa ler exatamente
  a projeção que enumerou e confirmar o que guardou, senão a reconciliação dela não teria contra o
  que comparar.
- O contrato de `/network/replica/{kind}` é "página de ids com posição", não "lista completa". Um nó
  que pare no meio retoma do cursor; um nó que perca o cursor refaz a travessia — e é por isso que a
  travessia é idempotente do ponto de vista da réplica: reler um id devolve `changed=false` quando a
  versão já é a mesma.
- A allowlist precisa de teste negativo: tipo fora dela é recusado **antes** de qualquer consulta
  (`NetworkReplicaTests.tipoForaDaAllowlistNaoEnumera` fixa que nenhuma versão foi criada).