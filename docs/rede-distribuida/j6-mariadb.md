# MariaDB para o serviço de dados no J6

Para o aceite em staging, provisione uma instância MariaDB 11.4 com
armazenamento persistente e um schema dedicado ao J6. O serviço Java de
dados mantém a conexão JDBC, executa o Flyway e atende as réplicas pela
API privada HTTPS. Permita acesso à porta MariaDB a partir do host do
serviço de dados. A rede das réplicas e dos agentes deve alcançar a API
HTTPS e ter acesso à porta SQL bloqueado.

## Banco e usuário

Use a linha 11.4 empregada pelo [check de schema do repositório](../../.github/workflows/build-and-push.yml).
Crie o schema com `utf8mb4_uca1400_ai_ci`. A tabela `users` da V1
herda a collation do schema e a V165 compara seus IDs com as tabelas
de serviços da V74, que declaram essa collation explicitamente.

No console administrativo do MariaDB, substitua a origem pelo endereço
do serviço de dados conforme visto pelo banco, incluindo qualquer NAT.
Gere a senha no gerenciador de segredos do ambiente.

```sql
CREATE DATABASE enois_j6 CHARACTER SET utf8mb4 COLLATE utf8mb4_uca1400_ai_ci;

CREATE USER 'enois_j6_data'@'<origem-do-servico-de-dados>'
  IDENTIFIED BY '<senha-gerada>' REQUIRE SSL;

GRANT ALL PRIVILEGES ON enois_j6.* TO 'enois_j6_data'@'<origem-do-servico-de-dados>';
```

Esse grant cobre as operações de aplicação e as migrações no schema
dedicado. Execute o primeiro boot com uma única instância do serviço de
dados para manter um proprietário do Flyway.
[Contas MariaDB](https://mariadb.com/docs/server/reference/sql-statements/account-management-sql-statements/create-user) ·
[Privilégios MariaDB](https://mariadb.com/docs/server/reference/sql-statements/account-management-sql-statements/grant)

## Configuração do serviço Java de dados

Prepare as variáveis abaixo no processo Java de dados. Substitua os
endereços e caminhos pelos valores do ambiente. O hostname MariaDB
deve corresponder ao certificado do banco. O arquivo PEM de
`serverSslCert` pode vir do provedor ou da CA do ambiente.
`sslMode=verify-full` verifica o certificado e o hostname.
[TLS no MariaDB Connector/J](https://mariadb.com/docs/connectors/mariadb-connector-j/using-tls-ssl-with-mariadb-java-connector)

```sh
export SPRING_PROFILES_ACTIVE=hml
export SPRING_DATASOURCE_URL='jdbc:mariadb://mariadb-j6.example.invalid:3306/enois_j6?sslMode=verify-full&serverSslCert=/etc/enois/tls/mariadb-ca.pem'
export SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.mariadb.jdbc.Driver
export SPRING_DATASOURCE_USERNAME=enois_j6_data
export SPRING_JPA_PROPERTIES_HIBERNATE_DIALECT=org.hibernate.dialect.MariaDBDialect
export SPRING_JPA_HIBERNATE_DDL_AUTO=validate
export SPRING_FLYWAY_ENABLED=true
export SPRING_FLYWAY_VALIDATE_ON_MIGRATE=true

export REDE_FRONTEND_URL='https://frontend-j6.example.invalid'
export REDE_BACKEND_URL='https://api-j6.example.invalid'
export SERVER_ADDRESS='<ip-privado-do-host-java>'
export SERVER_PORT=8090
export SERVER_SSL_ENABLED=true
export SERVER_SSL_CERTIFICATE='file:/etc/enois/tls/data-fullchain.pem'
export SERVER_SSL_CERTIFICATE_PRIVATE_KEY='file:/etc/enois/tls/data-key.pem'
export REDE_DADOS_CHAVE_ARQUIVO='/etc/enois/data.key'
export REDE_DADOS_OUTBOX_ENABLED=false
```

Injete a senha do usuário MariaDB em `DB_PASSWORD`, que o perfil `hml`
lê do ambiente. `REDE_DADOS_CHAVE_ARQUIVO` deve apontar para um arquivo
com modo `0600`, acessível ao usuário Java, com os mesmos bytes de
`CP_DATA_KEY_FILE`. O certificado HTTPS do serviço de dados precisa
corresponder ao endereço anunciado ao CP e ser aceito pelo truststore
das réplicas.

Com o JDK 25, construa e inicie o JAR de dados no ambiente configurado.

```sh
./mvnw -Pdados-distribuidos package
: "${DB_PASSWORD:?Injete DB_PASSWORD no ambiente do serviço de dados}"
java -jar target/rede-0.0.1-SNAPSHOT-dados.jar
```

No terminal de administração do CP, registre o endereço HTTPS do serviço
de dados. O deployment Java deve referenciar `data-j6`.
[Operação de aplicações na plataforma](https://github.com/Rede-ENois/enois-distributed/blob/main/docs/applications.md)

```sh
enoisctl grant data-j6 data-service trusted
enoisctl data-service data-j6 https://data-j6.example.invalid:8090 1.0.0
```

## Verificação para o aceite

1. A partir do host de dados, conecte ao MariaDB com o usuário da aplicação
   e verificação TLS. Confira a versão, a collation do schema e o TLS da sessão.

   ```sh
   mariadb --protocol=tcp --ssl-verify-server-cert --ssl-ca=/etc/enois/tls/mariadb-ca.pem \
     --host=mariadb-j6.example.invalid --port=3306 --user=enois_j6_data --password enois_j6
   ```

   ```sql
   SELECT VERSION(), @@character_set_database, @@collation_database;
   SHOW SESSION STATUS LIKE 'Ssl_cipher';
   SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
   ```

   Faça a última consulta após o boot do serviço. As migrações devem estar
   concluídas até a versão do JAR, com o Hibernate em `validate`.
2. Confira que o IP ou hostname registrado no CP resolve a partir das
   réplicas, que o certificado é confiável e que suas sondas locais
   `GET /readyz` retornam `200` com a versão atribuída. A sonda de dados
   `GET /internal/data/v1/ready` executa `SELECT 1` com o grant da réplica
   e responde `204` com `X-Enois-Data-Protocol: 1`.
3. Confirme a negação de acesso à porta SQL a partir da rede de cada réplica
   e a ausência de configurações de banco nos processos da réplica e do
   agente. Execute os fluxos J6 com duas réplicas e uma reserva.

O teste automatizado local do J6 usa H2. Use este ambiente MariaDB para
verificar o boot, o schema e os fluxos de aceite em staging.
