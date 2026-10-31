package com.socialcoisa.rede.feed.application;

import java.util.List;

/**
 * Estado da enquete de um post do tipo POLL, anexado a PostView pelas leituras (em lote por
 * pagina) e pela resposta do voto. Os contadores sao derivados de poll_votes (nunca coluna
 * acumulada) e `myVote` e a posicao escolhida por quem le, ou null. O voto nao tem troca:
 * quem ja votou recebe 409 no endpoint e o card nao oferece votar de novo.
 */
public record PollView(List<String> options, List<Long> counts, Integer myVote, long totalVotes) {

	public PollView {
		options = options == null ? List.of() : List.copyOf(options);
		counts = counts == null ? List.of() : List.copyOf(counts);
	}
}
