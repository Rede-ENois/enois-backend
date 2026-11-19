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

### Processos de mídia em Windows e Linux

`MediaFfmpegRunnerTests` e `MediaFfmpegTranscodeTests` usam `FfmpegProcessoDeTeste`: o `jpackage`
do JDK 25 gera launchers nativos com uma JVM mínima (`java.base`), sem instalar ffmpeg nem executar
scripts de shell. O runner de produção continua lançando processos reais. Os cenários registram os
argumentos, gravam a saída, devolvem a duração, falham ou bloqueiam até o timeout/interrupção.
É necessário um JDK completo com `jpackage` e `jlink`, não apenas um JRE. Os launchers e seus
cenários ficam em `@TempDir`, com limpeza automática também verificada; não há skip por plataforma.

Para simular indisponibilidade de temporários, os testes criam um arquivo comum em `@TempDir` e
usam esse arquivo como pai de diretórios inválidos. Não dependem de `/dev/null`, permissões POSIX,
privilégios do usuário nem alterações globais em `java.io.tmpdir`.

### Outbox e economia controladas

`CommunityProjectNotificationTests` dirige o worker real por ids concretos, com relógio fixo.
As fixtures de projetos ficam agendadas para esse relógio dentro da transação que grava o evento:
workers de outros contextos compartilham o H2 e não podem disputar seus claims. Avisos, recibos,
continuações e preferências dos assinantes do cenário são limpos mesmo quando uma asserção falha.

`OutboxWorkerTests` e `OutboxRetentionWorkerTests` usam bases H2 próprias para impedir que chamadas
de outros contextos disputem os claims. Nesses contextos e em `MediaDeletionWorkerTransactionTests`,
a ponte de fixtures `TestDatabaseSupport` é substituída para evitar a associação estática durante
o boot. O `TestDatabaseContextListener` associa o helper ao contexto que está efetivamente em teste
e o reassocia ao retornar a outro contexto em cache; os repositórios e workers continuam reais.

**Estado global de rede precisa ser devolvido.** A base H2 é compartilhada entre classes: quem
deixa um estado que muda o resultado de outro (aqui, uma região declarada fora de serviço, que
zera a capacidade e o despacho) precisa limpá-lo **ao fim** do próprio teste, não só no começo.
Limpar no `@BeforeEach` deixa o resíduo para a classe que roda depois. `NetworkRegionTests` e
`NetworkReplicationGeographicTests` são os exemplos: `network_region_states` é estado global, e
a declaração sobrevivia ao fim da classe em ambas.

Para reproduzir um vazamento assim, lembre que a ordem padrão do JUnit não é a ordem do arquivo:
o resíduo só aparece se o método que **declara** o estado rodar por último — e métodos sem
`@Order` (default 1000) rodam depois de todos os anotados, com o próprio `@BeforeEach` deles
apagando o resíduo. Ordene explicitamente **todos** os métodos antes de julgar.

A economia automática fica desligada em `src/test/resources/application.properties`: um cron
não pode alterar a cotação compartilhada entre asserções de carteira e serviços. Os cenários de
`CoinEconomyIsolationTests` chamam os dois jobs reais em um H2 próprio para garantir esse contrato;
`CoinEconomyServiceTests` mantém a cobertura da publicação habilitada com relógio controlado.
Isso não altera a configuração de produção.

## Estrutura de Testes

`NetworkMediaTests` preserva os assets de outras classes: os assets do cenário têm UUID próprio
e uma remoção global viola a FK de jobs de exclusão alheios. `NetworkReplicaTests` posiciona as
datas somente das suas contas após o maior `created_at` persistido, antes de capturar ou avançar
o cursor. Assim a travessia não depende de fixtures futuras de outras classes. As datas originais
são restauradas em `@AfterEach`. O @ da conta banida é capturado antes do banimento: consultar
`/me` depois usa uma sessão que deve estar revogada. Paginação, autorização e contagens SQL
continuam com as mesmas asserções e o servidor não muda.

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

### Isolamento das varreduras globais

A base H2 padrão é compartilhada entre contextos. Testes de workers que varrem contas e aplicam
sanções não podem consumir as fixtures de outras classes. `SuspiciousAutoBanTests` usa uma base
própria com `DB_CLOSE_DELAY=0` e descarta o contexto depois de cada método. Assim, o bloqueio real
por repetição de origem continua coberto, sem deixar contas punidas ou origens bloqueadas para
outros cenários. Não basta desligar o agendamento: `sweep()` continua executando a política completa.

O `TestDatabaseContextListener`, registrado em `src/test/resources/META-INF/spring.factories`,
reassocia o helper estático ao `JdbcTemplate` do contexto atual na preparação da instância (antes
do `@BeforeAll` de instância) e antes de cada método, inclusive quando o Spring retorna a um
contexto em cache após fechar uma base isolada. Contextos sem JDBC não precisam dessa associação.
A instalação inicial por `TestDatabaseSupport` permanece disponível durante a criação do contexto.

Prova de regressão entre classes, no mesmo processo e com reutilização do contexto:

```bash
./mvnw -DforkCount=1 -DreuseForks=true -Dsurefire.runOrder=alphabetical \
  '-Dtest=MarketplaceSanitizeBehaviorTests#theSanitizingPassRemovesOnlyTheLeftoversAndKeepsHealthyListings,BulkBanAndSuspiciousTests#approvedModeratorCanSeeAndRemoveSuspiciousPostsInBulk,SuspiciousAutoBanTests#bansAndSanitizesAnAccountAboveTheRiskCeiling,ProfileControllerTests#viewsPublicProfile' test
```

Além do descarte da base, `CenarioAutoBan` fornece uma origem sintética por cenário e registra os
bloqueios criados pela rodada para limpeza no `@AfterEach`, inclusive se uma asserção falhar, sem
remover bloqueios anteriores. A regressão `naoContaminaAutenticacaoDepoisDeFixturesDeOutrasClasses`
semeia explicitamente o bloqueio anterior e confere aplicação do controle, preservação desse estado
e autenticação de um cenário seguinte. Os testes de duas e três contas cobrem os dois lados do
limiar, inclusive recusa de cadastro e login. A limpeza não desfaz sanções, revogações de sessão ou
sanitizações; essas persistem até o descarte do H2. Worker e regras de produção permanecem intactos.

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

## Workers, relógio e isolamento

O classpath de teste registra `AgendamentoManualDeTesteConfig`: quando
`spring.task.scheduling.enabled=false`, o `NoOpTaskScheduler` mantém os workers sob controle
explícito dos cenários. Isso não desativa seus métodos, regras ou validações. O agendamento da
aplicação produtiva não foi alterado; essa configuração existe somente em `src/test`.

Os testes da outbox, retenção da outbox e transação de exclusão de mídia usam bases H2 próprias.
Assim, outro contexto não consome seus eventos nem preenche o lote com fixtures alheias.
O relógio desses cenários é fixo e as datas de elegibilidade são derivadas dele. Um job que deve
estar vencido nasce antes do instante atual do cenário, não exatamente no limite sujeito à
precisão da coluna do banco. As verificações de recibo, backoff, lote e transação continuam ativas.

Os cenários que dependem de notificações da outbox usam `ProcessadorOutboxDeTeste` depois da
resposta HTTP. Ele seleciona o evento por tipo e agregado e chama o worker real, sem consumir
a fila inteira. O retorno do worker confirma a tentativa; as asserções das notificações
comprovam os efeitos de negócio. `MonitoringTests` chama o `flush` real e verifica a gravação
do marcador exclusivo no arquivo, sem aguardar o agendador nem remover a redação de dados.

```bash
./mvnw -Dtest=AgendamentoManualDeTesteTests,OutboxWorkerTests,OutboxRetentionWorkerTests,MediaDeletionWorkerTransactionTests test
./mvnw -Dtest=GroupNotificationsTests,PersonalizationSurfaceAuditTests,MonitoringTests test
```

## Cobertura

```bash
# Gerar relatório de cobertura (target/site/jacoco/)
./mvnw verify
```
