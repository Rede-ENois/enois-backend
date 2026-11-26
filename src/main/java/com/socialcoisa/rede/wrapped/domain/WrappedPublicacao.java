package com.socialcoisa.rede.wrapped.domain;

/**
 * Uma publicação que marcou o ano de quem está lendo — seja porque ela própria publicou e rendeu,
 * seja porque foi nela que a pessoa mais interagiu.
 *
 * <p>{@code id} e {@code autorId} permitem ao container montar o link; o texto nunca é copiado
 * integralmente para outro lugar público. O {@code motivo} diz por que entrou (engajo, participação);
 * a {@code metrica} é o número que decidiu o ranking.</p>
 */
public record WrappedPublicacao(
		String id,
		String autorId,
		String autorUsername,
		String autorName,
		String trecho,
		Motivo motivo,
		int metrica,
		String dataIso) {

	public enum Motivo {
		/** Publicação própria com maior engajamento no ano. */
		PROPRIA_ENGAJADA,
		/** Publicação de terceiro onde a pessoa mais interagiu (reação, comentário, compartilhamento). */
		PARTICIPACAO
	}
}
