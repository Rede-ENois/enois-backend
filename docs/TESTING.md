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

### Teste de Contexto

**RedeApplicationTests.java**: Testa se o contexto da aplicação carrega.

## Padrões de Teste

### TestAuthHelper

```java
var auth = TestAuthHelper.signup("Teste", "teste@email.com", "senha123");
// auth.token() -> token de sessão
// auth.userId() -> ID do usuário
```

### Teste de Controller (MockMvc)

```java
@Test
void shouldReturnFeed() {
    var auth = TestAuthHelper.signup(...);

    mockMvc.perform(get("/api/feed")
            .header("Authorization", "Bearer " + auth.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isArray());
}
```

## Cobertura

```bash
# Gerar relatório de cobertura (target/site/jacoco/)
./mvnw verify
```
