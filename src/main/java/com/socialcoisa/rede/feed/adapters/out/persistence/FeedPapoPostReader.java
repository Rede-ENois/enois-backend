package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostStatus;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.Visibility;
import com.socialcoisa.rede.paporeto.application.port.out.PapoPublicacaoReader;

/**
 * A fonte POST do Papo Reto: os textos públicos do feed.
 *
 * Mesmos critérios do leitor do Termômetro (conteúdo ativo, tipos de texto social, sem canal,
 * nada de corporativo nem efêmero), com uma diferença: o post entra só com Visibility.PUBLIC
 * (post de CONNECTIONS é conversa de conexões, não papo da rede). A publicação carrega a
 * comunidade do post, para o escopo do ranking por comunidade.
 *
 * Vive no pacote do feed porque a leitura é do acervo dele (entidades package-private). O
 * comentário é OUTRA fonte, em {@link FeedPapoCommentReader}: o peso da fonte na pontuação é
 * diferente, e a âncora de deduplicação de um comentário é ele mesmo, não o post que o abriga.
 */
@Component
@Transactional(readOnly = true)
class FeedPapoPostReader implements PapoPublicacaoReader {

	/** Por página de leitura; a fronteira corta cedo na prática (corte de 5 minutos). */
	private static final int PAGINA = 500;

	/** Tipos de texto do feed social; ARTICLE, PAGE, POLL, BUSINESS e efêmeros ficam fora. */
	private static final List<PostType> TIPOS_DE_TEXTO = List.of(PostType.INSIGHT, PostType.QUESTION,
			PostType.PROJECT, PostType.OPPORTUNITY, PostType.NEWS, PostType.TUTORIAL, PostType.REVIEW,
			PostType.DIVERTIDO, PostType.COMPLAINT);

	private final SpringDataPostJpaRepository posts;

	FeedPapoPostReader(SpringDataPostJpaRepository posts) {
		this.posts = posts;
	}

	@Override
	public List<Publicacao> desde(Instant de, Instant ate) {
		List<Publicacao> publicacoes = new ArrayList<>();
		for (int pagina = 0; pagina < 20; pagina++) {
			List<PostJpaEntity> lote = posts.findTextoSocialPublicoEntre(PostStatus.ACTIVE, Visibility.PUBLIC,
					TIPOS_DE_TEXTO, de, ate, PageRequest.of(pagina, PAGINA));
			for (PostJpaEntity entidade : lote) {
				Post post = entidade.toDomain();
				if (post.content() == null || post.content().isBlank()) {
					continue;
				}
				publicacoes.add(new Publicacao(post.content(), post.authorId().value(), post.createdAt(),
						post.communityId(), post.id().value()));
			}
			if (lote.size() < PAGINA) {
				break;
			}
		}
		return publicacoes;
	}

	@Override
	public String fonte() {
		return "POST";
	}

}
