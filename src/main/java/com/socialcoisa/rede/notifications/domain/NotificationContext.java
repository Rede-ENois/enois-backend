package com.socialcoisa.rede.notifications.domain;

import java.util.Set;

/**
 * The interface a notification belongs to. Work kinds (applications, professional messages,
 * microprojects) live in the professional interface; account-wide kinds (security, moderation,
 * support, platform notices) show in both; everything else is social.
 */
public enum NotificationContext {
	SOCIAL,
	PROFESSIONAL,
	SHARED;

	static final Set<String> PROFESSIONAL_KINDS = Set.of("JOB_APPLICATION_STATUS", "NEW_WORK_MESSAGE", "MICROPROJETO",
		"COMPANY_NEW_OPPORTUNITY");

	static final Set<String> SHARED_KINDS = Set.of("SEGURANCA", "MODERACAO", "SANCAO", "RECURSO", "SUPORTE",
		"SISTEMA");

	/** {@link #PROFESSIONAL_KINDS} and {@link #SHARED_KINDS} inlined in the JPQL filter. */
	public static final String PROFESSIONAL_KINDS_JPQL = "('JOB_APPLICATION_STATUS', 'NEW_WORK_MESSAGE', 'MICROPROJETO', 'COMPANY_NEW_OPPORTUNITY')";

	public static final String SHARED_KINDS_JPQL = "('SEGURANCA', 'MODERACAO', 'SANCAO', 'RECURSO', 'SUPORTE', 'SISTEMA')";

	public static NotificationContext of(String kind) {
		if (kind != null && PROFESSIONAL_KINDS.contains(kind)) {
			return PROFESSIONAL;
		}
		return kind != null && SHARED_KINDS.contains(kind) ? SHARED : SOCIAL;
	}
}
