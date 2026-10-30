package com.socialcoisa.rede.moderation.application.port.out;

import java.net.URI;
import java.util.Optional;

import com.socialcoisa.rede.moderation.domain.PageSummary;

/**
 * Le o titulo e a descricao de um destino publicado no texto.
 *
 * O adaptador e quem garante que o destino e publico: link para a rede interna, para o proprio
 * servico ou para um esquema que nao seja `http(s)` nao e visitado. Falha de leitura devolve vazio —
 * um link que nao responde nunca vira acusacao.
 */
public interface LinkedPageReader {

	Optional<PageSummary> summaryOf(URI url);

}
