package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.network.application.port.out.NodeObservabilityRepository.Sample;

/**
 * A idade da série do painel do nó (brief §8.7: distinguir ausência de dado, nó offline e medida
 * zerada).
 *
 * <p>O que estes testes seguram, e por quê: sem a distinção, um ponto de duas horas atrás desenha
 * igual a um deste minuto, e um nó que nunca mediu parece "0% de CPU". O que se afirma sobre o
 * estado de uma máquina de terceiro tem de vir do dado, com a idade dele ao lado.</p>
 */
class NodeObservabilityFreshnessTests {

	private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

	private static Sample amostra(Instant at) {
		return new Sample("nd_a", at, 12.5, 2048, 9000L, 1, null, "NORMAL", null);
	}

	@Test
	void no_que_nunca_mediu_e_NO_SAMPLES_e_nao_medida_zerada() {
		NodeObservabilityService.Freshness f = NodeObservabilityService.freshness(List.of(), NOW);

		assertThat(f.state()).isEqualTo("NO_SAMPLES");
		// Nulo, nunca 0: zero segundo de idade diria "mediu agora", o oposto do caso vazio.
		assertThat(f.ageSeconds()).isNull();
		assertThat(f.lastSampleAt()).isNull();
	}

	@Test
	void ponto_dentro_da_janela_de_vivo_e_FRESH_com_a_idade_real() {
		NodeObservabilityService.Freshness f = NodeObservabilityService.freshness(
			List.of(amostra(NOW.minusSeconds(30))), NOW);

		assertThat(f.state()).isEqualTo("FRESH");
		assertThat(f.ageSeconds()).isEqualTo(30);
		assertThat(f.lastSampleAt()).isEqualTo(NOW.minusSeconds(30));
	}

	@Test
	void ponto_fora_da_janela_e_STALE_mas_o_ultimo_instante_nao_se_perde() {
		NodeObservabilityService.Freshness f = NodeObservabilityService.freshness(
			List.of(amostra(NOW.minusSeconds(7200))), NOW);

		// A tela mostra o número COM a idade; omitir o instante faria um dado de duas horas atrás
		// parecer atual.
		assertThat(f.state()).isEqualTo("STALE");
		assertThat(f.ageSeconds()).isEqualTo(7200);
		assertThat(f.lastSampleAt()).isEqualTo(NOW.minusSeconds(7200));
	}

	@Test
	void o_limite_da_janela_e_inclusivo_para_nao_marcar_como_velho_o_que_ainda_vale() {
		// Exatamente 180 s ainda é "vivo": é a mesma janela do scheduler, e um ponto na fronteira
		// saindo como STALE faria o painel acusar nó que o balanceamento ainda despacha.
		NodeObservabilityService.Freshness f = NodeObservabilityService.freshness(
			List.of(amostra(NOW.minusSeconds(180))), NOW);
		assertThat(f.state()).isEqualTo("FRESH");

		NodeObservabilityService.Freshness depois = NodeObservabilityService.freshness(
			List.of(amostra(NOW.minusSeconds(181))), NOW);
		assertThat(depois.state()).isEqualTo("STALE");
	}

	@Test
	void a_serie_vem_do_mais_novo_para_o_mais_antigo_e_o_ultimo_ponto_e_o_primeiro() {
		// Se a ordem do repositório mudar, a idade seria a do ponto mais fácil de achar errado.
		NodeObservabilityService.Freshness f = NodeObservabilityService.freshness(
			List.of(amostra(NOW.minusSeconds(10)), amostra(NOW.minusSeconds(70))), NOW);

		assertThat(f.ageSeconds()).isEqualTo(10);
	}
}