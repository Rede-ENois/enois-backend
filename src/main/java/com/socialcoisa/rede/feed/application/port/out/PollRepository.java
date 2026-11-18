package com.socialcoisa.rede.feed.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.socialcoisa.rede.feed.domain.PollConfig;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;

/**
 * Enquetes: alternativas por post, configuracao (multipla escolha e prazo) e votos — a troca
 * de selecao e permitida enquanto a enquete esta aberta. Todas as leituras sao em lote por
 * pagina, no mesmo molde das reacoes.
 */
public interface PollRepository {

	void saveOptions(PostId postId, List<String> options);

	Map<String, List<String>> optionsByPostIds(Collection<String> postIds);

	/** Contagem de votos por posicao de alternativa, para cada post da pagina. */
	Map<String, Map<Integer, Long>> countsByPostIds(Collection<String> postIds);

	/** Pessoas que votaram por post (na multipla escolha a pessoa conta uma vez). */
	Map<String, Long> votersCountByPostIds(Collection<String> postIds);

	/** As posicoes escolhidas por quem le em cada post da pagina (so entra quem ja votou). */
	Map<String, List<Integer>> myVotesByPostIds(UserId user, Collection<String> postIds);

	/**
	 * Substitui a selecao da pessoa no post: grava o que falta e apaga o que saiu. Enquanto a
	 * enquete esta aberta o voto pode ser trocado; depois do prazo o servico recusa antes de chegar aqui.
	 * O chamador deve manter o lock do post ({@link PostRepository#lockForUpdate}) ate o fim da transacao.
	 */
	void replaceVotes(PostId postId, UserId user, List<Integer> positions);

	/** Grava a configuracao (multipla escolha e prazo) junto com as alternativas. */
	void saveConfig(PostId postId, PollConfig config);

	/** Configuracao por post; post sem linha (enquete antiga) nao entra no mapa — leia como SIMPLES. */
	Map<String, PollConfig> configsByPostIds(Collection<String> postIds);
}
