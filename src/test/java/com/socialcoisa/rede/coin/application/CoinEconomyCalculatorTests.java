package com.socialcoisa.rede.coin.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.coin.application.CoinEconomyCalculator.Metrics;
import com.socialcoisa.rede.coin.application.CoinEconomyCalculator.Params;

/**
 * A cotação automática: sobe com giro e escassez, desce com emissão e com economia parada, e
 * respeita piso, teto e passo.
 */
class CoinEconomyCalculatorTests {

	private final Params params = Params.defaults();

	private static Metrics metrics(long escrow, long p2p, long inflow, long issuance, long circulation,
			long treasury) {
		return new Metrics(escrow, p2p, inflow, issuance, circulation, treasury);
	}

	@Test
	void economiaParadaDerrubaUmPasso() {
		// Ninguém usa e nada volta ao cofre: o preço cede 1% (o cofre precisa de retorno).
		var result = CoinEconomyCalculator.evaluate(20_000,
			metrics(0, 0, 0, 0, 1_000_000, 1_000_000), params);

		assertThat(result.index()).isZero();
		assertThat(result.suggestedMicros()).isEqualTo(19_800);
	}

	@Test
	void noPisoOPrecoNaoDesceMais() {
		var result = CoinEconomyCalculator.evaluate(1_000,
			metrics(0, 0, 0, 10_000_000, 1_000_000, 1_000_000), params);

		assertThat(result.suggestedMicros()).isEqualTo(1_000);
	}

	@Test
	void usoAcimaDaMetaSobeUmPassoNoMaximo() {
		var result = CoinEconomyCalculator.evaluate(10_000,
			metrics(200_000, 0, 0, 0, 1_000_000, 1_000_000), params);

		assertThat(result.index()).isGreaterThan(1);
		assertThat(result.suggestedMicros()).isEqualTo(10_100);
	}

	@Test
	void retornoAoCofreContaComoGiro() {
		// Só volta ao cofre (compras da loja/taxas), sem escrow nem P2P: já é economia girando.
		var result = CoinEconomyCalculator.evaluate(20_000,
			metrics(0, 0, 50_000, 0, 1_000_000, 1_000_000), params);

		assertThat(result.index()).isGreaterThan(1);
		assertThat(result.suggestedMicros()).isEqualTo(20_200);
	}

	@Test
	void menosNoCofreAumentaOValorParaOMesmoGiro() {
		var cofreCheio = CoinEconomyCalculator.evaluate(10_000,
			metrics(50_000, 0, 0, 0, 1_000_000, 1_500_000), params);
		var cofreVazio = CoinEconomyCalculator.evaluate(10_000,
			metrics(50_000, 0, 0, 0, 1_000_000, 500_000), params);

		assertThat(cofreVazio.scarcity()).isGreaterThan(cofreCheio.scarcity());
		assertThat(cofreVazio.index()).isGreaterThan(cofreCheio.index());
	}

	@Test
	void emissaoAltaDilui() {
		var result = CoinEconomyCalculator.evaluate(20_000,
			metrics(50_000, 0, 0, 1_000_000, 1_000_000, 1_000_000), params);

		assertThat(result.index()).isLessThan(1);
		assertThat(result.suggestedMicros()).isEqualTo(19_800);
	}

	@Test
	void poeiraEntreContasNaoEstouraOCalculo() {
		var result = CoinEconomyCalculator.evaluate(10_000,
			metrics(10_000_000, 0, 0, 0, 1_000_000, 1_000_000), params);

		assertThat(result.effectiveUsage()).isEqualTo(1_000_000);
		assertThat(result.suggestedMicros()).isEqualTo(10_100);
	}

	@Test
	void p2pEntraComPesoMenorQueOConsumo() {
		var p2pOnly = CoinEconomyCalculator.evaluate(10_000,
			metrics(0, 200_000, 0, 0, 1_000_000, 1_000_000), params);
		var escrowOnly = CoinEconomyCalculator.evaluate(10_000,
			metrics(200_000, 0, 0, 0, 1_000_000, 1_000_000), params);

		assertThat(p2pOnly.index()).isLessThan(escrowOnly.index());
	}

	@Test
	void respeitaTetoDePreco() {
		var ceiling = new Params(30, 0.05, 0.5, 1.0, 0.5, 0.25, 0.01, 1_000, 999_900);
		var result = CoinEconomyCalculator.evaluate(999_900,
			metrics(10_000_000, 0, 0, 0, 1_000_000, 1_000_000), ceiling);

		assertThat(result.suggestedMicros()).isEqualTo(999_900);
	}

	@Test
	void semCirculacaoNaoReavalia() {
		var result = CoinEconomyCalculator.evaluate(12_345,
			metrics(500_000, 0, 0, 0, 0, 2_000_000), params);

		assertThat(result.suggestedMicros()).isEqualTo(12_345);
	}
}