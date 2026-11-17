package com.socialcoisa.rede.identity.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class SessaoLimitadaDecisaoAtualTests {

	private static final Instant AGORA = Instant.parse("2026-11-16T12:00:00Z");

	@ParameterizedTest
	@MethodSource("decisoesEValidades")
	void respeitaDecisaoAtualRevogacaoEValidade(String tipo, String estado) {
		var sessao = sessaoLimitada(tipo);
		if ("REVOGADA".equals(estado)) sessao.revoke(AGORA.minusSeconds(1));
		if ("EXPIRADA".equals(estado))
			ReflectionTestUtils.setField(sessao, "sanctionsExpiresAt", AGORA.minusSeconds(1));
		if ("LIMITE".equals(estado)) ReflectionTestUtils.setField(sessao, "sanctionsExpiresAt", AGORA);
		if ("SEM_VALIDADE".equals(estado)) ReflectionTestUtils.setField(sessao, "sanctionsExpiresAt", null);
		boolean permitida = "VALIDA".equals(estado) && !"BAN".equals(tipo) && !"PERMABAN".equals(tipo);
		assertThat(sessao.activeForSanctionsAt(AGORA)).isEqualTo(permitida);
	}

	@ParameterizedTest
	@ValueSource(strings = {"BAN", "PERMABAN"})
	void recusaSessaoEmitidaAposRevogacaoDasSessoesAnteriores(String tipo) {
		var anterior = sessaoLimitada("SUSPENDED");
		ReflectionTestUtils.setField(anterior, "createdAt", AGORA.minusSeconds(20));
		anterior.revoke(AGORA.minusSeconds(10));
		var posterior = sessaoLimitada(tipo);
		assertThat(anterior.activeForSanctionsAt(AGORA)).isFalse();
		assertThat(posterior.activeForSanctionsAt(AGORA)).isFalse();
	}

	@Test
	void preservaConsultaQuandoDecisaoFoiRemovida() {
		var sessao = sessaoLimitada("SUSPENDED");
		ReflectionTestUtils.setField(sessao, "activeSanctionType", null);
		assertThat(sessao.activeForSanctionsAt(AGORA)).isTrue();
	}

	private static Stream<Arguments> decisoesEValidades() {
		return Stream.of("BAN", "PERMABAN", "SUSPENDED", "RESTRICTION", (String) null)
			.flatMap(tipo -> Stream.of("VALIDA", "REVOGADA", "EXPIRADA", "LIMITE", "SEM_VALIDADE")
				.map(estado -> Arguments.of(tipo, estado)));
	}

	private AuthSessionJpaEntity sessaoLimitada(String tipo) {
		var sessao = new AuthSessionJpaEntity("sessao-sintetica", null, "titular-sintetico",
			AGORA.minusSeconds(5), AGORA.plusSeconds(60), null);
		sessao.restrictToSanctions();
		ReflectionTestUtils.setField(sessao, "activeSanctionType", tipo);
		return sessao;
	}
}
