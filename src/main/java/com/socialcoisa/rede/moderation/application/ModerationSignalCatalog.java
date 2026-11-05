package com.socialcoisa.rede.moderation.application;

import java.util.Map;

/** Nomes e pesos-base dos sinais. O peso aprendido pode sobrescrever o base, mas nunca some. */
public final class ModerationSignalCatalog {

	public static final String REASON_VIOLENCE = "REASON_VIOLENCE";
	/** Conteúdo sexual explícito: sinal próprio porque a prova costuma estar na IMAGEM, não no texto. */
	public static final String REASON_SEXUAL = "REASON_SEXUAL";
	/**
	 * Publicação automatizada por bot/IA: a rede é para gente, e a denúncia desse caso tem que
	 * chegar ao modelo mesmo sem nenhum outro sinal — o texto de um robô costuma ser longo, bem
	 * formatado e sem link, que é justamente o perfil que a pré-triagem não pontua.
	 */
	public static final String REASON_AUTOMATION = "REASON_AUTOMATION";
	public static final String REASON_SPAM = "REASON_SPAM";
	public static final String REASON_OTHER = "REASON_OTHER";
	public static final String REPEATED_TARGET = "REPEATED_TARGET";
	public static final String NEW_ACCOUNT = "NEW_ACCOUNT";
	public static final String DETAILED_REPORT = "DETAILED_REPORT";
	public static final String CONTENT_LINK = "CONTENT_LINK";
	public static final String SHORT_CONTENT = "SHORT_CONTENT";
	public static final String SYSTEM_REPORT = "SYSTEM_REPORT";

	public static final Map<String, Integer> BASE_WEIGHTS = Map.ofEntries(
		Map.entry(REASON_VIOLENCE, 50),
		// Acima do piso de 30 da pré-triagem por si só: uma denúncia de conteúdo sexual sempre
		// chega ao modelo, mesmo sem nenhum outro sinal (conta antiga, texto curto, sem link).
		Map.entry(REASON_SEXUAL, 60),
		// Também acima do piso: denúncia de conta automatizada sempre chega ao modelo.
		Map.entry(REASON_AUTOMATION, 55),
		Map.entry(REASON_SPAM, 25),
		Map.entry(REASON_OTHER, 5),
		Map.entry(REPEATED_TARGET, 30),
		Map.entry(NEW_ACCOUNT, 15),
		Map.entry(DETAILED_REPORT, 10),
		Map.entry(CONTENT_LINK, 10),
		Map.entry(SHORT_CONTENT, 10),
		Map.entry(SYSTEM_REPORT, 20));

	private ModerationSignalCatalog() {
	}
}
