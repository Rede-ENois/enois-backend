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
import com.socialcoisa.rede.termometro.application.port.out.ChaveDaJanela;
import com.socialcoisa.rede.termometro.application.port.out.ManifestacaoReader;

/**
 * A fonte CONFISSAO do Termômetro: as histórias e os comentários ativos do Confessionário.
 *
 * O anonimato do Confessionário é de EXIBIÇÃO — internamente a autoria existe para moderação,
 * e é só ela que atravessa o port (para a contagem de pessoas distintas), junto do texto.
 * Nada disso volta ao painel: sai daqui, vira fatia, e o texto é descartado.
 * A leitura é por chave (instante, id), página a página, até o fim do intervalo.
 */
@Component
@Transactional(readOnly = true)
class ConfessionarioManifestacaoReader implements ManifestacaoReader {

	private static final int PAGINA = 500;

	/** Freio de segurança da leitura inteira: o intervalo de 25 horas nunca chega perto. */
	private static final int LIMITE_DE_LOTES = 40;

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
		manifestacoes.addAll(ChaveDaJanela.ler(de, PAGINA, LIMITE_DE_LOTES,
				(chave, tamanho) -> posts.findAtivosDaChave(ConfissaoStatus.ACTIVE, chave.instante(), chave.id(), ate,
						PageRequest.of(0, tamanho)),
				entidade -> new ChaveDaJanela.Chave(entidade.getCreatedAt(), entidade.getId()), entidade -> {
					ConfissaoPost post = entidade.toDomain();
					return new Manifestacao(post.content() == null ? "" : post.content(),
							post.authorId().value(), post.createdAt(), post.id());
				}));
		manifestacoes.addAll(ChaveDaJanela.ler(de, PAGINA, LIMITE_DE_LOTES,
				(chave, tamanho) -> comments.findAtivosDaChave(ConfissaoStatus.ACTIVE, chave.instante(), chave.id(), ate,
						PageRequest.of(0, tamanho)),
				entidade -> new ChaveDaJanela.Chave(entidade.getCreatedAt(), entidade.getId()), entidade -> {
					ConfissaoComment comment = entidade.toDomain();
					return new Manifestacao(comment.content() == null ? "" : comment.content(),
							comment.authorId().value(), comment.createdAt(), comment.id());
				}));
		return manifestacoes;
	}

	@Override
	public String fonte() {
		return "CONFISSAO";
	}

}
