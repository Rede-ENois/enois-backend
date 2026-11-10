package com.socialcoisa.rede.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * O 409 precisa dizer <em>o que</em> aconteceu.
 *
 * <p>Era uma mensagem única para qualquer violação de integridade — "Operacao conflita com o estado
 * atual dos dados" — e o painel a mostrava sem explicação, como se a pessoa do time tivesse feito
 * algo errado. Agora o código é estável (o front pode reagir a ele) e a mensagem diz o que houve,
 * sem revelar estrutura do banco: a causa raiz continua no log.</p>
 */
class GlobalExceptionHandlerConflictTests {

	private final GlobalExceptionHandler handler =
		new GlobalExceptionHandler(org.mockito.Mockito.mock(com.socialcoisa.rede.ops.monitoring.MonitoringService.class));

	private ErrorResponse handle(String cause) {
		var response = handler.handleConflict(new DataIntegrityViolationException(cause));
		assertThat(response.getStatusCode().value()).isEqualTo(409);
		return response.getBody();
	}

	@Test
	void registroDuplicadoTemCodigoProprio() {
		var body = handle("Duplicate entry 'joao' for key 'uk_users_username'");
		assertThat(body.code()).isEqualTo("CONFLICT_DUPLICATE");
		assertThat(body.message()).isEqualTo("Este registro ja existe.");
	}

	@Test
	void referenciaQuebradaTemCodigoProprio() {
		var body = handle("Cannot delete or update a parent row: a foreign key constraint fails");
		assertThat(body.code()).isEqualTo("CONFLICT_REFERENCE");
	}

	@Test
	void valorGrandeDemaisTemCodigoProprio() {
		var body = handle("Data too long for column 'page_html'");
		assertThat(body.code()).isEqualTo("CONFLICT_VALUE_TOO_LARGE");
		assertThat(body.message()).isEqualTo("Valor maior do que o permitido.");
	}

	@Test
	void valorNuloTemCodigoProprio() {
		var body = handle("Column 'title' cannot be null");
		assertThat(body.code()).isEqualTo("CONFLICT_MISSING_VALUE");
	}

	@Test
	void causaDesconhecidaMantemOCodigoGenerico() {
		var body = handle("algo que o banco disse e nao sabemos classificar");
		assertThat(body.code()).isEqualTo("CONFLICT");
		assertThat(body.message()).isEqualTo("Operacao conflita com o estado atual dos dados.");
	}

	/** A resposta não pode carregar detalhe do banco para quem chamou. */
	@Test
	void aRespostaNaoVazaODetalheDoBanco() {
		var body = handle("Duplicate entry 'joao' for key 'uk_users_username'");
		assertThat(body.message()).doesNotContain("uk_users_username");
		assertThat(body.message()).doesNotContain("joao");
	}
}
