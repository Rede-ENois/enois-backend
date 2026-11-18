package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.domain.Comment;
import com.socialcoisa.rede.feed.domain.CommentStatus;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostStatus;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.termometro.application.port.out.ManifestacaoReader;

/**
 * As fontes POST e COMMENT do Termômetro: os textos públicos do feed.
 *
 * Vivem no pacote do feed porque a leitura é do acervo dele (entidades package-private). Leem
 * apenas conteúdo ATIVO desde a fronteira, em páginas — a fronteira corta cedo na prática
 * (corte de 5 minutos), e a página alta só serve à primeira leitura do horizonte de 31 dias.
 * A consulta de janela dos posts é declarada aqui (só serve ao Termômetro); a dos comentários
 * mora no repositório compartilhado, junto do precedente `findWithLinksSince`.
 *
 * Efêmeros vencidos, enquetes (sem texto classificável), posts de canal e BUSINESS (que são
 * do feed corporativo, não público) não entram: o Termômetro mede o que está no ar nos espaços
 * sociais.
 */
@Component
@Transactional(readOnly = true)
class FeedPostManifestacaoReader implements ManifestacaoReader {

	/** Por página de leitura; a fronteira corta cedo na prática (corte de 5 minutos). */
	private static final int PAGINA = 500;

	/** Tipos de texto do feed social; BLOG, ARTICLE, PAGE, POLL, BUSINESS e efêmeros ficam fora. */
	private static final List<PostType> TIPOS_DE_TEXTO = List.of(PostType.INSIGHT, PostType.QUESTION,
			PostType.PROJECT, PostType.OPPORTUNITY, PostType.NEWS, PostType.TUTORIAL, PostType.REVIEW,
			PostType.DIVERTIDO, PostType.COMPLAINT);

	private final SpringDataPostJpaRepository posts;

	FeedPostManifestacaoReader(SpringDataPostJpaRepository posts) {
		this.posts = posts;
	}

	@Override
	public List<Manifestacao> desde(Instant de, Instant ate) {
		List<Manifestacao> manifestacoes = new java.util.ArrayList<>();
		Instant ponteiro = de;
		for (int pagina = 0; pagina < 20; pagina++) {
			List<PostJpaEntity> lote = posts.findTextoSocialEntre(PostStatus.ACTIVE, TIPOS_DE_TEXTO, ponteiro,
					ate, PageRequest.of(pagina, PAGINA));
			for (PostJpaEntity entidade : lote) {
				Post post = entidade.toDomain();
				if (post.content() == null || post.content().isBlank()) {
					continue;
				}
				manifestacoes.add(new Manifestacao(post.content(), post.authorId().value(), post.createdAt()));
			}
			if (lote.size() < PAGINA) {
				break;
			}
		}
		return manifestacoes;
	}

	@Override
	public String fonte() {
		return "POST";
	}

}

@Component
@Transactional(readOnly = true)
class FeedCommentManifestacaoReader implements ManifestacaoReader {

	private static final int PAGINA = 500;

	private final SpringDataCommentJpaRepository comments;

	FeedCommentManifestacaoReader(SpringDataCommentJpaRepository comments) {
		this.comments = comments;
	}

	@Override
	public List<Manifestacao> desde(Instant de, Instant ate) {
		List<Manifestacao> manifestacoes = new java.util.ArrayList<>();
		Instant ponteiro = de;
		for (int pagina = 0; pagina < 20; pagina++) {
			List<CommentJpaEntity> lote = comments.findAtivosEntre(CommentStatus.ACTIVE, ponteiro, ate,
					PageRequest.of(pagina, PAGINA));
			for (CommentJpaEntity entidade : lote) {
				Comment comment = entidade.toDomain();
				if (comment.content() == null || comment.content().isBlank()) {
					continue;
				}
				manifestacoes.add(new Manifestacao(comment.content(), comment.authorId().value(),
						comment.createdAt()));
			}
			if (lote.size() < PAGINA) {
				break;
			}
		}
		return manifestacoes;
	}

	@Override
	public String fonte() {
		return "COMMENT";
	}

}
