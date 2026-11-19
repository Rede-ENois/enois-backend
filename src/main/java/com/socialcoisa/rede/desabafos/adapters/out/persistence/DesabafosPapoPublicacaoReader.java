package com.socialcoisa.rede.desabafos.adapters.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.desabafos.domain.DesabafoComment;
import com.socialcoisa.rede.desabafos.domain.DesabafoPost;
import com.socialcoisa.rede.desabafos.domain.DesabafoStatus;
import com.socialcoisa.rede.paporeto.application.port.out.PapoPublicacaoReader;

/**
 * A fonte DESABAFO do Papo Reto: os desabafos e as acolhidas ativos.
 *
 * O mesmo critério do leitor do Termômetro (conteúdo ativo dos dois tipos, na janela). O
 * anonimato de exibição dos Desabafos é de tela: internamente a autoria existe para moderação,
 * e é só ela que atravessa o port (contagem de pessoas distintas). Desabafo não tem comunidade:
 * o recorte por comunidade nunca pega fala daqui.
 */
@Component
@Transactional(readOnly = true)
class DesabafosPapoPublicacaoReader implements PapoPublicacaoReader {

	private static final int PAGINA = 500;

	private final SpringDataDesabafoPostJpaRepository posts;
	private final SpringDataDesabafoCommentJpaRepository comments;

	DesabafosPapoPublicacaoReader(SpringDataDesabafoPostJpaRepository posts,
			SpringDataDesabafoCommentJpaRepository comments) {
		this.posts = posts;
		this.comments = comments;
	}

	@Override
	public List<Publicacao> desde(Instant de, Instant ate) {
		List<Publicacao> publicacoes = new ArrayList<>();
		for (DesabafoPostJpaEntity entidade : posts.findAtivosEntre(DesabafoStatus.ACTIVE, de, ate,
				PageRequest.of(0, PAGINA))) {
			DesabafoPost post = entidade.toDomain();
			if (post.content().isBlank()) {
				continue;
			}
			publicacoes.add(new Publicacao(post.content(), post.authorId().value(), post.createdAt(), null,
				post.id()));
		}
		for (DesabafoCommentJpaEntity entidade : comments.findAtivosEntre(DesabafoStatus.ACTIVE, de, ate,
				PageRequest.of(0, PAGINA))) {
			DesabafoComment comment = entidade.toDomain();
			if (comment.content().isBlank()) {
				continue;
			}
			publicacoes.add(new Publicacao(comment.content(), comment.authorId().value(), comment.createdAt(), null,
				comment.id()));
		}
		return publicacoes;
	}

	/**
	 * O texto dos desabafos e acolhidas relacionados, só os ativos. A autoria volta aqui para
	 * o service decidir bloqueio/anonimato — a tela de desabafo NUNCA mostra autor.
	 */
	@Override
	public List<Detalhe> detalhes(java.util.Collection<String> publicacaoIds) {
		if (publicacaoIds == null || publicacaoIds.isEmpty()) {
			return List.of();
		}
		List<Detalhe> detalhes = new ArrayList<>();
		for (DesabafoPostJpaEntity entidade : posts.findAllById(publicacaoIds)) {
			DesabafoPost post = entidade.toDomain();
			if (post.status() == DesabafoStatus.ACTIVE && !post.content().isBlank()) {
				detalhes.add(new Detalhe(post.id(), post.content(), post.authorId().value(), post.createdAt(),
						null, null));
			}
		}
		for (DesabafoCommentJpaEntity entidade : comments.findAllById(publicacaoIds)) {
			DesabafoComment comment = entidade.toDomain();
			if (comment.status() == DesabafoStatus.ACTIVE && !comment.content().isBlank()) {
				detalhes.add(new Detalhe(comment.id(), comment.content(), comment.authorId().value(),
						comment.createdAt(), null, null));
			}
		}
		return detalhes;
	}

	@Override
	public String fonte() {
		return "DESABAFO";
	}

}
