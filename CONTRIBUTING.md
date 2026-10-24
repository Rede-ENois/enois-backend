# Como contribuir

Obrigado pelo interesse. Este é o backend do **Énóis** (Spring Boot 4, Java 25, Maven). O frontend
fica no repositório `enois-frontend` — o contrato entre os dois é a API, e mudar um lado sem o outro
é a forma mais comum de quebrar a tela de alguém.

Antes de começar: leia o [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md). Falha de segurança **não** vira
issue — o canal está no [`SECURITY.md`](SECURITY.md).

## Verificando o ambiente local

Antes de iniciar o backend, execute o diagnóstico de pré-requisitos:

```bash
bash scripts/dev-doctor.sh
```

O Dev Doctor verifica Git, Java/JDK 25, Maven Wrapper e a porta 8081.
Docker é opcional para o desenvolvimento com H2 em memória.

- `[PASS]`: requisito encontrado.
- `[WARN]`: possível limitação que não impede o desenvolvimento.
- `[FAIL]`: requisito essencial ausente ou incompatível.

O comando retorna código `0` quando não há falhas essenciais e `1`
quando o ambiente precisa de ajustes.

Para executar os testes do próprio diagnóstico:

```bash
bash scripts/tests/dev-doctor-test.sh
```

O diagnóstico não instala dependências, não modifica o ambiente e não
acessa a infraestrutura de deploy do projeto.

## Rodando

```bash
./mvnw spring-boot:run
```

O perfil `dev` usa H2 em memória e aplica todas as migrações Flyway na subida. Para servir o front
local, suba com o contexto ligado:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8081 --server.servlet.context-path=/api"
```

## Antes de abrir a PR

```bash
./mvnw -DskipTests compile       # compila
./mvnw -Dtest=NomeDaClasse test  # os testes do que você mexeu
./mvnw test                      # a suíte inteira, quando o escopo pedir
```

Se **outra pessoa estiver compilando na mesma cópia** do repositório, rode os testes num
`git worktree` separado (`git worktree add /tmp/alguma-coisa HEAD`): os dois processos brigam pelo
mesmo `target/` e o erro que aparece ("cannot find symbol" em arquivo alheio) não é do seu código.

## Arquitetura

Pacote por funcionalidade, em três camadas: `domain/` (regras e records), `application/` (+
`application/port/out` para as portas), `adapters/in/web` e `adapters/out/persistence`. Um módulo novo
é um pacote novo — não um serviço novo, não um banco novo.

Regras que valem em toda parte:

- **Autorização no backend, sempre.** `currentUserResolver.resolve(...)` **lança** quando não há
  sessão (é o certo para escrita); o `viewerId` tolerante existe só para leitura pública, onde a
  ausência de sessão não é erro. Escolher o errado é como as telas ficam "meio quebradas".
- **Idempotência nas escritas** (`Idempotency-Key` + hash): repetir a requisição não pode criar duas
  linhas nem debitar duas vezes.
- **Erro com código**: `ApiException(status, "CODIGO", "mensagem em português")`. O front decide o que
  mostrar pelo código — mensagem genérica esconde a causa de quem está usando.
- **Limite e validação no servidor.** Tamanho de campo, faixa de valor e propriedade do recurso são
  conferidos aqui, não na tela.
- **Nada de dado pessoal em log.** Nem e-mail completo, nem token, nem conteúdo privado.

## Migrações

- Versionadas (`V75__assunto.sql`), aditivas sempre que possível, e **compatíveis com o que já está
  aplicado**: uma migração aplicada não se edita — o checksum muda e o deploy para. Precisa corrigir?
  Escreva a próxima.
- `create table if not exists`, sem chave estrangeira, com `engine=InnoDB default charset=utf8mb4
  collate=utf8mb4_uca1400_ai_ci` explícito (a lição das collations mistas).
- Cuidado com `varchar` grande: o teto de linha do InnoDB (65.535 bytes) estoura com poucas colunas
  de 4.000 caracteres em utf8mb4 — texto longo é `text`/`longtext`.
- Migração que muda dados existentes precisa dizer no comentário o que acontece com as linhas atuais.
- Valide contra **MariaDB de verdade** quando mexer em schema (o `docker-compose.yml` do repositório
  serve): o H2 dos testes aceita coisas que o MariaDB recusa, e vice-versa.

## Testes

- HTTP com `MockMvc` + `TestAuthHelper` (cria a conta, confirma o e-mail e devolve a sessão):
  é o padrão para fluxo, permissão e contrato.
- `TestDatabase` para montar estado que só existe por e-mail (conta verificada, 2FA ligado).
- O banco de teste é **um só para a classe**: um teste não pode contar com o catálogo inteiro nem
  supor que a tabela está vazia. Use um marcador próprio (uma tag, um id) quando precisar isolar.
- Teste que não pode rodar sem internet (DNS, provedor externo) precisa de dublê ou de IP literal —
  a suíte não depende de rede.

## Commits e PRs

- Mensagem no imperativo com escopo: `feat(selos): vários selos por pessoa, e a lista de quem marcou`.
  O corpo conta o que estava errado e o que muda — o diff já mostra o como.
- Migração entra no mesmo commit que a usa, com o número no corpo da mensagem.
- Descreva **como você verificou**: qual teste, quantos passaram, e o que foi conferido à mão.
- Nunca suba segredo, credencial ou `.env` preenchido. Configuração sensível vem de variável de
  ambiente (a lista está no README).

## Deploy

O deploy é por imagem no ECR + manifesto no GitOps (não é `fly deploy`). O fluxo, as pendências de
infraestrutura e as ações manuais estão no README.
