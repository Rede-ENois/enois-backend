# Réplica Java isolada

O perfil Maven `replica` empacota um JAR executável adicional. Sua
configuração Spring importa explicitamente o servidor HTTP e as sondas.
Por padrão escuta em `127.0.0.1:8081`. `SERVER_PORT` configura a porta,
`SERVER_ADDRESS` aceita somente loopback, e o nome de configuração Spring
é `replica` por padrão.

```sh
./mvnw -Preplica package
java -jar target/rede-0.0.1-SNAPSHOT-replica.jar
```

O assembly isolado serve `GET /health` para liveness do processo,
`GET /version` para os metadados públicos do build e `GET /readyz`
para prontidão verificada. O contexto inicia
sem repositórios, pool JDBC, Flyway, agendamento, control plane legado
ou provedores de assinatura. O JAR principal continua executando
`RedeApplication` e serve o ambiente legado.

## Contratos de leitura

`REDE_REPLICA_CONTEXT_SOCKET` aponta para o socket Unix privado do agente.
A réplica exige HTTPS no listener local quando esse parâmetro está
configurado. A réplica confere o SHA-256 do próprio JAR contra o snapshot
do agente e consulta o serviço confiável de dados. `/readyz` devolve a
versão atribuída quando a dependência autentica o grant e confirma o
protocolo de dados. Sem contexto válido, a prontidão e as operações de
dados retornam 503.

As leituras `GET /feed/posts/{postId}` e `GET /auth/me` reusam as regras
autoritativas do backend. `POST /feed/posts` envia o comando autoritativo
com a chave de idempotência. Escritas por cookie exigem
`X-CSRF-Protection: 1`. `REDE_REPLICA_CORS_ORIGINS` aceita uma lista de
origens HTTPS explícitas separadas por vírgulas. Uma falha de autenticação
do workload resulta em 503. Um 401 do usuário só chega ao navegador após
uma resposta autenticada do protocolo de dados.

As rotas de sessão do J6 são `POST /auth/login`, `POST /auth/2fa/verify`,
`POST /auth/refresh` e `POST /auth/logout`. O serviço de dados reaproveita
o limite e as regras de autenticação do backend legado. A réplica só
aceita o endereço de ingresso quando recebe a prova do agente pelo túnel.
Login e renovação mantêm os valores `Set-Cookie` separados.

As rotas de cadastro incluem `GET /auth/config`, `GET /auth/signup-challenge`,
`POST /auth/signup`, `POST /auth/email/verify` e `POST /auth/email/resend`.
O serviço de dados aplica as regras de cadastro e os desafios do backend,
preserva a chave de idempotência e envia a confirmação por SMTP. O ambiente
distribuído habilita o cadastro depois de configurar o remetente. A réplica
devolve os cookies de sessão emitidos na confirmação do e-mail.

## Serviço Java de dados

Para o aceite em staging, veja [MariaDB para o serviço de dados no J6](j6-mariadb.md).

```sh
./mvnw -Preplica,dados-distribuidos package
java -jar target/rede-0.0.1-SNAPSHOT-dados.jar
```

O operador configura `SPRING_PROFILES_ACTIVE`, a conexão autorizada ao
banco e `REDE_DADOS_CHAVE_ARQUIVO`, que aponta para a chave privada de
dados do CP com modo 0600. O serviço aceita HTTPS ou um listener local
em loopback atrás de uma origem HTTPS confiável. API v1 inclui
`/internal/data/v1/ready`, `/internal/data/v1/session` e
`/internal/data/v1/posts/{postId}`. O comando privado
`POST /internal/data/v1/posts` reutiliza a publicação autoritativa,
o replay durável e a mesma chave de rota já usada pelo backend.
O servidor verifica assinatura,
audiência, papel, app e expiração em cada chamada. Flyway e os pools SQL
ficam nesse processo. O rollout precisa manter um proprietário de
migrações enquanto o runtime legado estiver disponível.

A revogação é lida no mesmo armazenamento de sessões em cada chamada.
O serviço de dados pode processar a outbox e sua retenção com
`REDE_DADOS_OUTBOX_ENABLED=true`. A ativação pertence ao corte de autoridade
de jobs, quando o agendador anterior já foi drenado. Os claims e recibos
continuam com o worker existente. Fan-out e os demais jobs e integrações
seguem como etapas da migração.
