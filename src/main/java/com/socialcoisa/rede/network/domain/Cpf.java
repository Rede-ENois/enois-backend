package com.socialcoisa.rede.network.domain;

/**
 * CPF brasileiro: os 11 dígitos e os dois verificadores.
 *
 * <p>O formulário de inscrição pede CPF porque quem serve um nó responde por ele, e a administração
 * precisa identificar a pessoa. Guardar o número sem conferir produziria cadastros que parecem
 * válidos e não são — e a checagem é barata o suficiente para acontecer na porta de entrada.</p>
 *
 * <p>O que esta classe <b>não</b> faz: consultar a Receita Federal. Ela confere a aritmética dos
 * dígitos verificadores, que elimina erro de digitação e número inventado; não prova que o CPF
 * existe nem que pertence a quem está preenchendo. Provar isso exigiria serviço externo, e a
 * decisão administrativa continua sendo humana.</p>
 */
public final class Cpf {

	private final String digits;

	private Cpf(String digits) {
		this.digits = digits;
	}

	/**
	 * Normaliza (só dígitos) e valida. Devolve vazio quando o valor não é um CPF aritmeticamente
	 * válido — inclusive sequências repetidas como {@code 11111111111}, que passam no cálculo dos
	 * verificadores mas não são CPFs.
	 */
	public static java.util.Optional<Cpf> parse(String raw) {
		if (raw == null) {
			return java.util.Optional.empty();
		}
		String digits = raw.replaceAll("\\D", "");
		if (digits.length() != 11 || digits.chars().distinct().count() == 1) {
			return java.util.Optional.empty();
		}
		if (!checkDigit(digits, 9) || !checkDigit(digits, 10)) {
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(new Cpf(digits));
	}

	/** Confere o dígito na posição {@code position} (9 = primeiro verificador, 10 = segundo). */
	private static boolean checkDigit(String digits, int position) {
		int sum = 0;
		for (int i = 0; i < position; i++) {
			sum += (digits.charAt(i) - '0') * (position + 1 - i);
		}
		int remainder = sum % 11;
		int expected = remainder < 2 ? 0 : 11 - remainder;
		return digits.charAt(position) - '0' == expected;
	}

	/** Só os 11 dígitos, para persistir. */
	public String digits() {
		return digits;
	}

	/** Como o painel mostra em lista: os dois últimos dígitos, o resto oculto. */
	public String masked() {
		return "***.***.***-" + digits.substring(9);
	}
}