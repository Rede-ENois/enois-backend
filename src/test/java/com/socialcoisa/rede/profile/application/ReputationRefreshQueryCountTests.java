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

	private int refreshCountingSelects() {
		inspector.reset();
		// A porta de entrada é a MESMA passada do @Scheduled — o teste roda o que roda em produção.
		worker.refreshRankingColumn();
		List<String> sql = List.copyOf(inspector.statements());
		return (int) sql.stream()
			.filter(statement -> statement.strip().toUpperCase().startsWith("SELECT"))
			.count();
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
		int withBase = refreshCountingSelects();

		seedUsers(BASE_USERS, 1);
		int withDouble = refreshCountingSelects();

		seedUsers(BASE_USERS, 2);
		int withTriple = refreshCountingSelects();

		// O custo é por PÁGINA de 500 ids (17 consultas cada). Quando o total de contas da base
		// (a H2 é compartilhada: outras classes criam contas) encosta numa fronteira de página,
		// o loop faz UMA verificação extra de página vazia — +1 consulta que não cresce com o
		// número de contas, só com a fronteira. A prova do invariante é que as consultas por
		// página não crescem: tolera-se no máximo essa 1 consulta de fronteira por medição.
		assertThat(withDouble)
			.as("a passada não pode consultar mais o banco por ter o dobro de contas")
			.isLessThanOrEqualTo(withBase + 1);
		assertThat(withTriple)
			.as("a passada não pode consultar mais o banco por ter o triplo de contas")
			.isLessThanOrEqualTo(withBase + 1);

		// E a coluna do ranking tem de ter sido escrita de verdade para as contas semeadas: elas
		// nascem com o sentinela -1 e a passada grava a nota calculada (0 é nota válida).
		Integer written = jdbc.queryForObject(
			"select count(*) from users where id like 'user_rankq_%' and reputation >= 0", Integer.class);
		assertThat(written).isEqualTo(3 * BASE_USERS);
	}
}
