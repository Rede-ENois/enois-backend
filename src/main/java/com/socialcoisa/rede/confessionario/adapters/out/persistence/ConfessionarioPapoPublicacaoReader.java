package com.socialcoisa.rede.confessionario.adapters.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.confessionario.domain.ConfissaoComment;
import com.socialcoisa.rede.confessionario.domain.ConfissaoPost;
import com.socialcoisa.rede.confessionario.domain.ConfissaoStatus;
import com.socialcoisa.rede.paporeto.application.port.out.PapoPublicacaoReader;

/**
 * A fonte CONFISSAO do Papo Reto: as histórias e os comentários ativos do Confessionário.
 *
 * O mesmo critério do leitor do Termômetro. O anonimato do Confessionário é de EXIBIÇÃO —
 * internamente a autoria existe para moderação, e é só ela que atravessa o port. Nada disso
 * volta à página: sai daqui, vira fala (expressão normalizada), e o texto é descartado.
 * Confissão não tem comunidade.
 */
@Component
@Transactional(readOnly = true)
class ConfessionarioPapoPublicacaoReader implements PapoPublicacaoReader {

	private static final int PAGINA = 500;

	private final SpringDataConfissaoPostJpaRepository posts;
	private final SpringDataConfissaoCommentJpaRepository comments;

	ConfessionarioPapoPublicacaoReader(SpringDataConfissaoPostJpaRepository posts,
			SpringDataConfissaoCommentJpaRepository comments) {
		this.posts = posts;
		this.comments = comments;
	}

	@Override
	public List<Publicacao> desde(Instant de, Instant ate) {
		List<Publicacao> publicacoes = new ArrayList<>();
		for (ConfissaoPostJpaEntity entidade : posts.findAtivosEntre(ConfissaoStatus.ACTIVE, de, ate,
				PageRequest.of(0, PAGINA))) {
			ConfissaoPost post = entidade.toDomain();
			if (post.content().isBlank()) {
				continue;
			}
			publicacoes.add(new Publicacao(post.content(), post.authorId().value(), post.createdAt(), null,
				post.id()));
		}
		for (ConfissaoCommentJpaEntity entidade : comments.findAtivosEntre(ConfissaoStatus.ACTIVE, de, ate,
				PageRequest.of(0, PAGINA))) {
			ConfissaoComment comment = entidade.toDomain();
			if (comment.content().isBlank()) {
				continue;
			}
			publicacoes.add(new Publicacao(comment.content(), comment.authorId().value(), comment.createdAt(), null,
				comment.id()));
		}
		return publicacoes;
	}

	@Override
	public String fonte() {
		return "CONFISSAO";
	}

}
