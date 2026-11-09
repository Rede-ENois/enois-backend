package com.socialcoisa.rede.identity.domain;

import java.util.Locale;

/**
 * Traducao dos status da Didit para a linguagem desta plataforma.
 *
 * <p>A Didit devolve strings exatas, com espaco e maiuscula ("In Review", "Kyc Expired"). A
 * comparacao e estrita e ignora caixa/espaco nas pontas apenas para tolerar variacao de
 * digitacao — o mapeamento desconhecido NUNCA vira aprovacao.</p>
 */
public final class DiditStatus {

	private DiditStatus() {
	}

	/**
	 * Status final/observavel. {@code PENDING} aqui significa "ainda nao ha desfecho" e mantem a
	 * tentativa em andamento — nunca aprova.
	 */
	public enum Kind {
		APPROVED, DECLINED, IN_REVIEW, IN_PROGRESS, PENDING, EXPIRED, ABANDONED, UNKNOWN
	}

	public static Kind of(String status) {
		String value = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
		return switch (value) {
			case "approved" -> Kind.APPROVED;
			case "declined" -> Kind.DECLINED;
			case "in review" -> Kind.IN_REVIEW;
			case "in progress" -> Kind.IN_PROGRESS;
			case "not started", "awaiting user", "resubmitted" -> Kind.PENDING;
			case "expired", "kyc expired" -> Kind.EXPIRED;
			case "abandoned" -> Kind.ABANDONED;
			default -> Kind.UNKNOWN;
		};
	}
}
