package com.socialcoisa.rede.paporeto.application.port.out;

import java.time.Instant;
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

	/** De onde a publicação veio — POST, COMMENT, DESABAFO ou CONFISSAO; só vai na fala. */
	String fonte();

	/**
	 * A publicação mínima: texto, autor interno, instante, id da comunidade (nulo se não tem)
	 * e o id de origem — a âncora que impede a mesma publicação de falar duas vezes.
	 */
	record Publicacao(String texto, String autorId, Instant criadoEm, String communityId, String publicacaoId) {
	}

}
