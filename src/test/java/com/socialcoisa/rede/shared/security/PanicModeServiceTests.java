package com.socialcoisa.rede.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * O modo panico roda no inicio de TODA requisicao. Ele nao pode ser o ponto por onde um problema
 * de banco vira parada total: nenhuma thread de requisicao pode ficar parada esperando a recarga,
 * e uma falha de leitura nao pode ser repetida a cada requisicao.
 */
class PanicModeServiceTests {

	/** JdbcTemplate que responde o que o teste mandar, contando as consultas. */
	private static final class FakeJdbc extends JdbcTemplate {

		private final AtomicInteger consultas = new AtomicInteger();
		private final CountDownLatch entrou;
		private final CountDownLatch liberar;
		private final boolean falhar;

		FakeJdbc(boolean falhar) {
			this(falhar, null, null);
		}

		FakeJdbc(boolean falhar, CountDownLatch entrou, CountDownLatch liberar) {
			this.falhar = falhar;
			this.entrou = entrou;
			this.liberar = liberar;
		}

		@Override
		public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) throws DataAccessException {
			consultas.incrementAndGet();
			if (entrou != null) {
				entrou.countDown();
			}
			if (liberar != null) {
				try {
					liberar.await(5, TimeUnit.SECONDS);
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
			}
			if (falhar) {
				throw new DataAccessResourceFailureException("banco fora");
			}
			return List.of();
		}

		int consultas() {
			return consultas.get();
		}
	}

	@Test
	void falhaDeLeituraNaoRepeteAConsultaATCadaRequisicao() {
		FakeJdbc jdbc = new FakeJdbc(true);
		PanicModeService service = new PanicModeService(jdbc, Clock.systemUTC(), "");

		for (int i = 0; i < 100; i++) {
			service.active();
		}

		// A janela de recarga vale tambem para a falha: sem isso cada requisicao refazia a consulta.
		assertThat(jdbc.consultas()).isEqualTo(1);
		assertThat(service.active()).isFalse();
	}

	@Test
	void requisicaoNaoEsperaARecargaEmAndamento() throws Exception {
		CountDownLatch entrou = new CountDownLatch(1);
		CountDownLatch liberar = new CountDownLatch(1);
		PanicModeService service = new PanicModeService(new FakeJdbc(false, entrou, liberar), Clock.systemUTC(), "");

		Thread recarga = new Thread(service::active);
		recarga.start();
		assertThat(entrou.await(5, TimeUnit.SECONDS)).isTrue();

		long inicio = System.nanoTime();
		boolean ativo = service.active();
		long millis = (System.nanoTime() - inicio) / 1_000_000;

		// Com a trava exclusiva da versao anterior, esta chamada ficava parada atras da consulta.
		assertThat(millis).isLessThan(200);
		assertThat(ativo).isFalse();

		liberar.countDown();
		recarga.join(5_000);
	}

	@Test
	void estadoLigadoNoEnvVarNaoConsultaOBanco() {
		FakeJdbc jdbc = new FakeJdbc(false);
		PanicModeService service = new PanicModeService(jdbc, Clock.systemUTC(), "yes");

		assertThat(service.active()).isTrue();
		assertThat(service.active()).isTrue();
		assertThat(jdbc.consultas()).isZero();
	}
}