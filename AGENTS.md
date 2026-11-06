# AGENTS.md — contrato operacional para agentes de IA

Destilado do contrato do [KofLang](https://github.com/KofLang/Kof4j) para a realidade deste
repositório. **Este arquivo é lido por todo agente de IA antes de trabalhar aqui** — as regras valem
para agentes autônomos, sessões assistidas e contribuições humanas que usam IA.

Se uma regra deste arquivo conflitar com a pressa, a pressa perde.

---

## Estado

last: contrato endurecido (2026-11-06) — gates Q0–Q7, autoverificação obrigatória, design system
como dependência de contrato.

state: ativo

intent: nenhum-slop-em-release

constraint:

- qualidade-primeiro
- nenhum-bug-em-release
- sem-stubs
- zero-regressão
- compilar-antes-da-entrega
- testar-com-a-mudança
- evidência-antes-de-inferência
- mantenedores-controlam-arquitetura
- mantenedores-controlam-merge-na-produção
- sem-fallback-silencioso
- sem-api-inventada

---

## Papel e autoridade

- **IA acelera a implementação; IA não define arquitetura, produto nem política.**
- Design, prioridades e merge para a branch de produção são decisão dos mantenedores.
- Fluxo de entrega: **feature → `qa` → `main`** (ver [`docs/GITFLOW.md`](docs/GITFLOW.md)). PR precisa
  de aprovação de alguém que não empurrou o código.
- **Toda mudança relevante tem origem rastreável**: uma issue aberta com o plano de implementação,
  uma tarefa do backlog ou um pedido explícito dos mantenedores. PR sem origem é PR sem destino.
- **Toda entrega tem prova**: teste executado, verificação de tela ou sonda de produção. Concluído
  significa comprovado, não pretendido.

---

## Estado do projeto — ler antes de trabalhar

1. `git fetch` + `git log` recente: **confira que outro agente não está fazendo a mesma coisa**
   (branches, PRs abertos, commits de hoje). Trabalho duplicado já aconteceu — checar antes.
2. [`docs/BACKLOG.md`](docs/BACKLOG.md) — fila priorizada com critério de aceite.
3. [`docs/GITFLOW.md`](docs/GITFLOW.md), [`docs/TESTING.md`](docs/TESTING.md),
   [`docs/SECURITY.md`](docs/SECURITY.md) — fluxo, testes e segurança.
4. [`CONTRIBUTING.md`](CONTRIBUTING.md) — arquitetura e regras de código.
5. [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) — convivência. Vale para agente também.

---

## Ciclo de trabalho

1. Ler o estado (acima).
2. Escolher a tarefa de maior valor que ninguém reivindicou.
3. Implementar **um escopo completo** — nada de meio-corte, nada de stub "por enquanto".
4. Testar: no mínimo os testes do que mexeu; a suíte inteira quando o escopo pedir.
5. Commit com mensagem **neutra e curta** (ver segurança abaixo).
6. Push na sua branch e PR para `qa`. Nunca termine com trabalho não commitado.
7. Antes de commitar: `git fetch`, reconcilie com o remoto, procure marcadores de conflito
   (`<<<<<<<`) nos seus arquivos, rode os testes de novo na árvore final.

Regras do ciclo:

- **Nunca perguntar quando as evidências do repositório podem responder.** Leia o código, o teste, o
  log. Pergunta é para decisão de produto, não para fato que está no arquivo.
- **Nunca encerrar um turno com uma unidade não commitada.**
- **Nunca encerrar um turno apenas com um resumo** — resumo sem entrega é relatório de intenção.
- Exatamente um item do todo pode estar `in_progress`.
- **Concluído significa comprovado, não pretendido.**

Pare e reporte quando:

- a decisão exigir semântica de produto congelada (só os mantenedores decidem);
- houver colisão inevitável com a lane de outro agente;
- aparecer regressão externa sem explicação;
- o requisito depender de acesso à infraestrutura que você não tem.

---

## Gate de qualidade (Q0–Q7)

Fluxo de correção: **reproduzir → corrigir a causa-raiz → adicionar teste de prova → rodar os testes
relevantes → rodar a suíte → caçar casos-limite → commit → push.**

- **Q0 — causa-raiz.** Bug é corrigido na causa, não no sintoma. O teste de prova **falha no código
  antigo** e passa no novo. Teste que passa antes e depois não prova nada.
- **Q1 — cobertura.** Toda funcionalidade tem teste. Toda correção tem teste de regressão. Teste e
  implementação compartilham o commit. Refactor preserva a prova existente.
- **Q2 — compilação.** `./mvnw -DskipTests compile` verde **antes** do push. Sem exceção.
- **Q3 — casos-limite.** Cobrir quando aplicável: limite numérico, vazio/null, limites de tamanho,
  erro esperado, idempotência, concorrência, falha de recurso externo. **Só o caminho feliz é
  insuficiente.**
- **Q4 — caça.** Procurar ativamente: entrada extrema, fronteira do contrato, regressão em
  comportamento vizinho, comportamento não coberto.
- **Q5 — honestidade do verde.** Sem falso verde. Sem asserção enfraquecida. Sem `@Disabled`/skip
  escondido. Vermelho seu é corrigido ou revertido — nunca "deixa assim".
- **Q6 — a suíte é o mínimo, não o teto.**
- **Q7 — proibido.** Stub, placeholder, `TODO`/`FIXME` fingindo conclusão, implementação vazia,
  fachada que devolve `null`/`0`/lista vazia para esconder ausência, branch não tratada engolida,
  suporte fingido como "não implementado". Comportamento não suportado responde com **erro explícito
  e código próprio** (`ApiException(status, "CODIGO", "mensagem")`), nunca com "talvez funcione".

**Regra do escoteiro: toda falha é corrigida quando encontrada.** Teste quebrado ou instável, build
quebrado, erro de lint ou de tipo, bug ou falha de segurança que você encontrar é corrigido na mesma
tarefa, mesmo que seja anterior à sua mudança. "Já falha na `main`" não é motivo para deixar como
está. Se o conserto estiver realmente fora de alcance (depende de decisão dos mantenedores, de acesso
à infraestrutura, ou é grande demais para a tarefa), registre na mesma tarefa (issue ou item do
backlog) e diga na PR — nunca deixe em silêncio.

---

## Regras de segurança (não negociáveis)

- **O repositório é público e há atacante lendo.** Mensagem de commit e descrição de PR são neutras:
  diga *o que* mudou, **nunca detalhe *como* uma falha de segurança foi corrigida** (vetor, payload,
  regra de detecção). Quem precisa saber, sabe pelo canal fechado.
- **Segredo nunca em código, log, commit, PR ou relatório.** Token de sessão, chave, senha e dado
  pessoal (e-mail, IP, telefone, documento) não aparecem em lugar algum — nem em fixture de teste.
- **Nunca desative um controle de segurança para fazer teste passar** (guarda, rate limit, captcha,
  validação). Se o teste pede isso, o teste está errado.
- **Produção é intocável por agente:** nenhuma ação destrutiva, nenhuma escrita, nenhuma conta de
  teste. Validação de produção é sonda de leitura pública (`GET /api/version`, `GET /health`) e tela.
- **Push em `main` ou `qa` dispara deploy automático.** Não force push, não dispare workflow manual,
  não "conserte" pipeline. Se o deploy falhar, diagnostique pelo log de CI e reporte.
- **Migração de banco é a parte mais perigosa:** migração aplicada não se edita; siga as regras do
  [`CONTRIBUTING.md`](CONTRIBUTING.md) (charset/collation explícito, aditiva, testada). Deploy parado
  por migração ruim derruba o site inteiro — é o incidente mais caro deste projeto.
- **Autorização no backend, sempre.** `currentUserResolver.resolve(...)` **lança** sem sessão (é o
  certo para escrita); o `viewerId` tolerante existe só para leitura pública. Escolher o errado é
  como as telas ficam "meio quebradas" — e como um recurso privado vaza.

---

## Gate de código

- **Sem comentário narrando o óbvio.** Comentário existe para registrar uma restrição que o código não
  consegue mostrar (um motivo, um limite, uma armadilha). "Aqui somamos 1" é ruído.
- **Sem código morto, sem import órfão, sem variável não usada, sem `System.out`.**
- **Sem dependência nova sem justificativa na PR.** Biblioteca que já existe no projeto vence
  biblioteca nova; a plataforma vence a biblioteca.
- **Sem refatoração que a tarefa não pediu.** PR de feature não é PR de faxina.
- **Sem arquivo gerado em massa.** Nada de "criei 40 arquivos de scaffolding".
- **Tamanho:** alvo de ≤500 linhas por classe. 500–599 é dívida tolerada. **≥600 bloqueia o merge.**
  Divida por responsabilidade, nunca por sufixo numérico.
- **Idioma:** código, nomes, mensagens de erro e documentação em português, como o resto do
  repositório. Não misture idiomas no mesmo arquivo.
- **O compilador e os testes mandam sobre a memória.** Sintaxe incerta? Compile primeiro. Nunca
  invente API, assinatura, comportamento ou resultado — verifique no código-fonte.

---

## Design system (obrigatório)

A interface é construída sobre `@rede-enois/design-system`. Ele é **contrato**, não sugestão.

- **Nenhum componente de UI é reimplementado no front quando o design system já tem o equivalente.**
  Antes de criar, procure no pacote. Se falta, a mudança começa no `enois-design-system` — não num
  componente local que depois diverge.
- **Token, não valor mágico.** Cor, espaçamento, raio, tipografia e sombra vêm de token do tema. Hex
  solto, `px` arbitrário e `!important` para vencer o tema são proibidos.
- **Tema é dado, não código.** A aparência muda por variável de tema; componente não pode assumir um
  modo (claro/escuro) nem uma paleta fixa.
- **Contraste é requisito, não estética.** Todo par texto/fundo precisa passar WCAG AA nos dois modos
  e em todos os temas suportados. Componente novo entra com verificação de contraste.
- **Acessibilidade mínima:** elemento interativo é botão ou link de verdade, tem rótulo acessível
  (`aria-label` quando não há texto visível), foco visível e navegação por teclado.
- **Camadas do design system:** `domain → patterns → ui`. Um componente de `ui` não importa de
  `patterns`; nenhum deles busca dados, navega ou lê store. Se o componente precisa de dados, quem
  passa é o chamador.
- **Mudança no design system é mudança de contrato:** sobe versão, atualiza o consumidor no mesmo
  fluxo e prova que as telas que usam o componente continuam corretas.
- **Nada de estilo inline para resolver layout.** Se o layout quebra, o problema é a estrutura
  (grid/flex, `min-w-0`, largura mínima), não um `style=` de emergência.

---

## Verificação

```bash
./mvnw -DskipTests compile       # compilação
./mvnw -Dtest=NomeDaClasse test  # os testes do que você mexeu
./mvnw test                      # a suíte inteira, quando o escopo pedir
```

- **Nunca afirmar que está verde sem prova executada.** "Deveria passar" não é resultado.
- **Guarda ambiental não é regressão sua** — mas precisa ser dita na PR, com o motivo e a evidência
  de que o mesmo erro existe na base limpa.
- **Isolamento de teste:** a base H2 é **compartilhada** entre classes — teste só afirma sobre os
  próprios dados (fixtures com prefixo único), nunca sobre contagem ou listagem global.
- **Teste não depende de rede.** DNS, provedor externo e relógio precisam de dublê ou valor fixo.
- **Bug corrigido ganha teste de prova que falharia antes do conserto.**

---

## Commit e entrega

Antes do commit:

- Q0–Q7 satisfeitos;
- compilação verde;
- testes relevantes verdes;
- suíte completa executada quando o escopo pedir;
- árvore pós-rebase inspecionada (sem `<<<<<<<`, sem arquivo alheio);
- documentação do módulo atualizada no mesmo commit.

O commit:

- é uma unidade coesa e completa — implementação + prova juntas;
- tem mensagem no imperativo com escopo: `feat(selos): vários selos por pessoa`;
- o corpo conta **o que estava errado e o que muda**; o diff já mostra o como;
- migração entra no mesmo commit que a usa, com o número no corpo;
- **não** tem `Co-Authored-By`, "Generated with" nem nome de ferramenta.

A PR:

- referencia a issue de origem;
- descreve **como você verificou**: qual teste, quantos passaram, o que foi conferido à mão;
- declara o uso de IA no checklist do template;
- não mistura escopos: uma feature por PR.

---

## Multiagente

- O repositório é trabalhado por vários agentes em paralelo. **Nunca empurre trabalho alheio**, nunca
  force uma branch de outro, nunca dispute a mesma cópia de trabalho.
- Compilar/testar em paralelo? Use `git worktree` separado (`git worktree add /tmp/x HEAD`) — os
  processos brigam pelo mesmo `target/`.
- Se o push falhar porque o remoto andou, **reconcilie com o conteúdo atual** (preserve os dois
  lados), nunca sobrescreva. Nunca `checkout --ours` nem `--theirs` para "resolver" conflito.
- Antes de escolher número de migração ou de issue, `git fetch` e confira a ponta remota.

---

## Armadilhas conhecidas deste repositório

- **Filtros nascem antes do contexto completar.** Filtro que injeta bean de WebMvc/JPA no
  construtor derruba o boot inteiro (aconteceu). Em filtro, dependência pesada é `ObjectProvider`
  resolvido na primeira requisição.
- **Rotas fora da superfície respondem 200 com corpo vazio** (anti-enumeração). Teste novo que
  espera 404 de caminho inexistente deve esperar 200 vazio.
- O gateway tira o prefixo `/api`: controller serve na raiz, não em `/api/**`.
- O `currentUserResolver.resolve(...)` lança sem sessão (use para escrita); o `viewerId` tolerante é
  só para leitura pública.
- Collation do MariaDB de produção difere do H2: sempre explicitar `charset`/`collation` em DDL.
- **Hot rows e contadores:** contador muda por `UPDATE` relativo; leitura-decisão-escrita em linha
  compartilhada trava antes; linha pai é escrita antes da filha. Ver
  [`docs/CONCURRENCY.md`](docs/CONCURRENCY.md).

---

## Documentação

- `docs/` descreve o que **existe** (implementado e verificado). Plano não implementado fica em
  issue ou na seção de planejado — documento de plano não substitui implementação.
- Decisão tomada em conversa vira documento no mesmo commit que a implementa.
- Código mudou? A doc do módulo muda junto no mesmo PR.
- Documento que descreve comportamento que o código não tem é bug de documentação — corrija ou
  remova.

---

## Contribuindo com IA

Este projeto aceita e usa código escrito com ajuda de IA — com as mesmas regras de sempre e mais
algumas próprias. O contrato completo está em [`CONTRIBUTING.md`](CONTRIBUTING.md#contribuições-com-ia).

1. **Declare o uso.** Marque a caixa de IA no template da PR. Sem nome de ferramenta e sem linha de
   atribuição no commit.
2. **Quem submete responde pelo código.** Entenda cada linha antes de abrir a PR. Saída de IA colada
   sem revisão é a forma mais rápida de introduzir regressão sutil.
3. **Prova, não promessa.** "Deveria funcionar" não é verificação.
4. **Zero slop.** Sem comentário narrando o óbvio, sem arquivo gerado em massa, sem dependência nova
   sem justificativa, sem refatoração que a tarefa não pediu, sem abstração especulativa.
5. **Segurança vem primeiro.** As regras acima valem integralmente.
6. **Revisão humana antes do merge continua sendo a regra**, com aprovação de quem não empurrou.

---

## Autoverificação final

Antes de dizer que terminou, confira cada item. **Se qualquer um for falso, o estado é `não-pronto`.**

- [ ] compilou
- [ ] testou (escopo) e rodou a suíte quando o escopo pediu
- [ ] causa-raiz corrigida, não o sintoma
- [ ] teste de prova falha no código antigo
- [ ] casos-limite relevantes cobertos
- [ ] sem falso verde, sem asserção enfraquecida, sem skip escondido
- [ ] sem stub, sem placeholder, sem TODO fingindo conclusão
- [ ] sem segredo, sem dado pessoal, sem detalhe de correção de segurança no texto público
- [ ] sem comentário de ruído, sem código morto, sem dependência injustificada
- [ ] design system respeitado (token, componente existente, contraste, acessibilidade)
- [ ] documentação do módulo atualizada no mesmo commit
- [ ] árvore limpa, sem marcador de conflito, sem trabalho alheio
- [ ] remoto sincronizado

`if_any_false: state: não-pronto — next: corrigir-antes-da-entrega`
