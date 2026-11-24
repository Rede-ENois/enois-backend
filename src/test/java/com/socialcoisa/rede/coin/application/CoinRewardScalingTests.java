package com.socialcoisa.rede.coin.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.coin.domain.CoinRate;

/**
 * A emissão automática preserva o VALOR em reais: se a faísca subiu, paga-se menos faíscas pelo
 * mesmo valor; se caiu, mais. Sempre sobra pelo menos 1 milésimo.
 */
@Tag("critico")
class CoinRewardScalingTests {

	@Test
	void naCotacaoInicialOValorBaseNaoMuda() {
		assertThat(CoinService.scaleReward(CoinService.FAIS(5), CoinRate.INITIAL_BRL_MICROS))
			.isEqualTo(CoinService.FAIS(5));
		assertThat(CoinService.scaleReward(CoinService.FAIS(25), CoinRate.INITIAL_BRL_MICROS))
			.isEqualTo(CoinService.FAIS(25));
	}

	@Test
	void comAFaiscaMaisCaraPagaMenosFaiscasPeloMesmoValor() {
		// 2 centavos por faísca: 5 faíscas (R$ 0,05) viram 2,5 faíscas.
		assertThat(CoinService.scaleReward(CoinService.FAIS(5), 2 * CoinRate.MICROS_PER_CENT))
			.isEqualTo(2500);

		// 3 centavos: 5 / 3 = 1,666... → arredonda para 1,667 faíscas.
		assertThat(CoinService.scaleReward(CoinService.FAIS(5), 3 * CoinRate.MICROS_PER_CENT))
			.isEqualTo(1667);
	}

	@Test
	void comAFaiscaMaisBarataPagaMaisFaiscas() {
		assertThat(CoinService.scaleReward(CoinService.FAIS(5), CoinRate.MICROS_PER_CENT / 2))
			.isEqualTo(CoinService.FAIS(10));
	}

	@Test
	void nuncaDevolveZero() {
		assertThat(CoinService.scaleReward(1, 100 * CoinRate.MICROS_PER_CENT)).isEqualTo(1);
	}
}
