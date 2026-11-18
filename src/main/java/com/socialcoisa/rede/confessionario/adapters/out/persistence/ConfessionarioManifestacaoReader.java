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
import com.socialcoisa.rede.termometro.application.port.out.ManifestacaoReader;

/**
 * A fonte CONFISSAO do Termômetro: as histórias e os comentários ativos do Confessionário.
 *
 * O anonimato do Confessionário é de EXIBIÇÃO — internamente a autoria existe para moderação,
 * e é só ela que atravessa o port (para a contagem de pessoas distintas), junto do texto.
 * Nada disso volta ao painel: sai daqui, vira fatia, e o texto é descartado.
 */
@Component
@Transactional(readOnly = true)
class ConfessionarioManifestacaoReader implements ManifestacaoReader {

	private static final int PAGINA = 500;

	private final SpringDataConfissaoPostJpaRepository posts;
	private final SpringDataConfissaoCommentJpaRepository comments;

	ConfessionarioManifestacaoReader(SpringDataConfissaoPostJpaRepository posts,
			SpringDataConfissaoCommentJpaRepository comments) {
		this.posts = posts;
		this.comments = comments;
	}

	@Override
	public List<Manifestacao> desde(Instant de, Instant ate) {
		List<Manifestacao> manifestacoes = new ArrayList<>();
		for (ConfissaoPostJpaEntity entidade : posts.findAtivosEntre(ConfissaoStatus.ACTIVE, de, ate,
				PageRequest.of(0, PAGINA))) {
			ConfissaoPost post = entidade.toDomain();
			if (post.content().isBlank()) {
				continue;
			}
			manifestacoes.add(new Manifestacao(post.content(), post.authorId().value(), post.createdAt()));
		}
		for (ConfissaoCommentJpaEntity entidade : comments.findAtivosEntre(ConfissaoStatus.ACTIVE, de, ate,
				PageRequest.of(0, PAGINA))) {
			ConfissaoComment comment = entidade.toDomain();
			if (comment.content().isBlank()) {
				continue;
			}
			manifestacoes.add(new Manifestacao(comment.content(), comment.authorId().value(), comment.createdAt()));
		}
		return manifestacoes;
	}

	@Override
	public String fonte() {
		return "CONFISSAO";
	}

}
