package com.socialcoisa.rede.moderation.domain;

/**
 * Veredito da checagem de conteúdo automatizado: o post parece saída de máquina sem pessoa por
 * trás (diretriz 8). O palpite é do modelo; quem esconde o post é o backend, e SEMPRE com revisão
 * humana na fila — este caminho nunca aplica sanção.
 */
public enum ContentAutomationVerdict {

	/** Voz de pessoa: usa IA como ferramenta ou escreveu sozinho. Nada acontece. */
	HUMAN,
	/** Saída de máquina publicada como se fosse a voz da conta. */
	AUTOMATED,
	/** O modelo não conseguiu decidir. Fica para revisão humana. */
	UNSURE;

	/** Automatizado confirmado é o único palpite que pode acionar a quarentena. */
	public boolean isSuspicion() {
		return this == AUTOMATED;
	}
}