package com.socialcoisa.rede.feed.domain;

import java.time.Instant;

/**
 * Configuracao da enquete: multipla escolha e prazo de encerramento. Enquete antiga (ou sem
 * linha em poll_configs) vale {@link #SIMPLES}: escolha unica e sem prazo.
 */
public record PollConfig(boolean multipleChoice, Instant endsAt) {

	/** Enquete de escolha unica e sem prazo (padrao das que existiam antes das extensoes). */
	public static final PollConfig SIMPLES = new PollConfig(false, null);

	/** Depois de {@code endsAt} o voto e recusado e o resultado congela. */
	public boolean encerrada(Instant agora) {
		return endsAt != null && !endsAt.isAfter(agora);
	}
}
