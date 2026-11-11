package com.socialcoisa.rede.profile.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * O job do ranking roda a cada 30 minutos em cada instância e recalcula TODAS as contas: o custo
 * dele não pode crescer com o número de contas.
 *
 * <p>Antes: para CADA conta eram dezessete consultas (findById com as coleções EAGER do perfil,
 * quinze contagens e o gate de identidade) — ~68 mil consultas por passada a 4 mil contas, com
 * três delas carregando entidades inteiras só para chamar .size().</p>
 *
 * <p>A prova é a CONTAGEM das instruções SQL executadas de verdade: dobra-se o número de contas
 * e o número de consultas tem de continuar IDÊNTICO — a leitura em lote é uma consulta por
 * métrica por página, não por conta.</p>
 *
 * <p>O custo é constante POR PÁGINA, não por passada: o worker pagina de 500 em 500, e a base H2 é
 * compartilhada entre as classes da suíte. Quando o total de contas cruza um múltiplo de 500 entre
 * duas medições, a passada faz uma página a mais e a contagem sobe em 17 (1 varredura de ids + 16
 * métricas) — sem que o custo por conta tenha mudado. Por isso a asserção normaliza pelo número de
 * páginas que a passada realmente percorreu.</p>
 */
@SpringBootTest
@Import(ReputationRefreshQueryCountTests.InspectorConfig.class)
class ReputationRefreshQueryCountTests {

	/** Volume semeado em cada medição (e o dobro na segunda). Longe do PAGE=500 do worker. */
	private static final int BASE_USERS = 8;

	@TestConfiguration
	static class InspectorConfig {

		@Bean
		CountingInspector countingInspector() {
			return new CountingInspector();
		}

		@Bean
		org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer statementInspector(
				CountingInspector countingInspector) {
			return properties -> properties.put(
				org.hibernate.cfg.AvailableSettings.STATEMENT_INSPECTOR, countingInspector);
		}
	}

	/**
	 * Guarda as instruções SQL que o Hibernate mandou ao banco, mas só as da thread que estiver
	 * medindo: o gatilho @Scheduled do próprio worker dispara nos 90 segundos de vida do contexto
	 * e, no CI, pode correr em paralelo com a medição — sem esse filtro, a passada concorrente
	 * suja a contagem.
	 */
	static class CountingInspector implements StatementInspector {

		private final AtomicReference<List<String>> captured =
			new AtomicReference<>(Collections.synchronizedList(new ArrayList<>()));

		private final AtomicReference<String> recordingThread = new AtomicReference<>();

		@Override
		public String inspect(String sql) {
			if (Thread.currentThread().getName().equals(recordingThread.get())) {
				captured.get().add(sql);
			}
			return sql;
		}

		void reset() {
			recordingThread.set(Thread.currentThread().getName());
			captured.set(Collections.synchronizedList(new ArrayList<>()));
		}

		List<String> statements() {
			return captured.get();
		}
	}

	@Autowired
	private ReputationRefreshWorker worker;

	@Autowired
	private CountingInspector inspector;

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbc;

	/** Quantas contas existem agora: é o que decide quantas páginas a passada vai percorrer. */
	private int accountCount() {
		Integer total = jdbc.queryForObject("select count(*) from users", Integer.class);
		return total == null ? 0 : total;
	}

	/** Páginas de 500 que a passada percorre com o total de contas informado. */
	private static int pagesFor(int accounts) {
		return Math.max(1, (accounts + 499) / 500);
	}

	/**
	 * SELECTs da passada, normalizados por página. O custo por página é o que a leitura em lote
	 * promete: uma varredura de ids e uma consulta por métrica, independentemente de quantas contas
	 * a página tem.
	 */
	private double selectsPerPage() {
		int accounts = accountCount();
		inspector.reset();
		// A porta de entrada é a MESMA passada do @Scheduled — o teste roda o que roda em produção.
		worker.refreshRankingColumn();
		List<String> sql = List.copyOf(inspector.statements());
		long selects = sql.stream()
			.filter(statement -> statement.strip().toUpperCase().startsWith("SELECT"))
			.count();
		return (double) selects / pagesFor(accounts);
	}

	/**
	 * Contas de teste direto no banco: verificado por e-mail (o gate de identidade precisa estar
	 * ligado para a nota existir) e com a coluna de reputação zerada. Ids prefixados para nunca
	 * colidir com as contas que outros testes criam na mesma execução.
	 */
	private void seedUsers(int extra, int round) {
		for (int i = 0; i < extra; i++) {
			String id = "user_rankq_" + round + "_" + i;
			jdbc.update("delete from users where id = ?", id);
			jdbc.update("""
					insert into users (id, username, name, email, is_admin, open_to_work, is_mentor,
						email_verified_at, created_at, updated_at, reputation)
					values (?, ?, 'Conta do ranking', ?, false, false, false, current_timestamp,
						current_timestamp, current_timestamp, -1)""",
				id, id, id + "@ranking.test");
		}
	}

	@Test
	void theRankingPassCostDoesNotGrowWithTheNumberOfAccounts() {
		// O banco H2 dos testes nasce vazio por execução, mas outras classes desta suíte podem ter
		// criado contas: mede-se o estado REAL varrendo tudo que existe, semeando o lote de cima.
		seedUsers(BASE_USERS, 0);
		double withBase = selectsPerPage();

		seedUsers(BASE_USERS, 1);
		double withDouble = selectsPerPage();

		seedUsers(BASE_USERS, 2);
		double withTriple = selectsPerPage();

		assertThat(withDouble)
			.as("a passada não pode consultar mais o banco por conta: o custo por página é fixo")
			.isEqualTo(withBase);
		assertThat(withTriple)
			.as("a passada não pode consultar mais o banco por conta: o custo por página é fixo")
			.isEqualTo(withBase);

		// E a coluna do ranking tem de ter sido escrita de verdade para as contas semeadas: elas
		// nascem com o sentinela -1 e a passada grava a nota calculada (0 é nota válida).
		Integer written = jdbc.queryForObject(
			"select count(*) from users where id like 'user_rankq_%' and reputation >= 0", Integer.class);
		assertThat(written).isEqualTo(3 * BASE_USERS);
	}
}
