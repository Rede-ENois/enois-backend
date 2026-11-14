package com.socialcoisa.rede.eroleplay.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * A gramatica da rolagem, provada no dominio.
 *
 * <p>O que este teste tranca e a fronteira do que o servidor aceita sortear: se a interpretacao
 * afrouxar, entra no registro uma expressao que o motor nao sabe somar; se endurecer demais, a mesa
 * perde uma rolagem legitima. Os dois lados aparecem aqui.</p>
 */
class DiceExpressionTests {

	private static ApiException errorOf(String expression) {
		try {
			DiceExpression.parse(expression);
		}
		catch (ApiException error) {
			return error;
		}
		throw new AssertionError("esperava recusa para: " + expression);
	}

	@Test
	void interpretaUmDadoSimples() {
		DiceExpression expression = DiceExpression.parse("1d20");

		assertThat(expression.text()).isEqualTo("1d20");
		assertThat(expression.groups()).containsExactly(new DiceExpression.Group(1, 20));
		assertThat(expression.constants()).isEmpty();
		assertThat(expression.minTotal()).isEqualTo(1);
		assertThat(expression.maxTotal()).isEqualTo(20);
	}

	@Test
	void interpretaGrupoComSomaDeConstante() {
		DiceExpression expression = DiceExpression.parse("2d6+3");

		assertThat(expression.groups()).containsExactly(new DiceExpression.Group(2, 6));
		assertThat(expression.constants()).containsExactly(3);
		assertThat(expression.minTotal()).isEqualTo(5);
		assertThat(expression.maxTotal()).isEqualTo(15);
	}

	@Test
	void interpretaSubtracaoDeConstante() {
		DiceExpression expression = DiceExpression.parse("1d20-2");

		assertThat(expression.constants()).containsExactly(-2);
		assertThat(expression.minTotal()).isEqualTo(-1);
		assertThat(expression.maxTotal()).isEqualTo(18);
	}

	@Test
	void aceitaMaisDeUmGrupo() {
		DiceExpression expression = DiceExpression.parse("1d8+2d6+1");

		assertThat(expression.groups()).extracting(DiceExpression.Group::faces).containsExactly(8, 6);
		assertThat(expression.constants()).containsExactly(1);
		assertThat(expression.minTotal()).isEqualTo(4);
		assertThat(expression.maxTotal()).isEqualTo(21);
	}

	@Test
	void quantidadeOmitidaValeUmDado() {
		assertThat(DiceExpression.parse("d20").groups()).containsExactly(new DiceExpression.Group(1, 20));
	}

	@Test
	void normalizaMaiusculasEEspacos() {
		assertThat(DiceExpression.parse(" 2D6 + 3 ").text()).isEqualTo("2d6+3");
	}

	@Test
	void oIntervaloEExaustivo() {
		DiceExpression expression = DiceExpression.parse("2d6+3");

		assertThat(expression.inRange(5)).isTrue();
		assertThat(expression.inRange(15)).isTrue();
		assertThat(expression.inRange(4)).isFalse();
		assertThat(expression.inRange(16)).isFalse();
	}

	@Test
	void recusaExpressaoVazia() {
		assertThat(errorOf("   ").code()).isEqualTo("DICE_EXPRESSION_REQUIRED");
		assertThatThrownBy(() -> DiceExpression.parse(null)).isInstanceOf(ApiException.class)
			.hasMessageContaining("Informe o que rolar");
	}

	@Test
	void recusaExpressaoLongaDemais() {
		assertThat(errorOf("1d20+" + "1".repeat(60)).code()).isEqualTo("DICE_EXPRESSION_TOO_LONG");
	}

	@Test
	void recusaCaractereForaDaGramatica() {
		assertThat(errorOf("2d6*3").code()).isEqualTo("DICE_EXPRESSION_INVALID");
		assertThat(errorOf("1d20+forca").code()).isEqualTo("DICE_EXPRESSION_INVALID");
		assertThat(errorOf("d20; drop table").code()).isEqualTo("DICE_EXPRESSION_INVALID");
	}

	@Test
	void recusaOperadorSolto() {
		assertThat(errorOf("2d6+").code()).isEqualTo("DICE_EXPRESSION_INVALID");
		assertThat(errorOf("2d6++3").code()).isEqualTo("DICE_EXPRESSION_INVALID");
	}

	@Test
	void recusaConstanteSozinha() {
		// "5" nao e rolagem: sem dado, nao ha o que sortear nem o que conferir na mesa.
		assertThat(errorOf("5").code()).isEqualTo("DICE_EXPRESSION_INVALID");
		assertThat(errorOf("5+3").code()).isEqualTo("DICE_EXPRESSION_INVALID");
	}

	@Test
	void recusaQuantidadeForaDaFaixa() {
		assertThat(errorOf("0d20").code()).isEqualTo("DICE_COUNT_INVALID");
		assertThat(errorOf("101d20").code()).isEqualTo("DICE_COUNT_INVALID");
		assertThat(errorOf((DiceExpression.MAX_DICE + 1) + "d20").code()).isEqualTo("DICE_COUNT_INVALID");
		assertThat(DiceExpression.parse(DiceExpression.MAX_DICE + "d20").groups())
			.containsExactly(new DiceExpression.Group(DiceExpression.MAX_DICE, 20));
	}

	@Test
	void recusaFacesForaDaFaixa() {
		assertThat(errorOf("1d0").code()).isEqualTo("DICE_FACES_INVALID");
		assertThat(errorOf("1d1001").code()).isEqualTo("DICE_FACES_INVALID");
	}

	@Test
	void recusaGrupoSemFaces() {
		assertThat(errorOf("1d").code()).isEqualTo("DICE_EXPRESSION_INVALID");
	}

	@Test
	void recusaMaisDeOitoGrupos() {
		assertThat(errorOf("1d6+1d6+1d6+1d6+1d6+1d6+1d6+1d6+1d6").code()).isEqualTo("DICE_GROUPS_TOO_MANY");
	}

	@Test
	void recusaConstanteAcimaDoTeto() {
		assertThat(errorOf("1d20+1000000").code()).isEqualTo("DICE_CONSTANT_TOO_LARGE");
		assertThat(errorOf("1d20-1000000").code()).isEqualTo("DICE_CONSTANT_TOO_LARGE");
	}

	@Test
	void aceitaAConstanteExatamenteNoTeto() {
		assertThat(DiceExpression.parse("1d20+" + DiceExpression.MAX_CONSTANT).constants())
			.containsExactly(DiceExpression.MAX_CONSTANT);
		assertThat(DiceExpression.parse("1d20-" + DiceExpression.MAX_CONSTANT).constants())
			.containsExactly(-DiceExpression.MAX_CONSTANT);
	}

	@Test
	void recusaConstanteQueNaoCabeEmInteiro() {
		assertThat(errorOf("1d20+99999999999999999999").code()).isEqualTo("DICE_EXPRESSION_INVALID");
	}

	@Test
	void oMenorLongNaoEscapaDoTeto() {
		// abs(Long.MIN_VALUE) continua negativo: comparar com sinal e o que recusa de verdade.
		assertThat(errorOf("1d20-9223372036854775808").code()).isEqualTo("DICE_CONSTANT_TOO_LARGE");
	}
}
