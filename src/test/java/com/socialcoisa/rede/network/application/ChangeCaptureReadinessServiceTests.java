package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A prontidão de captura por binlog é uma <b>medição</b>, e é isso que estes testes fixam.
 *
 * <p>O erro que este serviço existe para evitar é afirmar "pronto" sem ter perguntado. No H2 da
 * suíte, o {@code select @@log_bin} nem existe — e um serviço que tratasse a falha como "desligado"
 * faria todo ambiente de teste parecer quebrado; um que tratasse como "ligado" mentiria no
 * ambiente de produção. Os dois casos são distintos e o teste os separa.</p>
 *
 * <ul>
 * <li>{@code log_bin} desligado é <b>não pronto</b>, com motivo;</li>
 * <li>{@code binlog_format = STATEMENT} é <b>não pronto</b>: o binlog guarda o comando, não a
 * linha, e duas réplicas divergem;</li>
 * <li>{@code ROW} e ligado é pronto;</li>
 * <li>servidor que não responde é <b>nulo</b>, não {@code false}: "não sei" é diferente de "não
 * está pronto", e quem opera precisa ver a diferença.</li>
 * </ul>
 */
class ChangeCaptureReadinessServiceTests {

	private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");

	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	private final ChangeCaptureReadinessService service = new ChangeCaptureReadinessService(jdbc,
		Clock.fixed(NOW, ZoneOffset.UTC), false);

	@Test
	void binlogDesligadoNaoEhPronto() {
		server("0", "ROW", "FULL", 1073741824L, "11.4.13-MariaDB");

		var readiness = service.check();

		assertThat(readiness.ready()).isFalse();
		assertThat(readiness.reason()).contains("desligado");
		assertThat(readiness.ready()).isNotEqualTo(Boolean.TRUE);
	}

	@Test
	void binlogEmStatementNaoServeECapturaPrecisaDeRow() {
		server("1", "STATEMENT", "FULL", 1073741824L, "11.4.13-MariaDB");

		var readiness = service.check();

		assertThat(readiness.ready()).isFalse();
		assertThat(readiness.reason()).contains("ROW");
	}

	@Test
	void binlogEmRowELigadoEhPronto() {
		server("1", "ROW", "FULL", 1073741824L, "11.4.13-MariaDB");

		var readiness = service.check();

		assertThat(readiness.ready()).isTrue();
		assertThat(readiness.binlogFormat()).isEqualTo("ROW");
		assertThat(readiness.maxSizeBytes()).isEqualTo(1073741824L);
	}

	@Test
	void servidorQueNaoRespondeEhDesconhecidoENaoDesligado() {
		// O H2 da suíte não tem @@log_bin: a consulta lança. Resposta honesta é "não sei".
		when(jdbc.queryForMap(contains("@@")))
			.thenThrow(new DataAccessResourceFailureException("funcao nao encontrada", new SQLException("h2")));

		var readiness = service.check();

		assertThat(readiness.ready()).isNull();
		assertThat(readiness.reason()).contains("nao foi possivel consultar");
	}

	@Test
	void aConsultaEhUmaSoParaAsVariaveisViremDoMesmoInstante() {
		server("1", "ROW", "FULL", 1024L, "11.4.13-MariaDB");

		var readiness = service.check();

		// As cinco variáveis na mesma consulta: três consultas separadas descreveriam dois servidores
		// se houvesse reinício entre elas, e a leitura diria "ROW" com log_bin de outro momento.
		org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.times(1)).queryForMap(contains("@@"));

		assertThat(readiness.checkedAt()).isEqualTo(NOW);
	}

	private void server(String logBin, String format, String rowImage, Long maxSize, String version) {
		when(jdbc.queryForMap(contains("@@")))
			.thenReturn(Map.of("log_bin", logBin, "binlog_format", format, "binlog_row_image", rowImage,
				"max_binlog_size", maxSize, "version", version));
	}

}