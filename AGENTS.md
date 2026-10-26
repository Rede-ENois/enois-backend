# AGENTS.md — contrato operacional para agentes de IA

Inspirado no contrato do [KofLang](https://github.com/KofLang/Kof4j) (AGENTS.md), destilado para a
realidade deste projeto. **Este arquivo é lido por todo agente de IA antes de trabalhar aqui** — os
regras valem para agentes autônomos, sessões assistidas e contribuições humanas que usam IA.

## Papel e autoridade

- **IA acelera a implementação; IA não define arquitetura, produto nem política.**
- Design, prioridades e merge para a branch de produção são decisão dos mantenedores.
- Fluxo de entrega: **feature → `qa` → `main`** (ver [`docs/GITFLOW.md`](docs/GITFLOW.md)). PR precisa
  de aprovação de alguém que não empurrou o código.
- Toda mudança relevante tem origem rastreável (issue, tarefa do backlog ou pedido dos mantenedores).
- **Toda entrega tem prova**: teste executado, verificação de tela ou sonda de produção. Concluído
  significa comprovado, não pretendido.

## Estado do projeto — ler antes de trabalhar

1. `git fetch` + `git log` recente: **confira que outro agente não está fazendo a mesma coisa**
   (branches, PRs abertos, commits de hoje). Trabalho duplicado já aconteceu — checar antes.
2. [`docs/BACKLOG.md`](docs/BACKLOG.md) — fila priorizada com critério de aceite.
3. [`docs/GITFLOW.md`](docs/GITFLOW.md), [`docs/TESTING.md`](docs/TESTING.md),
   [`docs/SECURITY.md`](docs/SECURITY.md) — fluxo, testes e segurança.
4. [`CONTRIBUTING.md`](CONTRIBUTING.md) — arquitetura e regras de código.

## Ciclo de trabalho

1. Ler o estado (acima).
2. Escolher a tarefa de maior valor que ninguém reivindicou.
3. Implementar **um escopo completo** — nada de meio-corte, nada de stub "por enquanto".
4. Testar: no mínimo os testes do que mexeu; a suíte inteira quando o escopo pedir.
5. Commit com mensagem **neutra e curta** (ver segurança abaixo).
6. Push na sua branch e PR para `qa`. Nunca termine com trabalho não commitado.
7. Antes de commitar: `git fetch`, reconcilie com o remoto, procure marcadores de conflito
   (`<<<<<<<`) nos seus arquivos, rode os testes de novo na árvore final.

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
- **Criptografia não abre PR para a `qa`.** O trabalho de criptografia pós-quântica e proteção de
  dados vive na branch **`sec/cripto-pq`** (nos dois repositórios), e todo PR dele tem essa branch
  como base — **nunca `qa`**. A promoção só acontece com o checklist do
  [`docs/PQ-CRYPTO-PLAN.md`](docs/PQ-CRYPTO-PLAN.md) cumprido por inteiro: migração verificada
  (decifra == claro), rotação ensaiada, zero falha de decifra, suíte completa verde e revisão de
  segurança registrada. Já aconteceu de a base do PR não trocar e o merge cair no lugar errado —
  **confira a base antes de mergear**.

## Qualidade (gates)

- **Regra do escoteiro: toda falha é corrigida quando encontrada.** Teste quebrado ou instável,
  build quebrado, erro de lint ou de tipo, bug ou falha de segurança que você encontrar é corrigido
  na mesma tarefa, mesmo que seja anterior à sua mudança. "Já falha na `main`" não é motivo para
  deixar como está. Se o conserto estiver realmente fora de alcance (depende de decisão dos
  mantenedores, de acesso à infraestrutura, ou é grande demais para a tarefa), registre na mesma
  tarefa (issue ou item do backlog) e diga na PR — nunca deixe em silêncio.
- **Zero regressão.** Bug corrigido ganha teste de prova que falharia antes do conserto.
- **Sem stub, sem TODO fingindo conclusão, sem asserção enfraquecida, sem fallback silencioso.**
  Comportamento não suportado responde com erro explícito, não com "talvez funcione".
- **Evidência antes de inferência.** "Desconhecido" em vez de "provavelmente". Nunca invente API,
  sintaxe, comportamento ou resultado — verifique no código-fonte.
- **O compilador e os testes mandam sobre a memória.** Sintaxe incerta? Compile primeiro.
- A suíte do escopo é o mínimo, não o teto.
- Isolamento de teste: a base H2 é **compartilhada** entre classes — teste só afirma sobre os
  próprios dados (fixtures com prefixo único), nunca sobre contagem ou listagem global.

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

## Multiagente

- O repositório é trabalhado por vários agentes em paralelo. **Nunca empurre trabalho alheio**, nunca
  force uma branch de outro, nunca dispute a mesma cópia de trabalho.
- Compilar/testar em paralelo? Use `git worktree` separado (`git worktree add /tmp/x HEAD`) — os
  processos brigam pelo mesmo `target/`.
- Se o push falhar porque o remoto andou, **reconcilie com o conteúdo atual** (preserve os dois
  lados), nunca sobrescreva.

## Documentação

- `docs/` descreve o que **existe** (implementado e verificado). Plano não implementado fica em
  issue ou na seção de planejado — documento de plano não substitui implementação.
- Decisão tomada em conversa vira documento no mesmo commit que a implementa.
- Código mudou? A doc do módulo muda junto no mesmo PR.

## Contribuindo com IA

Veja a seção "Contribuições com IA" no [`CONTRIBUTING.md`](CONTRIBUTING.md). Resumo: declare o uso,
entenda cada linha que submete, entregue prova, e a revisão humana antes do merge continua sendo a
regra.
