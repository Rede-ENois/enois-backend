package com.socialcoisa.rede.termometro.application.port.out;

import java.time.Instant;
import java.util.List;

/**
 * As manifestações públicas que alimentam o Termômetro, lidas das fontes desde uma fronteira.
 *
 * Manifestação é o quádruplo mínimo (texto público, autor interno, instante de publicação e o id
 * da publicação): o worker classifica o texto, grava a fatia e descarta o resto — o texto
 * classificado nunca é gravado pelo Termômetro, e o instante define o bucket da hora em que ela
 * cai. O id é o que torna a contagem idempotente: a mesma publicação relida no recomeço do
 * horizonte não vira duas fatias. Fontes: posts e comentários do feed, desabafos e confissões do
 * Confessionário (todos ativos, todos públicos por definição nos seus espaços).
 */
public interface ManifestacaoReader {

	/**
	 * As manifestações criadas de {@code de} até {@code ate} (exclusivo), da fonte, na ordem do
	 * tempo. A leitura vai até o fim do intervalo — não para na primeira página.
	 */
	List<Manifestacao> desde(Instant de, Instant ate);

	/** De onde a manifestação veio — POST, COMMENT, DESABAFO ou CONFISSAO; só vai na fatia. */
	String fonte();

	/** O que atravessa o port: texto, autor interno, instante de publicação e id da publicação. */
	record Manifestacao(String texto, String autorId, Instant criadoEm, String publicacaoId) {
	}

}
