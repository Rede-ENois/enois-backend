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
import com.socialcoisa.rede.termometro.application.port.out.ChaveDaJanela;
import com.socialcoisa.rede.termometro.application.port.out.ManifestacaoReader;

/**
 * As fontes POST e COMMENT do Termômetro: os textos públicos do feed.
 *
 * Vivem no pacote do feed porque a leitura é do acervo dele (entidades package-private). Leem
 * apenas conteúdo ATIVO desde a fronteira, por CHAVE (instante, id), página a página até o fim do
 * intervalo — a página numerada repetia a primeira e nunca chegava ao fim do horizonte.
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

	/** Freio de segurança da leitura inteira: o intervalo de 25 horas nunca chega perto. */
	private static final int LIMITE_DE_LOTES = 40;

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
		return ChaveDaJanela.ler(de, PAGINA, LIMITE_DE_LOTES,
				(chave, tamanho) -> posts.findTextoSocialEntre(PostStatus.ACTIVE, TIPOS_DE_TEXTO, ate,
						chave.instante(), chave.id(), PageRequest.of(0, tamanho)),
				entidade -> new ChaveDaJanela.Chave(entidade.getCreatedAt(), entidade.getId()), entidade -> {
					Post post = entidade.toDomain();
					// Texto vazio aqui é post de efêmero/enquete (sem texto): vai como está e a
					// reconciliação o vê como ainda no ar, sem fatia.
					return new Manifestacao(post.content() == null ? "" : post.content(), post.authorId().value(),
							post.createdAt(), post.id().value());
				});
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

	/** Freio de segurança da leitura inteira: o intervalo de 25 horas nunca chega perto. */
	private static final int LIMITE_DE_LOTES = 40;

	private final SpringDataCommentJpaRepository comments;

	FeedCommentManifestacaoReader(SpringDataCommentJpaRepository comments) {
		this.comments = comments;
	}

	@Override
	public List<Manifestacao> desde(Instant de, Instant ate) {
		return ChaveDaJanela.ler(de, PAGINA, LIMITE_DE_LOTES,
				(chave, tamanho) -> comments.findAtivosDepois(CommentStatus.ACTIVE, chave.instante(), chave.id(),
						ate, PageRequest.of(0, tamanho)),
				entidade -> new ChaveDaJanela.Chave(entidade.getCreatedAt(), entidade.getId()), entidade -> {
					Comment comment = entidade.toDomain();
					return new Manifestacao(comment.content() == null ? "" : comment.content(),
							comment.authorId().value(), comment.createdAt(), comment.id());
				});
	}

	@Override
	public String fonte() {
		return "COMMENT";
	}

}
