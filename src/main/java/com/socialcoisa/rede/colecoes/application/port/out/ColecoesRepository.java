package com.socialcoisa.rede.colecoes.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.socialcoisa.rede.colecoes.domain.ColecaoPost;
import com.socialcoisa.rede.colecoes.domain.ColecaoSalva;
import com.socialcoisa.rede.feed.domain.UserId;

/** Onde as coleções pessoais e seus elos vivem. Paginação por marca de tempo, como no resto da rede. */
public interface ColecoesRepository {

	ColecaoSalva saveColecao(ColecaoSalva colecao);

	Optional<ColecaoSalva> findColecaoById(String id);

	/** Coleções do dono criadas antes da marca, mais novas primeiro. */
	List<ColecaoSalva> findByOwnerBefore(UserId ownerId, Instant before, int limit);

	/** Todas as coleções do dono, mais novas primeiro — o seletor do post lista tudo. */
	List<ColecaoSalva> findAllByOwner(UserId ownerId);

	ColecaoPost savePost(ColecaoPost post);

	Optional<ColecaoPost> findPost(String colecaoId, String postId);

	/** Coleções (do conjunto) em que o post já está guardado — a flag "tem" do seletor. */
	Collection<String> findColecaoIdsComPost(String postId, Collection<String> colecaoIds);

	/** Elos da coleção antes da marca, mais recentes primeiro. */
	List<ColecaoPost> findPostsByColecao(String colecaoId, Instant before, int limit);

	/** Quantidade de posts por coleção (só coleções com elo contam). */
	Map<String, Long> countPosts(Collection<String> colecaoIds);

	void deletePost(String id);

	void deletePostsByColecao(String colecaoId);

	void deleteColecao(String id);
}
