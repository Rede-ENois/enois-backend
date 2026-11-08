package com.socialcoisa.rede.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * O contrato do H2 de que a suíte depende: uma check constraint continua valendo depois que a
 * conexão que a criou é fechada.
 *
 * <p>O H2 2.4.240 (a versão que o Spring Boot 4.1.2 gerencia) quebrava isso: o banco aceitava a
 * constraint, mas a partir da segunda conexão todo insert falhava com "Check constraint invalid
 * ... [23514]" mesmo com valor permitido — o driver chamava de inválida uma constraint íntegra. Na
 * suíte isso não é hipótese: o H2 é nomeado e compartilhado entre classes (jdbc:h2:mem:rede), e
 * quando o cache de contextos do Spring descarta um contexto o Hikari dele fecha as conexões
 * abertas — inclusive a que criou as tabelas do Flyway. Os testes que rodavam depois recebiam 409
 * nos inserts de calendário (V257/V258 são as únicas constraints CHECK do schema), dependendo da
 * ordem em que o CI distribuía as classes.</p>
 *
 * <p>As migrações não rodam aqui de propósito: o que está sendo fixado é o comportamento do driver
 * sobre um CREATE TABLE com CHECK criado e lido em conexões diferentes, na mesma base em memória.</p>
 */
class H2DriverConstraintTests {

	private static final String URL_TEMPLATE = "jdbc:h2:mem:%s;MODE=MySQL;DB_CLOSE_DELAY=-1";

	@Test
	void checkConstraintKeepsWorkingAfterTheCreatingConnectionIsClosed() throws Exception {
		String url = URL_TEMPLATE.formatted("h2-driver-" + UUID.randomUUID());

		try (Connection creator = DriverManager.getConnection(url, "sa", "")) {
			try (Statement statement = creator.createStatement()) {
				statement.execute("create table calendar_entry_participants (id varchar(255) not null, "
					+ "state varchar(16) not null, primary key (id), "
					+ "constraint ck_calendar_participants_state "
					+ "check (state in ('INVITED', 'ACCEPTED', 'DECLINED', 'TENTATIVE')))");
				statement.execute("insert into calendar_entry_participants (id, state) values ('a', 'INVITED')");
			}
			// O Hikari do contexto descartado faz exatamente isto: fecha a conexão que criou a tabela.
		}

		try (Connection next = DriverManager.getConnection(url, "sa", "")) {
			try (Statement statement = next.createStatement()) {
				assertThat(insert(statement, "b", "ACCEPTED")).as("valor que a constraint permite")
					.isEqualTo(Outcome.ACCEPTED);
				assertThat(insert(statement, "c", "CONFIRMED")).as("valor que a constraint proíbe")
					.isEqualTo(Outcome.REJECTED);
			}
		}
	}

	private Outcome insert(Statement statement, String id, String state) throws SQLException {
		try {
			statement.execute(
				"insert into calendar_entry_participants (id, state) values ('" + id + "', '" + state + "')");
			return Outcome.ACCEPTED;
		}
		catch (SQLException exception) {
			// 23513 é a violação normal; 23514 é o driver dizendo que a constraint está inválida.
			return exception.getErrorCode() == 23514 ? Outcome.CONSTRAINT_BROKEN : Outcome.REJECTED;
		}
	}

	private enum Outcome {

		ACCEPTED, REJECTED, CONSTRAINT_BROKEN

	}

}
