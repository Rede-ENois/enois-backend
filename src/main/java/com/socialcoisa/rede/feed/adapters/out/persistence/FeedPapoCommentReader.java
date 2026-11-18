package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.domain.Comment;
import com.socialcoisa.rede.feed.domain.CommentStatus;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostStatus;
import com.socialcoisa.rede.feed.domain.Visibility;
import com.socialcoisa.rede.paporeto.application.port.out.PapoPublicacaoReader;

/**
 * A fonte COMMENT do Papo Reto: os comentários ativos de posts públicos do feed.
 *
 * É fonte SEPARADA da dos posts: a pontuação pesa comentário diferente de post, e a âncora de
 * deduplicação de um comentário é o próprio comentário — não o post que o abriga. O comentário
 * herda a comunidade e a visibilidade do post de onde veio: comentário em post de CONNECTIONS
 * ou em post removido não é papo da rede.
 *
 * A comunidade vem num lote só (`findAllById` dos posts da página): o comentário guarda apenas
 * o id do post, e buscar um a um seria N+1 na janela que corta cedo.
 */
@Component
@Transactional(readOnly = true)
class FeedPapoCommentReader implements PapoPublicacaoReader {

	/** Por página de leitura; a fronteira corta cedo na prática (corte de 5 minutos). */
	private static final int PAGINA = 500;

	private final SpringDataCommentJpaRepository comments;

	private final SpringDataPostJpaRepository posts;

	FeedPapoCommentReader(SpringDataCommentJpaRepository comments, SpringDataPostJpaRepository posts) {
		this.comments = comments;
		this.posts = posts;
	}

	@Override
	public List<Publicacao> desde(Instant de, Instant ate) {
		List<Publicacao> publicacoes = new ArrayList<>();
		for (int pagina = 0; pagina < 20; pagina++) {
			List<CommentJpaEntity> lote = comments.findAtivosEntre(CommentStatus.ACTIVE, de, ate,
					PageRequest.of(pagina, PAGINA));
			List<Comment> daPagina = new ArrayList<>();
			for (CommentJpaEntity entidade : lote) {
				Comment comment = entidade.toDomain();
				if (comment.content() == null || comment.content().isBlank()) {
					continue;
				}
				daPagina.add(comment);
			}
			Map<String, Post> postsDaPagina = postsDos(daPagina);
			for (Comment comment : daPagina) {
				Post post = postsDaPagina.get(comment.postId().value());
				if (post == null) {
					continue;
				}
				publicacoes.add(new Publicacao(comment.content(), comment.authorId().value(), comment.createdAt(),
						post.communityId(), comment.id()));
			}
			if (lote.size() < PAGINA) {
				break;
			}
		}
		return publicacoes;
	}

	/** Os posts dos comentários da página, só os que são papo público da rede. */
	private Map<String, Post> postsDos(List<Comment> daPagina) {
		Set<String> ids = new LinkedHashSet<>();
		daPagina.forEach(comment -> ids.add(comment.postId().value()));
		Map<String, Post> porId = new HashMap<>();
		for (PostJpaEntity entidade : posts.findAllById(ids)) {
			Post post = entidade.toDomain();
			if (post.status() == PostStatus.ACTIVE && post.visibility() == Visibility.PUBLIC) {
				porId.put(post.id().value(), post);
			}
		}
		return porId;
	}

	@Override
	public String fonte() {
		return "COMMENT";
	}

}
