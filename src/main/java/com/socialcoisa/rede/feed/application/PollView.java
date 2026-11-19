package com.socialcoisa.rede.feed.application;

import java.time.Instant;
import java.util.List;

/**
 * Estado da enquete de um post do tipo POLL, anexado a PostView pelas leituras (em lote por
 * pagina) e pela resposta do voto. Os contadores sao derivados de poll_votes (nunca coluna
 * acumulada); `myVotes` sao as posicoes escolhidas por quem le (vazio se ainda nao votou) e
 * mudam quando a pessoa troca o voto. `multipleChoice` libera varias alternativas por pessoa;
 * `endsAt` nulo = sem prazo — passado o instante, o voto responde 409 POLL_CLOSED e o resultado
 * congela.
 */
public record PollView(List<String> options, List<Long> counts, List<Integer> myVotes, long totalVotes,
		boolean multipleChoice, Instant endsAt) {

	public PollView {
		options = options == null ? List.of() : List.copyOf(options);
		counts = counts == null ? List.of() : List.copyOf(counts);
		myVotes = myVotes == null ? List.of() : List.copyOf(myVotes);
	}
}
