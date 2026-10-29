package com.socialcoisa.rede.moderation.domain;

/**
 * O que a checagem de imagem concluiu sobre uma mídia.
 *
 * <p>Três estados, e a diferença entre eles é o que decide se a conta cai na hora ou se uma pessoa
 * olha: {@link #EXPLICIT} é nudez ou ato sexual, {@link #SEXUAL_MINOR} é conteúdo sexual com menor
 * (a violação mais grave da lista) e {@link #UNSURE} é tudo o que o modelo não conseguiu afirmar —
 * este último nunca pune sozinho.</p>
 */
public enum MediaSafetyVerdict {
	SAFE,
	EXPLICIT,
	SEXUAL_MINOR,
	UNSURE;

	/** Violação confirmada: a mídia tem de sair do ar, independentemente do que mais aconteça. */
	public boolean isViolation() {
		return this == EXPLICIT || this == SEXUAL_MINOR;
	}
}
