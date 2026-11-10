package com.socialcoisa.rede.coin.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.coin.application.CoinService;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * A conciliação noturna reconstrói o saldo de TODAS as carteiras somando o ledger. Antes era um SUM
 * por conta (findAll + uma consulta por carteira): com uma linha por usuário e um ledger append-only
 * sem retenção, a rodada virava uma ida ao banco por carteira.
 *
 * <p>A prova é a CONTAGEM de consultas a {@code coin_entries} durante a rodada: ela não pode crescer
 * com o número de contas. A segunda prova é a de comportamento — a divergência continua sendo
 * detectada e logada, exatamente como antes.</p>
 */
@SpringBootTest
class CoinReconcileQueryTests {

	@TestConfiguration
	static class InspectorConfig {

		@Bean
		CoinReconcileCountingInspector coinReconcileCountingInspector() {
			return new CoinReconcileCountingInspector();
		}

		@Bean
		org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer coinReconcileInspector(
				CoinReconcileCountingInspector inspector) {
			return properties -> properties.put(
				org.hibernate.cfg.AvailableSettings.STATEMENT_INSPECTOR, inspector);
		}
	}

	/** Captura as instruções SQL da thread do teste (a rodada roda na própria thread). */
	static class CoinReconcileCountingInspector implements StatementInspector {

		private final AtomicReference<List<String>> captured =
			new AtomicReference<>(Collections.synchronizedList(new ArrayList<>()));

		@Override
		public String inspect(String sql) {
			captured.get().add(sql);
			return sql;
		}

		void reset() {
			captured.set(Collections.synchronizedList(new ArrayList<>()));
		}

		List<String> sql() {
			return List.copyOf(captured.get());
		}
	}

	@Autowired private CoinService coins;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private CoinReconcileCountingInspector inspector;

	private final String prefixo = "recon-" + UUID.randomUUID().toString().substring(0, 8) + "-";

	@AfterEach
	void limparFixture() {
		jdbc.update("delete from coin_entries where account_id like ?", prefixo + "%");
		jdbc.update("delete from coin_accounts where account_id like ?", prefixo + "%");
	}

	@Test
	void reconciliacaoLeOLedgerInteiroEmUmaConsulta() {
		// Várias carteiras, cada uma com lançamentos: o pior caso para a consulta por conta.
		for (int i = 0; i < 25; i++) {
			String conta = prefixo + i;
			long saldo = 0;
			jdbc.update("insert into coin_accounts (account_id, balance, version, created_at) values (?, ?, 0, now())",
				conta, 10L * i);
			for (int lancamento = 0; lancamento < 3; lancamento++) {
				long valor = 10L * i / 3;
				saldo += valor;
				jdbc.update("""
					insert into coin_entries (id, account_id, amount, kind, ref_id, message, idempotency_key,
						prev_hash, hash, created_at) values (?, ?, ?, 'REWARD', ?, null, ?, ?, ?, now())
					""", UUID.randomUUID().toString(), conta, valor, "ref-" + lancamento,
					"reward:ref-" + lancamento, "0".repeat(64), "0".repeat(64));
			}
			// O snapshot fechado com a soma real: a rodada não deve reclamar destas contas.
			jdbc.update("update coin_accounts set balance = ? where account_id = ?", saldo, conta);
		}

		inspector.reset();
		coins.reconcile();

		List<String> ledgerQueries = inspector.sql().stream()
			.filter(sql -> sql.toLowerCase().contains("from coin_entries"))
			.toList();
		// Uma leitura agrupada do ledger, independente de quantas carteiras existam.
		assertThat(ledgerQueries).hasSize(1);
		assertThat(ledgerQueries.get(0).toLowerCase()).contains("group by");
	}

	@Test
	void divergenciaEntreSnapshotELedgerContinuaSendoLogada() {
		String conta = prefixo + "divergente";
		jdbc.update("insert into coin_accounts (account_id, balance, version, created_at) values (?, ?, 0, now())",
			conta, 999L);
		jdbc.update("""
			insert into coin_entries (id, account_id, amount, kind, ref_id, message, idempotency_key,
				prev_hash, hash, created_at) values (?, ?, ?, 'REWARD', 'ref-div', null, ?, ?, ?, now())
			""", UUID.randomUUID().toString(), conta, 10L, "reward:ref-div", "0".repeat(64), "0".repeat(64));

		ListAppender<ILoggingEvent> captura = new ListAppender<>();
		captura.start();
		Logger logger = (Logger) LoggerFactory.getLogger(CoinService.class);
		logger.addAppender(captura);
		try {
			coins.reconcile();
		}
		finally {
			logger.detachAppender(captura);
		}

		assertThat(captura.list).anySatisfy(evento -> {
			assertThat(evento.getFormattedMessage()).contains("coin_reconciliation_divergence")
				.contains(conta)
				.contains("snapshot=999")
				.contains("ledger=10");
		});
	}
}