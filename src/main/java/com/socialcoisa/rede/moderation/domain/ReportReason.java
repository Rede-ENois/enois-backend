package com.socialcoisa.rede.moderation.domain;

public enum ReportReason {
	AI_GENERATED,
	FAKE_CONTENT,
	SCAM,
	DISCRIMINATION,
	PREJUDICE,
	HARASSMENT,
	SPAM,
	VIOLENCE,
	/**
	 * Nudez ou ato sexual explícito, inclusive quando o conteúdo é uma IMAGEM e o texto do post não
	 * diz nada. É o único motivo em que a mídia anexada é a prova: por isso ele entra na pré-triagem
	 * com peso próprio e a triagem olha a imagem, não só o texto.
	 */
	SEXUAL_CONTENT,
	COPYRIGHT,
	OTHER
}
