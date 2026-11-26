package com.socialcoisa.rede.wrapped.domain;

/**
 * Uma pessoa com quem a conta mais se cruzou no ano: ou porque a conta interagiu muito no conteúdo
 * dela ("interagi"), ou porque ela interagiu muito no conteúdo da conta ("interagiram").
 *
 * <p>A resposta traz o mínimo para o link do perfil (username), nunca intimidade presumida: são
 * contagens de interação, não de amizade.</p>
 */
public record WrappedInteracao(String userId, String username, String nome, int vezes, Tipo tipo) {

	public enum Tipo {
		INTERAGI,
		INTERAGIRAM
	}
}
