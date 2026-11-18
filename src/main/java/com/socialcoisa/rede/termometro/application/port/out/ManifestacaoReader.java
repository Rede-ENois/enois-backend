package com.socialcoisa.rede.termometro.application.port.out;

import java.time.Instant;
import java.util.List;

/**
 * As manifestações públicas que alimentam o Termômetro, lidas das fontes desde uma fronteira.
 *
 * Manifestação é o triplo mínimo (texto público, autor interno, instante de publicação): o
 * worker classifica o texto, grava a fatia e descarta o resto — o texto classificado nunca é
 * gravado pelo Termômetro, e o instante define o bucket da hora em que ela cai. Fontes: posts
 * e comentários do feed, desabafos e confissões do Confessionário (todos ativos, todos públicos
 * por definição nos seus espaços).
 */
public interface ManifestacaoReader {

	/** As manifestações criadas depois de `de` até `ate` (exclusivo), da fonte, na ordem do tempo. */
	List<Manifestacao> desde(Instant de, Instant ate);

	/** De onde a manifestação veio — POST, COMMENT, DESABAFO ou CONFISSAO; só vai na fatia. */
	String fonte();

	/** O triplo mínimo que atravessa o port: texto, autor interno e instante de publicação. */
	record Manifestacao(String texto, String autorId, Instant criadoEm) {
	}

}
