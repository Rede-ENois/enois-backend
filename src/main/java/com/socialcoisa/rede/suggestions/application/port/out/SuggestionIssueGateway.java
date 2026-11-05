package com.socialcoisa.rede.suggestions.application.port.out;

import java.util.Optional;

import com.socialcoisa.rede.suggestions.domain.SuggestionTarget;

/**
 * Abre a issue pública de uma sugestão aprovada. Sem token/repositório do destino o gateway fica
 * desligado para aquele destino e a sugestão continua pendente.
 */
public interface SuggestionIssueGateway {

	boolean enabled(SuggestionTarget target);

	/** Vazio quando o GitHub não respondeu; quem chama devolve a sugestão para a fila. */
	Optional<IssueRef> createIssue(SuggestionTarget target, String title, String body);

	record IssueRef(int number, String url) {
	}
}
