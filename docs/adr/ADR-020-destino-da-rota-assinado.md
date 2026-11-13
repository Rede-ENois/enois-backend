# ADR-020 — O destino da rota é assinado, não transportado

**Status:** aceita (Fase 5)
**Data:** 2026-11-13

## Contexto

`API_REQUEST` era o último tipo de task da rede que não executava de verdade: o runtime não tinha
o braço, e a task caía no eco de ciclo de vida (o nó confirmava o `idempotencyKey` e devolvia sem
fazer nada). O tipo existe desde o desenho (`TaskType.java`: `API_REQUEST(NodeProfile.NODE_API)`) e
pertence ao perfil `NODE_API`, cujo propósito declarado na doc 04 é chamar uma API **em nome da
rede**.

Dizer "chamar uma API" deixa uma pergunta para a qual não há resposta ingênua: **para onde?** A
forma óbvia de responder — o payload traz a URL — é o desenho errado, e o motivo é que o nó
externo não é um servidor confiável do ponto de vista de quem o chama:

- O nó roda numa máquina do participante, dentro da rede dele. Um destino escolhido pelo
  solicitante transforma o nó em **SSRF apontado para dentro da rede do participante**: o
  metadata do hypervisor (`169.254.169.254`), o painel do roteador, um serviço interno que não
  responde à internet mas responde ao localhost da máquina. O nó não precisa ser malicioso para
  isso — basta alguém pedir.
- O payload é transportado por um intermediário. Se a URL vem no payload, um proxy no caminho (ou
  um Control Plane comprometido) escolhe o destino. E a permissão do nó é justamente a coisa que
  **não** pode ser transportada: permissão se assina.

A doutrina já escrita na rede resolve o impasse sem inventar nada: **permissão é dada do Control
Plane e assinado; o nó obedece, ele não decide** (doc 04 §4). A pergunta "qual destino este nó
pode chamar?" é uma pergunta de permissão. Então ela não pode ter resposta no payload — tem que
estar no documento que o Control Plane assina.

## Decisão

1. **O payload carrega o NOME da rota, nunca a URL.** `{"route":"catalogo","method":"GET"}`. O
   executor resolve o nome contra uma lista e **não tem** caminho alternativo: rota ausente na
   lista é `API_ROUTE_NOT_ALLOWED` e **nenhuma requisição sai**.

2. **A lista entra na config assinada — formato 4.** `allowedRoutes`, no formato `nome=url`
   (`["catalogo=https://catalogo.exemplo/api"]`), entra no payload **e** na linha canônica, logo
   **depois** de `allowedTasks`. A regra do "campo novo nunca desloca o último" continua valendo:
   `term` continua fechando a linha, e o runtime de formato 3 recusa o documento novo em vez de
   ler um campo no lugar errado.

3. **Por que assinar resolve.** Com a lista dentro da assinatura, trocar o destino por outro não
   é mais "injetar um campo no corpo": é adulterar a assinatura, e o runtime reconstrói a linha
   canônica e confere contra o **pino** (ADR-018), recusando o documento. O mesmo middleware que
   já barra `allowedTasks`/`limits`/`profiles` adulterados barra a rota adulterada, sem uma
   verificação nova.

4. **A lista é vazia por padrão.** Vem de `rede.network.config.allowed-routes`
   (`NETWORK_CONFIG_ALLOWED_ROUTES`), é ordenada, sem repetição, e **sem rota declarada o nó não
   tem para onde ir** — a task é recusada. Isso significa que todo nó já existente só ganhou uma
   lista **vazia** na assinatura: nenhuma permissão nova foi concedida por omissão, e nenhum nó
   pode chamar nada até alguém declarar a rota.

5. **A entrada só entra na assinatura se passar em quatro regras.** Cada uma protege uma
   propriedade de que o runtime depende, e nenhuma delas é negociável:

   - `nome=url`, com nome e URL não vazios;
   - nome em `[A-Za-z0-9._-]` — o mesmo conjunto que o runtime aceita no payload. Um nome fora
     dele entraria assinado e nunca seria resolvido: a task morreria como "rota não autorizada" no
     meio do trabalho, sem que ninguém entendesse por quê;
   - destino `http://` ou `https://` — o executor rejeita outro esquema, e a rota entraria
     assinada para ser sempre recusada;
   - destino só em ASCII imprimível, sem `"`, `\` nem `]`. `"` e `\` viram escape no JSON e `]`
     fecha o array: o destino seria lido em pedaços, ou a config inteira recusada. E fora do ASCII
     o signer (ordem natural de `String`, por unidade de código UTF-16) e o runtime (`sort` por
     byte UTF-8) ordenariam a lista de jeitos diferentes — a linha reconstruída pelo nó deixaria
     de conferir com a assinatura, e o nó recusaria uma config legítima.

   Nome repetido com outro destino também é descartado: o runtime resolve por nome, e duas URLs
   para o mesmo nome significam um destino que nunca será chamado, sem que ninguém perceba.

6. **O executor é mínimo por desenho.** Só `GET` e `HEAD` (escrita em API externa é outro
   contrato, com o seu próprio desenho); sem `-L`, porque destino não escolhe outro por redirect;
   **sem a credencial do nó** no destino (`X-Node-Key` é do Control Plane); teto de 30 s e de 1
   MiB. O `--max-filesize` do curl só confia no `Content-Length`, então o executor **mede** o
   arquivo baixado e recusa acima do teto — servidor que não declara o tamanho não escapa.

7. **O resultado é evidência, não conteúdo.** `{"route","status","bytes","digest"}`. O corpo da
   rota externa **não volta** para dentro da rede: devolver conteúdo de terceiro para o Control
   Plane é a separação de ambientes que a doc 02 exige. O que fica é o bastante para provar que a
   chamada aconteceu e o que ela devolveu.

8. **O produtor fica fora desta entrega.** Não existe ainda rota de admin que enfileire
   `API_REQUEST`. A entrega é o executor e a lista assinada; o produtor, quando vier, só pode
   escolher o **nome** da rota — ele não tem onde escrever uma URL.

   > **Atualizado no R109 (ADR-021).** O produtor foi entregue: `POST
   > /admin/network/nodes/{nodeId}/api-request?route=<nome>`. Ele escolhe o **nome**, como esta
   > decisão previa, e o nome é conferido contra a **mesma** lista assinada que o nó recebe, de
   > modo que nenhuma task nasce fadada a `API_ROUTE_NOT_ALLOWED`. O "quando vier" abaixo está
   > cumprido; o que a decisão previa — que o produtor não tem onde escrever uma URL — continua
   > valendo.

## O corte da vírgula: o defeito que a assinatura sozinha não pegava

Assinar a lista resolve o SSRF, mas não resolve o **corte de texto**. A leitura da lista no runtime
é feita em bash, sem `jq`, e a primeira versão separava os itens por **qualquer vírgula** — que é
justamente o separador natural de uma lista, e também um caractere **legítimo de URL**:

```
allowedRoutes: ["catalogo=https://api.exemplo/x?ids=1,2"]
```

Cortando ali, o destino lido pelo nó seria `https://api.exemplo/x?ids=1` — um caminho que
**ninguém autorizou**, dentro de uma lista cuja assinatura conferia perfeitamente. A assinatura
prova que a lista não mudou; ela não prova que a lista foi bem lida. O nó obedeceria a permissão
assinada **para um destino diferente**, e o SSRF voltaria pela porta do parser.

O mesmo corte aparecia em três lugares, e os três tinham de mudar juntos: a propriedade de
configuração no Control Plane (que separa as entradas `nome=url`), a reconstrução da linha canônica
no runtime (`json_list_sorted`, que monta a lista que vai na assinatura) e a resolução do nome
(`route_lookup`, que escolhe o destino). Um só deles corrigido deixaria a lista inconsistente dos
dois lados, e a falha apareceria como "assinatura não confere" — que é o sintoma de todo defeito de
ordenação, e um sintoma que manda o mantenedor procurar no lugar errado.

A correção é a mesma nos três: **a vírgula só separa quando introduz uma entrada nova** — no
Control Plane, quando o que vem depois é um `nome=url` válido; no runtime, quando a vírgula é
seguida de aspas (o padrão `","` que separa dois elementos do array JSON). Uma vírgula dentro da
URL não é seguida de aspas, então sobrevive.

E, para não deixar a ambiguidade para trás: o Control Plane só aceita rota com nome em
`[A-Za-z0-9._-]`, destino `http(s)` e sem caractere que precise de escape. Quem precisar de uma
vírgula literal na query escreve `%2C` — a forma canônica de URL.

## Alternativa rejeitada

**URL no payload, validada no runtime contra uma regex de "não é privado".** É o desenho comum
e é o que a maioria dos SSRF-blocking filters faz (bloquear `127.0.0.0/8`, `10/8`, `169.254/16`).
Rejeitado por dois motivos. Primeiro, a lista de faixas bloqueadas é uma corrida: `0.0.0.0`,
`[::]`, `0x7f.0.0.1`, `2130706433` (decimal), DNS que resolve para dentro, `localhost`, e o
serviço que responde em `169.254.169.254` por um nome próprio — a lista não fecha. Segundo, e
mais grave, essa validação **acontece no nó**: o nó valida o destino, ou seja, o executor confia
na própria checagem de um host que pode estar comprometido. A permissão tem de ser conferida
contra uma assinatura feita por quem manda, não por quem executa.

**O Control Plane valida a URL antes de enfileirar.** Melhor que a regex, mas não resolve: o
payload é transportado entre o Control Plane e o nó, e o runtime confia no payload. Um proxy no
caminho trocaria a URL depois da validação. A troca acontece **no nó**, contra o **pino** — que
é o ponto do ADR-018.

**Uma lista de rotas no banco, consultada pelo nó em tempo de execução.** O nó buscaria a rota na
hora da chamada. Isso abre a consulta em si (o nó sai para a rede do Control Plane e traz o
destino, que pode ter sido trocado no caminho) e quebra a regra de que a permissão é um **evento
assinado** (doc 03 §2: a config é um evento, não um relógio). A lista assinada é a mesma
informação com a propriedade que importa: ela não muda entre a emissão e o uso, e o nó sabe
que não mudou porque conferiu a assinatura.

**Assinar o destino dentro da task, em vez de na config.** A task também é assinada no
desenho, então seria "mais simples" — mas a permissão de *quais* destinos o nó pode chamar muda
na **config**, não na task: uma task é um pedido pontual, a config é a permissão do nó. Assinar
por task daria ao solicitante a escolha do destino (ele cria a task, ele escolhe a URL
assinada). O assinante do destino tem de ser quem concede a permissão — o Control Plane, na
config.

## Consequências

- ~~**A task `API_REQUEST` continua sem produtor.**~~ **Resolvido no R109 (ADR-021).** O produtor
  existe: `POST /admin/network/nodes/{nodeId}/api-request?route=<nome>`, que enfileira a task com
  alvo e confere o nome contra a mesma lista assinada que o nó recebe. A decisão de produto que
  esta entrega adiou — *quais* rotas a rede oferece — estava pronta: a lista vem de
  `rede.network.config.allowed-routes`, que o Control Plane assina. O que o produtor **não** faz é
  escolher o destino: ele escolhe o nome, e o nome só vale se estiver assinado.
- **Quem declare a rota declara uma URL de terceiro.** A lista é um allowlist de destinos
  externos que a rede aceita chamar. Um destino mal configurado (ou malicioso) é um problema de
  configuração, e por isso a lista é dada por propriedade do Control Plane e não pelo painel de
  participante.
- **O nó continua sem poder falar com a rede do participante por conta própria.** É a intenção:
  o nó é um-workers da rede, não um cliente da rede local.
- **A lista cresce no documento.** `allowedRoutes` entra na linha canônica, que é o que é
  assinado; como a lista é vazia por padrão e o caso de uso é poucas rotas por nó, o crescimento é
  pequeno — mas é o custo de assinar o destino, e ele é o preço do SSRF fechado.
- **O formato 4 é incompatível com runtime de formato 3**, por desenho: o runtime velho recusa o
  documento novo em vez de executar `API_REQUEST` sem destino assinado.

## Provas

**Q0 (mutação 1 — destino vindo do payload):** trocando a resolução da rota por `json_field
"$payload" url`, o cenário 37 falha com a chamada real ao `http://169.254.169.254/latest/meta-data/`
— a mutação prova que o teste detecta o SSRF, e não só que o código novo está escrito.

**Q0 (mutação 2 — lista fora da linha canônica):** removendo `[%s]` das rotas na linha assinada, a
suíte falha já no cenário 1 (`poll da fila designada`): a config não confere com o pino e o nó não
opera. É a prova de que a lista **precisa** estar dentro da assinatura, e não só no payload.

**Q0 (mutação 3 — corte da vírgula no runtime):** trocando a quebra dos itens da lista de volta
por "qualquer vírgula" (em `json_list_sorted` e em `route_lookup`, separadamente), o cenário 41
falha: a chamada vai para `https://api.exemplo/x?ids=1` em vez de `https://api.exemplo/x?ids=1,2`
— ou seja, o nó teria chamado um caminho que ninguém assinou. A mutação prova que o cenário
detecta o corte, e não só que o parser novo está escrito.

**Q0 (mutação 4 — corte da vírgula no Control Plane):** trocando a quebra das entradas da
propriedade de volta por "qualquer vírgula", o teste `virgulaNaUrlNaoCortaARota` falha: a rota
`catalogo=https://api.exemplo/x?ids=1,2` entra na assinatura cortada, e a rota seguinte some
com ela.

**Harness `node-executor-test.sh`:** 163 asserções (139 em R107), com os cenários novos:

- **35** — rota autorizada → chamada à URL assinada, resultado com `status:200`, `bytes:16` e
  `digest`; o **corpo** da rota não volta; a credencial do nó não vai para o destino.
- **36** — rota fora da lista → `API_ROUTE_NOT_ALLOWED`, **nenhuma** requisição.
- **37** — o payload traz uma URL (`169.254.169.254`) → ela é ignorada; a rota assinada é a
  chamada.
- **38** — lista vazia → recusada, e `https://catalogo.exemplo/api` não é chamada.
- **39** — lista adulterada **com a assinatura original** → o nó recusa a config ("assinatura da
  config não confere com o pino"), não faz poll e não chama o destino adulterado.
- **40** — payload sem rota e método `POST` → `TASK_PAYLOAD_INVALID`, sem chamar rota nenhuma.
- **41** — rota com vírgula na query (`https://api.exemplo/x?ids=1,2`) → a chamada sai com a
  query **inteira** e a config é aceita (a linha canônica reconstruída confere com a assinatura).
  É o cenário que fixa o corte de texto: sem ele, o nó obedeceria a permissão assinada para um
  destino diferente.

**Suíte `node-runtime-test.sh`:** 49 asserções, sem mudança (o doctor não ganhou caminho novo).

**Testes Java (signer e config):** `NetworkConfigSignerTests` — assinatura quebra quando a rota
troca por outra (`169.254.169.254`) e quando uma rota é acrescentada; a lista vazia não muda a
forma da linha canônica (`[]` e o `term` por último). `NetworkConfigServiceTests` — a rota
declarada vai no payload **e** na assinatura, uma rota trocada quebra a verificação Ed25519; sem
rota declarada a lista é vazia; rota malformada é descartada; a vírgula na query **não** corta a
rota; nome fora do conjunto do runtime é descartado; destino que não é http(s) é descartado;
destino com caractere que quebra o JSON é descartado; nome repetido com outro destino descarta o
duplicado. `NetworkNodesTests` — a resposta da config traz `allowedRoutes` como array (vazio por
padrão).

**Suíte completa:** 2688 testes antes desta rodada; com os testes novos, **2698** — 0 falhas, 0
erros, 3 skipped. **Nenhuma migração de banco nesta rodada**: a rota não precisa de coluna, porque a
permissão vive no documento assinado, não no banco — e ficar no banco seria justamente o desenho
rejeitado. **V290 continua livre.**

**Prova de interop entre as duas pontas:** para a mesma lista com vírgula na query, a linha canônica
montada pelo signer (Java) e a reconstruída pelo runtime (bash) são **byte a byte iguais**:

```
4|40|nd_exec1|["NODE_API"]|1|1024|1024|10|["API_REQUEST"]|["acervo=https://acervo.exemplo/api","busca=https://api.exemplo/x?ids=1,2"]|2026-11-12T12:00:00Z|300|2
```

É essa igualdade que faz a assinatura conferir, e ela é a razão de as entradas serem ASCII: o
signer ordena por unidade de código UTF-16 e o runtime por byte UTF-8, e fora do ASCII as duas
ordens divergiriam.
