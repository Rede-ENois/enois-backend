package com.socialcoisa.rede.paporeto.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * As publicações públicas que alimentam o Papo Reto, lidas das fontes desde uma fronteira.
 *
 * Publicação é o quádruplo mínimo (texto público, autor interno, instante, comunidade): o
 * worker extrai as expressões do texto, grava a fala e descarta o resto — o texto completo
 * nunca é gravado pelo Papo Reto. A comunidade (quando o post é de uma) fica na fala para o
 * ranking por comunidade; desabafos e confissões não têm comunidade (são espaços próprios).
 */
public interface PapoPublicacaoReader {

	/** As publicações criadas depois de `de` até `ate` (exclusivo), na ordem do tempo. */
	List<Publicacao> desde(Instant de, Instant ate);

	/**
	 * As publicações destes ids que SEGUEM públicas: a visão de "publicações relacionadas"
	 * usa para mostrar o texto de origem do assunto. Conteúdo apagado, expirado ou fora do
	 * acervo público simplesmente não volta (a fala antiga morre sozinha na janela).
	 */
	List<Detalhe> detalhes(Collection<String> publicacaoIds);

	/** De onde a publicação veio — POST, COMMENT, DESABAFO ou CONFISSAO; só vai na fala. */
	String fonte();

	/**
	 * A publicação mínima: texto, autor interno, instante, id da comunidade (nulo se não tem),
	 * o id de origem — a âncora que impede a mesma publicação de falar duas vezes — e os
	 * assuntos DECLARADOS pelo autor (tags; nulo ou vazio quando a fonte não tem).
	 */
	record Publicacao(String texto, String autorId, Instant criadoEm, String communityId, String publicacaoId,
			List<String> assuntosDeclarados) {

		/** A publicação sem assunto declarado (a maioria das fontes). */
		public Publicacao(String texto, String autorId, Instant criadoEm, String communityId, String publicacaoId) {
			this(texto, autorId, criadoEm, communityId, publicacaoId, List.of());
		}
	}

	/**
	 * O detalhe da publicação que a leitura devolve: texto, autor interno (quem decide o que
	 * aparece é o service — banido, bloqueado e anônimo são decisão dele), instante, comunidade
	 * e o id do post que abriga (só comentário precisa, para o link da discussão).
	 */
	record Detalhe(String publicacaoId, String texto, String autorId, Instant criadoEm, String communityId,
			String postId) {
	}

}
