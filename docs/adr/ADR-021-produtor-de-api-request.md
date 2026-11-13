# ADR-021 — O produtor de `API_REQUEST` escolhe o nome, e o nome é conferido contra a assinatura

**Status:** aceita (Fase 5)
**Data:** 2026-11-13

## Contexto

O ADR-020 entregou o **braço** do `API_REQUEST`: o executor do nó (`exec_api_request`), a lista
`allowedRoutes` **assinada** no formato 4 da config, e a recusa de qualquer destino que não esteja
nela. Deixou explicitamente de fora o **produtor** — a parte do Control Plane que enfileira a
task — e registrou isso como item de fase 5, com a ressalva de que o produtor "precisa de uma
decisão de produto — *quais* rotas a rede oferece".

A decisão de produto já existe: a lista vem da propriedade `rede.network.config.allowed-routes`, que
o Control Plane assina e publica. O que faltava era o caminho que a transforma em trabalho.

Deixar o produtor fora deixou três coisas true ao mesmo tempo:

- O executor era **código morto**: existia, estava provado por 163 asserções de harness, e nenhuma
  task `API_REQUEST` nascia em lugar nenhum da rede. O `TaskType.API_REQUEST` estava no enum e
  `allowedTypesFor` o entregava na config assinada de todo nó `NODE_API` — ou seja, o nó era
  **autorizado** a executar um tipo de trabalho que ninguém nunca lhe pedia.
- O painel não tinha nada a oferecer aqui. Os outros produtores (`EDGE_CACHE`, `EDGE_DELIVER`,
  `MEDIA_TRANSCODE`) têm rota de admin, e os dois últimos já têm execução de verdade.
- O item do plano continuava marcado como "falta o produtor" em três documentos (doc 03 §3b-bis,
  doc 09 §7, doc 07), o que mantinha a impressão de um furo na fase 5.

O risco óbvio de fechar esse furo é o oposto do que a assinatura protege: um produtor que aceite
**qualquer** nome de rota cria tasks **fadadas**. A task nasce, ocupa a fila, é designada a um nó,
e morre no nó com `API_ROUTE_NOT_ALLOWED` — trabalho morto que, no painel, parece uma rede travada.
O produtor precisa recusar antes, não o executor depois.

## Decisão

1. **O produtor existe e só escolhe o nome.** `POST /admin/network/nodes/{nodeId}/api-request?route=<nome>`
   enfileira a task com payload `{"route":"<nome>","method":"GET"}`. Não há parâmetro de URL, não há
   cabeçalho, não há credencial: o destino é resolvido pelo nó contra a lista assinada, como o
   ADR-020 decidiu. O produtor não tem onde escrever uma URL — e a rota de admin não aceita uma.

2. **O nome é conferido contra a MESMA lista assinada que o nó vai receber.** A conferência usa
   `NetworkConfigService.allowsRoute(nome)`, que lê a lista já parseada — o mesmo parser, a mesma
   propriedade, a mesma lista que `forNode` publica no documento. Não há uma segunda leitura da
   propriedade em outro lugar: um segundo parser seria um segundo lugar para a regra da vírgula
   divergir, e a divergência seria silenciosa — o produtor aceitaria uma rota que o nó não tem na
   assinatura (task fadada) ou barraria uma que o nó executaria (pedido legítimo recusado).

3. **A recusa é do produtor, antes de a task nascer.** `API_ROUTE_NOT_ALLOWED` (422) sai do
   `ApiRequestService`, e a fila fica limpa. O executor continua recusando rota fora da lista —
   a defesa do nó continua valendo, porque o runtime não confia no Control Plane o bastante para
   dispensar a conferência.

4. **A task leva alvo.** O pedido é sobre aquele nó, e a resposta da rota pode depender do estado
   local dele. Alvo é filtro no despacho (não preferência): nó alvo fora dos candidatos deixa a
   task na fila, e o nó errado não a executa.

5. **A idempotência é por nó, por rota e por janela.** `api:` + sha-256 de `<nó>:<rota>:<janela de
   5 min>` = 68 caracteres, dentro dos 80 de `network_tasks.idempotency_key`. Por nó e por rota,
   porque são trabalhos diferentes; por janela, porque a mesma rota precisa ser requisitada de novo
   depois — sem a janela, o duplo clique do admin criaria uma task só para sempre.

6. **O perfil do nó é conferido com o mesmo predicado do despacho.** `TaskType.API_REQUEST.allowedFor(node.profiles())`
   — que aceita `NODE_API` **e** `NODE_LOCAL`, porque `NODE_LOCAL` cobre todos os tipos. Um produtor
   que conferisse só `NODE_API` recusaria a infraestrutura da própria rede.

7. **O painel vê as rotas e o que está em aberto.** `GET /admin/network/api-routes` devolve os
   nomes assinados (para o admin pedir um nome que exista) e
   `GET /admin/network/nodes/{nodeId}/api-request` devolve as requisições em aberto do nó.

## Alternativa rejeitada

**Deixar o produtor escrever a URL no payload e o executor conferir.** É o desenho que o ADR-020
recusou, e recusar ele duas vezes não o torna melhor: o destino escolhido por quem pede continua
sendo um destino que ninguém assinou, e o nó continua sendo SSRF apontado para dentro da rede do
participante. A assinatura do destino não é enfeite: é a única coisa que impede o intermediário de
trocar o caminho.

**Deixar o produtor aceitar qualquer nome e deixar o executor recusar.** É a opção mais simples de
escrever, e a que produz o pior resultado: a task nasce, ocupa vaga de despacho, é designada, viaja
até o nó e morre lá. O efeito no painel é o oposto do desejado — "requisição recusada" pareceria
falha de rede ou de destino, quando o problema foi o pedido. Além disso, a fila enche de trabalho
que nunca vai rodar, e o contador de fila do painel passa a mentir sobre a saúde da rede.

**Uma segunda leitura da propriedade de rotas, só para o produtor.** Seria mais simples de escrever
do que expor o accessor do `NetworkConfigService`, e criaria duas listas que precisam concordar. A
regra da vírgula (ADR-020) já provou que esses parsers divergem fácil: um `split(",")` aqui
truncaria `https://api.exemplo/x?ids=1,2` no meio, e o produtor veria a rota `acervo` com o destino
cortado — diferente do que o nó recebe na assinatura.

**Uma task por rota para o nó inteiro, sem alvo.** O pedido é sobre um nó; a resposta da rota pode
depender do estado local dele. Executar no nó sorteado é executar no lugar errado.

## Consequências

- **A task `API_REQUEST` deixou de ser código morto.** O `TaskType` existia, o executor estava
  provado, e agora existe quem enfileire — e a lista assinada, que é a permissão, é o que limita o
  que pode ser pedido.
- **O produtor não amplia a superfície de ataque.** Ele não aceita URL: aceita nome, e o nome só
  vale se estiver na lista assinada. Quem consegue chamar a rota de admin não consegue escolher o
  destino — consegue apenas escolher, entre os destinos que o Control Plane já assinou.
- **O painel precisa de um controle para isto.** A rota de admin existe e o front ainda não a
  oferece: `src/lib/api/admin-network.ts` não tem chamada para os produtores (nenhum deles, nem
  `edge-cache` nem `edge-deliver`). É o próximo item de F5, no design system.
- **A lista continua vazia por padrão.** Nenhuma rota declarada significa que nenhum pedido de
  `API_REQUEST` é aceito em lugar nenhum — o produtor existe, mas a rede não oferece nada para
  chamar até alguém declarar a rota. É o comportamento correto: a permissão que falta é do
  operador, e nada aqui a concede por omissão.
- **Nenhuma migração de banco.** A task usa a tabela e as colunas que já existem
  (`network_tasks.target_node_id` de V287, `idempotency_key` de V277). **V290 continua livre.**

## Provas

**Q0 (mutação 1 — o produtor aceita qualquer nome):** trocando a conferência
`if (!configs.allowsRoute(routeName))` por `if (false)`, os dois testes de rota não autorizada
falham — `ApiRequestProducerTests.rotaForaDaListaAssinadaERecusada` (o pedido deixa de ser recusado)
e `NetworkApiRequestTests.rotaNaoAssinadaERecusadaSemCriarTarefa` (422 esperado, 200 obtido, e a
task nasce na fila). A mutação prova que o teste detecta a ausência da conferência, e não só que o
código novo está escrito.

**Q0 (mutação 2 — a task nasce sem alvo):** trocando o `targetNodeId` do `EnqueueCommand` por
`null`, `NetworkApiRequestTests.noAlvoRecebeARequisicaoEOutroNoDeApiNao` e
`pedidoAbertoApareceESomeAoConcluir` falham — a task passa a ser designada por mérito e o painel
não mostra o alvo. A mutação prova que o alvo está sendo fixado pelo teste.

**Testes Java novos:**

- `ApiRequestProducerTests` (8 casos) — o pedido nasce com alvo e chega ao nó de API pelo caminho
  das demais; **rota fora da lista assinada é recusada e nada nasce na fila**; nó sem `NODE_API` é
  recusado; `NODE_LOCAL` pode executar; a chave difere por nó e por rota; o mesmo pedido na mesma
  janela não duplica; **rota com vírgula na query continua autorizada** (a allowlist do produtor e
  a lista do documento são a mesma lista, inclusive no caso que o ADR-020 provou tricky).
- `NetworkApiRequestTests` (6 casos) — o caminho inteiro ponta a ponta: pedido do admin → task com
  alvo → despacho → o nó alvo recebe e **o outro nó de API não recebe** (o alvo é o menos folgado
  dos dois, para que a asserção não passe por sorteio); o pedido em aberto aparece no painel e some
  ao concluir; o painel lista as rotas assinadas; rota não assinada é recusada sem criar task; não
  admin não pede nem vê; nó sem perfil de API é recusado.

**Suíte completa:** 2698 testes antes desta rodada; com os 14 testes novos, **2712** — 0 falhas,
0 erros, 3 skipped. **Nenhuma migração de banco nesta rodada.**
