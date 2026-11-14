package com.socialcoisa.rede.eroleplay.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Um valor que o SISTEMA calcula a partir da ficha, e não o jogador.
 *
 * <p>Todo sistema de mesa tem números que saem de outros números: o modificador de um atributo, o
 * total de um teste, a defesa. Eles não podem ser digitados pelo cliente — se fossem, a ficha
 * mentiria. Por isso o derivado NUNCA é gravado: ele é recalculado na leitura, a partir dos valores
 * de origem que o servidor validou. Ficha sem derivado persistido é ficha que não dá para forjar.</p>
 */
public interface DerivedAttribute {

	/** Identificador do derivado. Nunca colide com um campo digitável do modelo. */
	String key();

	/** Rótulo legível. */
	String label();

	/** Como o resultado é lido. */
	AttributeType type();

	/** O campo de origem: a visibilidade do derivado nunca é mais aberta que a dele. */
	String sourceKey();

	/**
	 * O valor calculado a partir dos valores já validados da ficha, ou {@code null} quando não há
	 * origem suficiente para calcular (campo ausente, não numérico).
	 */
	String compute(Map<String, String> values);

	/**
	 * O modificador de um atributo da família D20: {@code (valor - 10) / 2}, arredondado para
	 * baixo. É a leitura que a mesa faz do número — e é cálculo, não conteúdo protegido.
	 */
	static DerivedAttribute modifier(String attributeKey, String label) {
		return new Modifier("mod_" + attributeKey, label + " (modificador)", attributeKey);
	}

	/** O modificador de um atributo. */
	record Modifier(String key, String label, String sourceKey) implements DerivedAttribute {

		@Override
		public AttributeType type() {
			return AttributeType.NUMBER;
		}

		@Override
		public String compute(Map<String, String> values) {
			String raw = values.get(sourceKey);
			if (raw == null || raw.isBlank()) {
				return null;
			}
			try {
				return new BigDecimal(raw.trim())
					.subtract(BigDecimal.TEN)
					.divide(BigDecimal.valueOf(2), 0, RoundingMode.FLOOR)
					.toPlainString();
			}
			catch (NumberFormatException | ArithmeticException error) {
				return null;
			}
		}
	}
}