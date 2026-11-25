package com.socialcoisa.rede.figurinhas.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Regras do código escolhido por quem cadastra uma figurinha da comunidade.
 *
 * <p>O código é o que o texto grava entre dois-pontos (`:gatinho:`). Por isso:</p>
 * <ul>
 * <li>nome global pequeno: letras minúsculas, números, `-` e `_` (sem espaço, sem dois-pontos,
 *   sem caracteres de controle);</li>
 * <li>prefixos de OUTRAS coleções são reservados (`enois_`, `bttv_`, `ffz_`): um código daqui
 *   nunca se confunde com o pacote oficial nem com os catálogos de terceiros;</li>
 * <li>a forma normalizada (sem acento, sem caixa, sem separador) é a chave da busca e do controle
 *   de semelhança — "Gatinho-Feliz" e "gatinhofeliz" caem na mesma forma.</li>
 * </ul>
 */
public final class FigurinhasCodigoRules {

	public static final Pattern FORMATO = Pattern.compile("^[a-z0-9_-]{2,32}$");

	/** Prefixos de outras origens: o código da comunidade nunca começa por eles. */
	private static final String[] PREFIXOS_RESERVADOS = { "enois_", "enois-", "bttv_", "ffz_" };

	private FigurinhasCodigoRules() {
	}

	public static boolean formatoValido(String codigo) {
		return codigo != null && FORMATO.matcher(codigo).matches();
	}

	public static boolean origemReservada(String codigo) {
		String minusculo = codigo == null ? "" : codigo.toLowerCase(Locale.ROOT);
		for (String prefixo : PREFIXOS_RESERVADOS) {
			if (minusculo.startsWith(prefixo)) {
				return true;
			}
		}
		return false;
	}

	/** Sem acento, em caixa baixa, com letras e números do fim para o começo (separadores fora). */
	public static String normalizar(String texto) {
		if (texto == null) {
			return "";
		}
		String semAcento = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
		return semAcento.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}
}
