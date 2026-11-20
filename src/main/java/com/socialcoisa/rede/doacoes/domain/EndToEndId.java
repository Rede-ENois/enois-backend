package com.socialcoisa.rede.doacoes.domain;

import java.util.regex.Pattern;

/**
 * O identificador fim a fim do Pix, no formato do arranjo: o ISPB do PSP (8 dígitos), a data da
 * liquidação (AAAAMMDD), o código do próprio PSP e 11 caracteres escolhidos por ele — 32
 * alfanuméricos no total.
 *
 * <p>O extrato descreve as coisas de outro jeito, e as instituições não escrevem igual: num extrato
 * aparece o E2E, noutro o "ID fim a fim" com a cara de {@code E + 8 dígitos + 14 dígitos + o
 * sequencial}. Este checkout guarda o E2E do comprovante e confere o resto pelo extrato da própria
 * conta, então aceita os dois (o formato do extrato só é conferido contra ele), mas um valor
 * digitado à mão que não tem cara de nenhum dos dois nunca vira prova.</p>
 */
public final class EndToEndId {

	/** O E2E: 32 alfanuméricos. Tolerância para o 11º caracter variável do código do PSP. */
	private static final Pattern E2E = Pattern.compile("[A-Za-z0-9]{32}");

	/** O "ID fim a fim" de alguns extratos: E + ISPB (8) + data/hora (14) + sequencial. */
	private static final Pattern EXTRATO = Pattern.compile("E\\d{22}[A-Za-z0-9]{1,11}");

	private EndToEndId() {
	}

	/** O identificador fim a fim, só se ele tem a cara de um. Nulo quando não tem. */
	public static String fromReceipt(String raw) {
		if (raw == null) {
			return null;
		}
		String candidato = raw.trim();
		return E2E.matcher(candidato).matches() ? candidato : null;
	}

	/** O número que alguns extratos mostram como "ID fim a fim" — aceito na conferência. */
	public static String fromStatement(String raw) {
		return fromReceipt(raw) != null ? fromReceipt(raw) : fromExtrato(raw);
	}

	private static String fromExtrato(String raw) {
		if (raw == null) {
			return null;
		}
		String candidato = raw.trim();
		return EXTRATO.matcher(candidato).matches() ? candidato : null;
	}
}
