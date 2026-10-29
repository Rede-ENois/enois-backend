package com.socialcoisa.rede.moderation.application;

import java.util.Map;

/** Nomes e pesos-base dos sinais. O peso aprendido pode sobrescrever o base, mas nunca some. */
public final class ModerationSignalCatalog {

	public static final String REASON_VIOLENCE = "REASON_VIOLENCE";
	/** Conteúdo sexual explícito: sinal próprio porque a prova costuma estar na IMAGEM, não no texto. */
	public static final String REASON_SEXUAL = "REASON_SEXUAL";
	public static final String REASON_SPAM = "REASON_SPAM";
	public static final String REASON_OTHER = "REASON_OTHER";
	public static final String REPEATED_TARGET = "REPEATED_TARGET";
	public static final String NEW_ACCOUNT = "NEW_ACCOUNT";
	public static final String DETAILED_REPORT = "DETAILED_REPORT";
	public static final String CONTENT_LINK = "CONTENT_LINK";
	public static final String SHORT_CONTENT = "SHORT_CONTENT";
	public static final String SYSTEM_REPORT = "SYSTEM_REPORT";

	public static final Map<String, Integer> BASE_WEIGHTS = Map.of(
		REASON_VIOLENCE, 50,
		// Acima do piso de 30 da pré-triagem por si só: uma denúncia de conteúdo sexual sempre
		// chega ao modelo, mesmo sem nenhum outro sinal (conta antiga, texto curto, sem link).
		REASON_SEXUAL, 60,
		REASON_SPAM, 25,
		REASON_OTHER, 5,
		REPEATED_TARGET, 30,
		NEW_ACCOUNT, 15,
		DETAILED_REPORT, 10,
		CONTENT_LINK, 10,
		SHORT_CONTENT, 10,
		SYSTEM_REPORT, 20);

	private ModerationSignalCatalog() {
	}
}
