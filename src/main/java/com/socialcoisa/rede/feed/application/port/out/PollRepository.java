package com.socialcoisa.rede.feed.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;

/**
 * Enquetes: alternativas por post e votos (um por pessoa, imutavel — a unique do banco garante).
 * Todas as leituras sao em lote por pagina, no mesmo molde das reacoes.
 */
public interface PollRepository {

	void saveOptions(PostId postId, List<String> options);

	Map<String, List<String>> optionsByPostIds(Collection<String> postIds);

	/** Contagem de votos por posicao de alternativa, para cada post da pagina. */
	Map<String, Map<Integer, Long>> countsByPostIds(Collection<String> postIds);

	/** A posicao votada por quem le em cada post da pagina (so entra quem ja votou). */
	Map<String, Integer> votesByUserAndPostIds(UserId user, Collection<String> postIds);

	/** Total de votos do post (para o rodape da enquete). */
	long countVotes(PostId postId);

	/** Grava o voto. A unique (post_id, user_id) e a regra: o primeiro voto ganha, nunca troca. */
	void vote(PostId postId, UserId user, int optionPosition);
}
