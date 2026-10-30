package com.socialcoisa.rede.moderation.domain;

/** O que o destino de um link publicado é. */
public enum LinkSafetyVerdict {

	/** Destino sem conteúdo proibido. */
	SAFE,

	/** Conteúdo sexual explícito ou nudez no destino — a mesma diretriz que vale para a imagem. */
	EXPLICIT,

	/** Destino que existe para golpe, phishing, pirataria ou venda de conteúdo proibido. */
	SUSPICIOUS,

	/** Não deu para saber (página fora do ar, sem descrição, modelo sem palpite). */
	UNSURE;

	public boolean isViolation() {
		return this == EXPLICIT || this == SUSPICIOUS;
	}

}
