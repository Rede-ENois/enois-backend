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
import com.socialcoisa.rede.termometro.application.port.out.ManifestacaoReader;

/**
 * A fonte DESABAFO do Termômetro: os desabafos e as acolhidas ativos.
 *
 * Vive no pacote do módulo (entidades package-private) e entrega o triplo mínimo do port —
 * o acolhimento é conteúdo público do espaço dele, e entra na medição como qualquer texto.
 */
@Component
@Transactional(readOnly = true)
class DesabafosManifestacaoReader implements ManifestacaoReader {

	private static final int PAGINA = 500;

	private final SpringDataDesabafoPostJpaRepository posts;
	private final SpringDataDesabafoCommentJpaRepository comments;

	DesabafosManifestacaoReader(SpringDataDesabafoPostJpaRepository posts,
			SpringDataDesabafoCommentJpaRepository comments) {
		this.posts = posts;
		this.comments = comments;
	}

	@Override
	public List<Manifestacao> desde(Instant de, Instant ate) {
		List<Manifestacao> manifestacoes = new ArrayList<>();
		for (DesabafoPostJpaEntity entidade : posts.findAtivosEntre(DesabafoStatus.ACTIVE, de, ate,
				PageRequest.of(0, PAGINA))) {
			DesabafoPost post = entidade.toDomain();
			if (post.content().isBlank()) {
				continue;
			}
			manifestacoes.add(new Manifestacao(post.content(), post.authorId().value(), post.createdAt()));
		}
		for (DesabafoCommentJpaEntity entidade : comments.findAtivosEntre(DesabafoStatus.ACTIVE, de, ate,
				PageRequest.of(0, PAGINA))) {
			DesabafoComment comment = entidade.toDomain();
			if (comment.content().isBlank()) {
				continue;
			}
			manifestacoes.add(new Manifestacao(comment.content(), comment.authorId().value(), comment.createdAt()));
		}
		return manifestacoes;
	}

	@Override
	public String fonte() {
		return "DESABAFO";
	}

}
