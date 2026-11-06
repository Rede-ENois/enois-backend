package com.socialcoisa.rede.feed.application.port.out;

import java.util.Collection;

/**
 * As preferências de personalização do leitor, lidas pelo feed.
 * Implementação vive no módulo de preferências (fonte única no backend).
 */
public interface ReaderPreferencesReader {

	/** Assuntos que o leitor NÃO quer ver (vazio = sem filtro). */
	Collection<String> blockedTopics(String userId);

	/** Assuntos que o leitor quer ver com mais frequência (vazio = sem boost). */
	Collection<String> likedTopics(String userId);

	/** Verdadeiro quando o leitor escolheu ocultar reposts (puros e com opinião) no feed pessoal. */
	boolean hideReposts(String userId);
}
