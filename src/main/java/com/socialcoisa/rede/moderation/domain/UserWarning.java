package com.socialcoisa.rede.moderation.domain;

import java.time.Instant;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Advertência administrativa dirigida a uma conta.
 *
 * <p>É o registro que faltava: "avisar" numa denúncia só mandava uma notificação ao autor, e o
 * histórico dele ficava vazio. A advertência aqui é uma decisão administrativa independente de
 * denúncia — quem aplica é um administrador, a advertência fica presa à CONTA (não ao conteúdo) e
 * pode ser revista, mas não apagada: revogar marca a data e quem revogou, e a linha continua no
 * histórico.</p>
 */
public record UserWarning(String id, String userId, String adminId, String reason, Instant createdAt,
		Instant revokedAt, String revokedBy) {

	/** Motivo mínimo: o mesmo piso das demais decisões administrativas, para o histórico ser útil. */
	public static final int MIN_REASON = 10;
	public static final int MAX_REASON = 2000;

	public static UserWarning create(String id, String userId, String adminId, String reason, Instant now) {
		return new UserWarning(id, userId, adminId, reason, now, null, null);
	}

	public static UserWarning restore(String id, String userId, String adminId, String reason, Instant createdAt,
			Instant revokedAt, String revokedBy) {
		return new UserWarning(id, userId, adminId, reason, createdAt, revokedAt, revokedBy);
	}

	public boolean isActive() {
		return revokedAt == null;
	}

	/** Revoga mantendo a linha: o histórico de advertências não perde o que já foi dito. */
	public UserWarning revoke(String reviewerId, Instant now) {
		if (!isActive()) {
			throw new ApiException(409, "WARNING_ALREADY_REVOKED", "Esta advertência já foi revogada.");
		}
		return new UserWarning(id, userId, adminId, reason, createdAt, now, reviewerId);
	}

	/** Motivo limpo, ou erro com o código que o painel mostra. Vale também para o administrador. */
	public static String requireReason(String reason, String what) {
		String clean = reason == null ? "" : reason.trim();
		if (clean.length() < MIN_REASON) {
			throw new ApiException(400, "REASON_REQUIRED",
				"Explique o motivo de " + what + " (pelo menos " + MIN_REASON + " caracteres).");
		}
		if (clean.length() > MAX_REASON) {
			throw new ApiException(400, "REASON_TOO_LONG",
				"O motivo pode ter no máximo " + MAX_REASON + " caracteres.");
		}
		return clean;
	}
}