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

	/**
	 * O texto das histórias e comentários relacionados, só os ativos. A autoria volta aqui
	 * para o service decidir bloqueio/anonimato — a tela do Confessionário NUNCA mostra autor.
	 */
	@Override
	public List<Detalhe> detalhes(java.util.Collection<String> publicacaoIds) {
		if (publicacaoIds == null || publicacaoIds.isEmpty()) {
			return List.of();
		}
		List<Detalhe> detalhes = new ArrayList<>();
		for (ConfissaoPostJpaEntity entidade : posts.findAllById(publicacaoIds)) {
			ConfissaoPost post = entidade.toDomain();
			if (post.status() == ConfissaoStatus.ACTIVE && !post.content().isBlank()) {
				detalhes.add(new Detalhe(post.id(), post.content(), post.authorId().value(), post.createdAt(),
						null, null));
			}
		}
		for (ConfissaoCommentJpaEntity entidade : comments.findAllById(publicacaoIds)) {
			ConfissaoComment comment = entidade.toDomain();
			if (comment.status() == ConfissaoStatus.ACTIVE && !comment.content().isBlank()) {
				detalhes.add(new Detalhe(comment.id(), comment.content(), comment.authorId().value(),
						comment.createdAt(), null, null));
			}
		}
		return detalhes;
	}

	@Override
	public String fonte() {
		return "CONFISSAO";
	}

}
