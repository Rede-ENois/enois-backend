# Testes

## Stack de Testes

- **JUnit 5** — Framework de testes
- **Spring Boot Test** — Test slices (@WebMvcTest, @DataJpaTest)
- **MockMvc** — Testes de controllers
- **H2 in-memory** — Banco para testes de persistence

## Executar Testes

```bash
# Todos os testes
./mvnw test

# Testes de um módulo específico
./mvnw test -Dtest="com.socialcoisa.rede.feed.*"

# Teste específico
./mvnw test -Dtest="FeedControllerTests"

# Com cobertura
./mvnw verify
```

Na CI, todo PR roda a suíte inteira (job `test-suite` em `.github/workflows/build-and-push.yml`).
O build da imagem usa `-DskipTests` e o schema check só sobe o contexto, então é esse job que pega
teste quebrado antes de chegar na `qa`. Ele não roda no push de `qa`/`main` e não segura deploy.

Envio que roda em pool de threads (push) não é verificado de forma síncrona: o teste troca o
executor por um que roda na hora (`@MockitoBean(name = "pushExecutor")` + `doAnswer`) ou usa
`verify(..., timeout(...))`. Um `verify` direto passa ou falha conforme a corrida.

The daily presence of an action is also recorded on an executor after the commit
(`presenceExecutor`). A test that reads the presence right after the action replaces it with an
inline one (`@MockitoBean(name = "presenceExecutor")`, as `StreakPresenceFlowTests` does); a test
that counts coin entries of someone who just acted leaves the `presenca:` entries out.

Concurrency tests (several threads sending the same requests at once) follow
`PostCounterConcurrencyTests`; see [`CONCURRENCY.md`](CONCURRENCY.md) for what H2 cannot reproduce.

`CommunityProjectNotificationTests` dirige o worker real por ids concretos, com relógio fixo.
As fixtures de projetos ficam agendadas para esse relógio dentro da transação que grava o evento:
workers de outros contextos compartilham o H2 e não podem disputar seus claims. Avisos, recibos,
continuações e preferências dos assinantes do cenário são limpos mesmo quando uma asserção falha.

`OutboxWorkerTests` e `OutboxRetentionWorkerTests` usam bases H2 próprias para impedir que workers
de outros contextos disputem os claims. Nesses contextos, a ponte de fixtures `TestDatabaseSupport`
é substituída para não redirecionar o helper estático de autenticação; os repositórios e workers
continuam reais.

A economia automática fica desligada em `src/test/resources/application.properties`: um cron
não pode alterar a cotação compartilhada entre asserções de carteira e serviços. Os cenários de
`CoinEconomyIsolationTests` chamam os dois jobs reais em um H2 próprio para garantir esse contrato;
`CoinEconomyServiceTests` mantém a cobertura da publicação habilitada com relógio controlado.
Isso não altera a configuração de produção.

## Estrutura de Testes

Os testes estão em `src/test/java/com/socialcoisa/rede/` e seguem a mesma estrutura de pacotes do código fonte.

### Testes de Controller (Integration)

Cada controller principal possui uma classe de teste:

| Arquivo | Controller testado |
|---|---|
| `FeedControllerTests.java` | Feed endpoints |
| `AuthProfileFlowTests.java` | Fluxo auth + profile |
| `MediaControllerTests.java` | Media endpoints |
| `MessagingContractControllerTests.java` | Messaging endpoints |
| `NetworkContractControllerTests.java` | Network endpoints |
| `NotificationContractControllerTests.java` | Notification endpoints |
| `ProfileControllerTests.java` | Profile endpoints |
| `SearchContractControllerTests.java` | Search endpoints |

### Testes Compartilhados

**ContractControllerTests.java**: Testes de contrato que validam:
- OpenAPI expõe todos os paths dos contratos
- Páginas vazias retornam arrays vazios
- `X-Request-Id` é ecoado na resposta
- Operações idempotentes são replayed

**TestAuthHelper.java**: Helper para autenticar usuários em testes (signup → retorna token + userId).

O banco H2 é compartilhado entre classes. Cenários que executam rotinas globais precisam limpar os
bloqueios de IP que a rodada inseriu, inclusive quando uma asserção falha, sem remover bloqueios
anteriores. `CenarioAutoBan` fornece uma origem sintética por cenário e registra esses bloqueios
para limpeza no `@AfterEach`. Sanções, revogações de sessão e sanitizações globais não são desfeitas.
A regressão confere a aplicação do controle, a preservação de estado anterior e a autenticação de
um cenário seguinte. O worker real e as regras de produção permanecem intactos.

### Teste de Contexto

**RedeApplicationTests.java**: Testa se o contexto da aplicação carrega.

## Padrões de Teste

O [fluxo de marketplace com cotação explícita](TESTING_MARKETPLACE_COTACAO.md) descreve os valores,
as fronteiras de commit HTTP e o isolamento das fixtures financeiras.

### TestAuthHelper

```java
var helper = new TestAuthHelper(mockMvc, objectMapper);
var auth = helper.authenticate("conta_do_cenario");
// auth.token() -> token de sessão
// auth.userId() -> ID do usuário
```

Para cenários que precisam distinguir clientes, `authenticate(nome, RequestPostProcessor)` aplica o
mesmo processador ao cadastro, à confirmação e ao login. A sobrecarga sem processador mantém o
comportamento padrão. A origem do cliente é dado da fixture, não um controle desligado.

### Teste de Controller (MockMvc)

```java
@Test
void listaPublicacoesDoCenario() throws Exception {
    var auth = new TestAuthHelper(mockMvc, objectMapper).authenticate("feed_cenario");

    mockMvc.perform(get("/feed/posts")
            .header("X-Session-Token", auth.token()))
        .andExpect(status().isOk());
}
```

## Cobertura

```bash
# Gerar relatório de cobertura (target/site/jacoco/)
./mvnw verify
```
