package com.socialcoisa.rede.eroleplay.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O registro imutavel da rolagem.
 *
 * <p>O que este teste tranca: o resultado sai da fonte do SERVIDOR (o registro nao aceita resultado
 * de fora), cada dado fica guardado para a mesa conferir a soma, a fonte de aleatoriedade e sempre
 * chamada dentro dos limites do dado, e a leitura obedece ao publico — privado nao e o degrau mais
 * fechado da escada, e a excecao do dono.</p>
 */
class DiceRollTests {

	private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

	/** Fonte previsivel: devolve o topo do intervalo, e registra cada chamada feita. */
	private static final class TopOfRange implements RandomGenerator {

		private final java.util.List<int[]> calls = new java.util.ArrayList<>();

		@Override
		public int nextInt(int origin, int bound) {
			calls.add(new int[] { origin, bound });
			return bound - 1;
		}

		@Override
		public long nextLong() {
			throw new UnsupportedOperationException("o registro usa nextInt com origem e limite");
		}
	}

	/** Fonte que devolve algo fora do intervalo pedido — o caso que precisa virar erro, nao registro. */
	private static final class Broken implements RandomGenerator {

		@Override
		public int nextInt(int origin, int bound) {
			return bound + 10;
		}

		@Override
		public long nextLong() {
			throw new UnsupportedOperationException();
		}
	}

	@Test
	void guardaCadaDadoEOSoma() {
		TopOfRange random = new TopOfRange();
		DiceRoll roll = DiceRoll.roll("roll_1", "camp_1", "user_1", DiceExpression.parse("2d6+3"), "ataque",
			SheetVisibility.CAMPAIGN, null, random, NOW);

		assertThat(roll.dice()).containsExactly(6, 6);
		assertThat(roll.total()).isEqualTo(6 + 6 + 3);
		assertThat(roll.expression()).isEqualTo("2d6+3");
		assertThat(roll.label()).isEqualTo("ataque");
		assertThat(roll.createdAt()).isEqualTo(NOW);
	}

	@Test
	void sorteiaDentroDoIntervaloDoDado() {
		TopOfRange random = new TopOfRange();
		DiceRoll.roll("roll_1", "camp_1", "user_1", DiceExpression.parse("3d20"), null, SheetVisibility.CAMPAIGN,
			null, random, NOW);

		// nextInt(1, faces + 1): o 1 e o teto entram, e o limite superior e exclusivo.
		assertThat(random.calls).hasSize(3);
		assertThat(random.calls).allSatisfy(call -> {
			assertThat(call[0]).isEqualTo(1);
			assertThat(call[1]).isEqualTo(21);
		});
	}

	@Test
	void aSomaBateComOsDadosGuardados() {
		DiceRoll roll = DiceRoll.roll("roll_1", "camp_1", "user_1", DiceExpression.parse("1d8+2d6-1"),
			null, SheetVisibility.GAME_MASTER, null, new TopOfRange(), NOW);

		long expected = roll.dice().stream().mapToLong(Integer::longValue).sum() - 1;
		assertThat(roll.total()).isEqualTo(expected);
		assertThat(roll.dice()).hasSize(3);
	}

	@Test
	void fonteQuebradaViraErroENaoRegistro() {
		assertThatThrownBy(() -> DiceRoll.roll("roll_1", "camp_1", "user_1", DiceExpression.parse("1d20"), null,
				SheetVisibility.CAMPAIGN, null, new Broken(), NOW))
			.isInstanceOf(ApiException.class)
			.satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("DICE_SOURCE_BROKEN"));
	}

	@Test
	void oValorDoTotalNuncaVemDoCorpo() {
		// A unica porta de construcao e o sorteio ou a reconstituicao do que foi gravado; nao existe
		// construtor publico que aceite um total informado por quem chama sem passar por um dos dois.
		assertThat(DiceRoll.class.getConstructors()).isEmpty();
	}

	@Test
	void reconstituiSemSortearNemRecalcular() {
		DiceRoll restored = DiceRoll.restore("roll_1", "camp_1", "user_1", "2d6+3", List.of(2, 5), 10, "ataque",
			SheetVisibility.CAMPAIGN, null, NOW);

		assertThat(restored.dice()).containsExactly(2, 5);
		assertThat(restored.total()).isEqualTo(10);
		assertThat(restored.expression()).isEqualTo("2d6+3");
	}

	@Test
	void aListaDeDadosNaoSeDeixaAlterarDeFora() {
		java.util.ArrayList<Integer> dice = new java.util.ArrayList<>(List.of(3, 4));
		DiceRoll roll = DiceRoll.restore("roll_1", "camp_1", "user_1", "2d6", dice, 7, null,
			SheetVisibility.CAMPAIGN, null, NOW);
		dice.add(99);

		assertThat(roll.dice()).containsExactly(3, 4);
		assertThatThrownBy(() -> roll.dice().add(1)).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void oDonoVeAPropriaRolagemEMesmoFechada() {
		DiceRoll roll = DiceRoll.roll("roll_1", "camp_1", "user_1", DiceExpression.parse("1d20"), null,
			SheetVisibility.PRIVATE, null, new TopOfRange(), NOW);

		assertThat(roll.visibleTo(SheetAudience.OWNER)).isTrue();
		assertThat(roll.visibleTo(SheetAudience.GAME_MASTER)).isFalse();
		assertThat(roll.visibleTo(SheetAudience.MEMBER)).isFalse();
	}

	@Test
	void aRolagemAbertaAoMestreNaoApareceParaOgrupo() {
		DiceRoll roll = DiceRoll.roll("roll_1", "camp_1", "user_1", DiceExpression.parse("1d20"), null,
			SheetVisibility.GAME_MASTER, null, new TopOfRange(), NOW);

		assertThat(roll.visibleTo(SheetAudience.GAME_MASTER)).isTrue();
		assertThat(roll.visibleTo(SheetAudience.MEMBER)).isFalse();
	}

	@Test
	void aRolagemDaMesaTodosVeem() {
		DiceRoll roll = DiceRoll.roll("roll_1", "camp_1", "user_1", DiceExpression.parse("1d20"), null,
			SheetVisibility.CAMPAIGN, null, new TopOfRange(), NOW);

		assertThat(roll.visibleTo(SheetAudience.MEMBER)).isTrue();
		assertThat(roll.visibleTo(SheetAudience.GAME_MASTER)).isTrue();
		assertThat(roll.visibleTo(SheetAudience.OWNER)).isTrue();
	}
}
