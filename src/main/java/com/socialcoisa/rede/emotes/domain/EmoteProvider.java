package com.socialcoisa.rede.emotes.domain;

import java.util.Locale;

/**
 * Provedores publicos de figurinha de chat cobertos pelo catalogo do site.
 *
 * Desconhecido vira `null` em vez de excecao: quem decide o que fazer com um provedor invalido e a
 * borda, nao o dominio.
 */
public enum EmoteProvider {

	BTTV, FFZ;

	public static EmoteProvider parse(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException unknown) {
			return null;
		}
	}
}