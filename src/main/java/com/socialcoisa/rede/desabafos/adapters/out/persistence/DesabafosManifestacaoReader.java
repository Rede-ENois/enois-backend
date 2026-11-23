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
import com.socialcoisa.rede.termometro.application.port.out.ChaveDaJanela;
import com.socialcoisa.rede.termometro.application.port.out.ManifestacaoReader;

/**
 * A fonte DESABAFO do Termômetro: os desabafos e as acolhidas ativos.
 *
 * Vive no pacote do módulo (entidades package-private) e entrega o triplo mínimo do port —
 * o acolhimento é conteúdo público do espaço dele, e entra na medição como qualquer texto.
 * A leitura é por chave (instante, id), página a página, até o fim do intervalo.
 */
@Component
@Transactional(readOnly = true)
class DesabafosManifestacaoReader implements ManifestacaoReader {

	private static final int PAGINA = 500;

	/** Freio de segurança da leitura inteira: o intervalo de 25 horas nunca chega perto. */
	private static final int LIMITE_DE_LOTES = 40;

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
		manifestacoes.addAll(ChaveDaJanela.ler(de, PAGINA, LIMITE_DE_LOTES,
				(chave, tamanho) -> posts.findAtivosDepois(DesabafoStatus.ACTIVE, chave.instante(), chave.id(), ate,
						PageRequest.of(0, tamanho)),
				entidade -> new ChaveDaJanela.Chave(entidade.getCreatedAt(), entidade.getId()), entidade -> {
					DesabafoPost post = entidade.toDomain();
					return new Manifestacao(post.content() == null ? "" : post.content(),
							post.authorId().value(), post.createdAt(), post.id());
				}));
		manifestacoes.addAll(ChaveDaJanela.ler(de, PAGINA, LIMITE_DE_LOTES,
				(chave, tamanho) -> comments.findAtivosDepois(DesabafoStatus.ACTIVE, chave.instante(), chave.id(), ate,
						PageRequest.of(0, tamanho)),
				entidade -> new ChaveDaJanela.Chave(entidade.getCreatedAt(), entidade.getId()), entidade -> {
					DesabafoComment comment = entidade.toDomain();
					return new Manifestacao(comment.content() == null ? "" : comment.content(),
							comment.authorId().value(), comment.createdAt(), comment.id());
				}));
		return manifestacoes;
	}

	@Override
	public String fonte() {
		return "DESABAFO";
	}

}
