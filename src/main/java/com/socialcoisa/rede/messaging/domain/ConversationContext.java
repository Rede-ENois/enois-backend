package com.socialcoisa.rede.messaging.domain;

import java.util.Locale;
import java.util.Set;

/**
 * The interface a conversation belongs to: the social inbox or the professional one.
 *
 * It is derived from the conversation kind, so no column and no backfill are needed: JOB
 * (application), FREELANCE (proposal) and SERVICE (hiring) threads are professional, everything else (DIRECT, GROUP)
 * is social. A conversation never changes context, which keeps historical threads where they are.
 */
public enum ConversationContext {
	SOCIAL,
	PROFESSIONAL;

	public static final Set<String> PROFESSIONAL_KINDS = Set.of("JOB", "FREELANCE", "SERVICE", "BUSINESS_DIRECT");

	/** Same list as {@link #PROFESSIONAL_KINDS}, inlined in the JPQL filters. */
	public static final String PROFESSIONAL_KINDS_JPQL = "('JOB', 'FREELANCE', 'SERVICE', 'BUSINESS_DIRECT')";

	public static ConversationContext of(String kind) {
		return kind != null && PROFESSIONAL_KINDS.contains(kind.toUpperCase(Locale.ROOT)) ? PROFESSIONAL : SOCIAL;
	}
}
