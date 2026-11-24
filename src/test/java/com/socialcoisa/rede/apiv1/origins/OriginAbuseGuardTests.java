package com.socialcoisa.rede.apiv1.origins;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Regressao: o construtor juntava as origens do app com as confiaveis usando `addAll` num conjunto
 * IMUTAVEL. Isso lancava `UnsupportedOperationException` na criacao do bean e derrubava o boot do
 * app inteiro (aconteceu na QA em 2026-10-23).
 *
 * <p>A lista de origens do app agora vem do {@link AppOriginRegistry} — a mesma do portao e do CORS
 * —, mas o guarda continua precisando construir com uma lista confiavel vazia.</p>
 */
@Tag("critico")
class OriginAbuseGuardTests {

	@Test
	void constroiComAsDuasListasDeOrigem() {
		AppOriginRegistry origins = new AppOriginRegistry("https://enois.ia.br,http://localhost:5173",
			"https://*.b7cloud.com.br", true);

		assertThatCode(() -> new OriginAbuseGuard(null, origins, 60, 300, 30))
			.doesNotThrowAnyException();
	}

	@Test
	void constroiComListaConfiavelVazia() {
		AppOriginRegistry origins = new AppOriginRegistry("https://enois.ia.br", "", false);

		assertThatCode(() -> new OriginAbuseGuard(null, origins, 60, 300, 30))
			.doesNotThrowAnyException();
	}
}
