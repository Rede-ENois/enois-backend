package com.socialcoisa.rede.feed.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.socialcoisa.rede.feed.domain.AuthorIdentity;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;

public interface PostRepository {

	Post save(Post post);

	Optional<Post> findById(PostId id);

	/** Momentos ativos cujo prazo de 24h venceu (o worker de expiração). */
	List<Post> findExpiredMoments(Instant now, int limit);

	List<Post> findByIds(Collection<PostId> ids);

	/** TODOS os posts do autor, em qualquer status — uso da purga (sanitização por spam). */
	List<Post> findAllByAuthor(UserId authorId);

	/**
	 * Posts que anexam uma mídia, em qualquer status. É como a checagem de conteúdo explícito
	 * encontra tudo o que precisa sair do ar quando a imagem é barrada — a mesma imagem pode ter
	 * sido publicada mais de uma vez.
	 */
	List<Post> findByMediaId(String mediaId);

	/** "Para você"/Seguindo: o que as identidades sociais assinaram (BUSINESS nunca entra). */
	List<Post> findActiveByAuthors(Set<UserId> authorIds, Instant before, int limit);

	/** Perfil SOCIAL do autor: sem BLOG (ponteiro) e sem o que a identidade profissional assinou. */
	List<Post> findActiveByAuthor(UserId authorId, Instant before, int limit);

	/** Inteiro do que {@link #findActiveByAuthor} lista, sem paginação. */
	int countActiveByAuthor(UserId authorId);

	/** Republicação ativa desta pessoa para este post original (dedupe do botão). */
	Optional<Post> findActiveRepostByAuthorAndSource(UserId authorId, String sourcePostId);

	/** Cópias ativas de um post (limpeza quando o original é apagado). */
	List<Post> findActiveRepostsBySource(String sourcePostId);

	List<Post> findActiveByCommunity(String communityId, Instant before, int limit);

	/** Posts de um canal privado — só o feed do canal chama (nunca os feeds gerais). */
	List<Post> findActiveByChannel(String channelId, Instant before, int limit);

	/** Feed "Tudo": exclui BLOG (conteúdo no blog) e BUSINESS (assina o feed corporativo). */
	List<Post> findActiveAll(Instant before, int limit);

	List<Post> findActiveByType(PostType type, Instant before, int limit);

	/** Feed "Tudo" respeitando os assuntos bloqueados do leitor (posts sem tópico passam). */
	List<Post> findActiveAllExcludingTopics(Collection<String> blockedTopics, Instant before, int limit);

	/** "Para você"/Seguindo respeitando os assuntos bloqueados do leitor. */
	List<Post> findActiveByAuthorsExcludingTopics(Set<UserId> authorIds, Collection<String> blockedTopics,
			Instant before, int limit);

	/** Em alta: mais reagidos nas últimas windowHours, ordenados por reações desc. */
	List<Post> findTrending(Instant since, int limit);

	/** Feed "Tudo" filtrado por tipos de post. */
	List<Post> findActiveByTypes(Set<PostType> types, Instant before, int limit);

	/** Feed corporativo: só o que a identidade profissional assinou. */
	List<Post> findActiveCorporate(Instant before, int limit);

	/** Página do perfil profissional: só o que o dono assinou como CORPORATE. */
	List<Post> findActiveCorporateByAuthor(UserId authorId, Instant before, int limit);

}
