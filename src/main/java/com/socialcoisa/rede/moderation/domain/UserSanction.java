package com.socialcoisa.rede.moderation.domain;

import java.time.Instant;

/**
 * Sancao de um usuario por moderacao. RESTRICTION trava publicar/comentar e pode ser revista
 * (recurso); SUSPENDED bloqueia o acesso normal e tem prazo de recurso (graca configurada antes
 * da conversao para BAN). BAN e permanente e pode vir do recurso NEGADO por um humano.
 */
public record UserSanction(String id, String userId, String reportId, SanctionType type, String reason,
		SanctionStatus status, Instant createdAt, Instant removedAt, String removedBy) {

	public static UserSanction create(String id, String userId, String reportId, String reason, Instant now) {
		return new UserSanction(id, userId, reportId, SanctionType.RESTRICTION, reason, SanctionStatus.ACTIVE,
			now, null, null);
	}

	/** Suspensao com prazo: bloqueia como o BAN; sem recurso no prazo, vira BAN definitivo. */
	public static UserSanction suspended(String id, String userId, String reportId, String reason, Instant now) {
		return new UserSanction(id, userId, reportId, SanctionType.SUSPENDED, reason, SanctionStatus.ACTIVE,
			now, null, null);
	}

	public static UserSanction restore(String id, String userId, String reportId, SanctionType type, String reason,
			SanctionStatus status, Instant createdAt, Instant removedAt, String removedBy) {
		return new UserSanction(id, userId, reportId, type, reason, status, createdAt, removedAt, removedBy);
	}

	public boolean isActive() {
		return status == SanctionStatus.ACTIVE;
	}

	/** Banimento (BAN ou PERMABAN): sem sessao nova e sem recurso. */
	public boolean isBan() {
		return type == SanctionType.BAN || type == SanctionType.PERMABAN;
	}

	/** Sem acesso normal; SUSPENDED admite apenas consulta/recurso com autenticacao limitada. */
	public boolean isBlocked() {
		return isBan() || type == SanctionType.SUSPENDED;
	}

	/**
	 * PERMABAN direto do painel admin: mesmo efeito do BAN (sessoes revogadas, sem login) e
	 * marcado como decisao definitiva — o painel nao oferece recurso.
	 */
	public static UserSanction permaBan(String id, String userId, String reportId, String reason, Instant now) {
		return new UserSanction(id, userId, reportId, SanctionType.PERMABAN, reason, SanctionStatus.ACTIVE,
			now, null, null);
	}

	/**
	 * Banimento por decisao humana. Nasce BAN: quem foi banido depois de uma revisao humana nao
	 * ganha recurso nem caminho de "suspensao que vira ban".
	 */
	public static UserSanction ban(String id, String userId, String reportId, String reason, Instant now) {
		return new UserSanction(id, userId, reportId, SanctionType.BAN, reason, SanctionStatus.ACTIVE,
			now, null, null);
	}

	/** Recurso aceito: restricao sai e a conta volta ao normal. */
	public UserSanction remove(String reviewerId, Instant at) {
		return new UserSanction(id, userId, reportId, type, reason, SanctionStatus.REMOVED, createdAt, at, reviewerId);
	}

	/** Recurso negado: restricao vira BAN permanente. */
	public UserSanction escalateToBan() {
		return escalateTo(SanctionType.BAN, reason);
	}

	/**
	 * Escala a sancao ativa para um ban, mantendo o MESMO id. Criar outra linha deixaria duas
	 * sancoes ACTIVE para o usuario e a mais recente mascararia a anterior em findActiveByUser.
	 */
	public UserSanction escalateTo(SanctionType type, String reason) {
		return new UserSanction(id, userId, reportId, type, reason, SanctionStatus.ACTIVE, createdAt,
			removedAt, removedBy);
	}
}
