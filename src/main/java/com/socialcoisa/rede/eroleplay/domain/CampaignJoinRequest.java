package com.socialcoisa.rede.eroleplay.domain;

import java.time.Instant;

/**
 * O pedido de alguém para entrar numa campanha que exige aprovação.
 *
 * <p>Existe um pedido pendente por pessoa e campanha — garantido pelo banco (índice único com
 * {@code pending_slot}) e pela aplicação. Sem essa trava, duas requisições simultâneas criariam
 * dois pedidos e a aprovação de um deixaria o outro vivo.</p>
 */
public class CampaignJoinRequest {

	private final String id;
	private final String campaignId;
	private final String userId;
	private final String message;
	private final CampaignJoinRequestStatus status;
	private final Instant createdAt;
	private final String decidedBy;
	private final Instant decidedAt;

	private CampaignJoinRequest(String id, String campaignId, String userId, String message,
			CampaignJoinRequestStatus status, Instant createdAt, String decidedBy, Instant decidedAt) {
		this.id = id;
		this.campaignId = campaignId;
		this.userId = userId;
		this.message = message;
		this.status = status;
		this.createdAt = createdAt;
		this.decidedBy = decidedBy;
		this.decidedAt = decidedAt;
	}

	public static CampaignJoinRequest create(String id, String campaignId, String userId, String message,
			Instant now) {
		return new CampaignJoinRequest(id, campaignId, userId, message, CampaignJoinRequestStatus.PENDING, now, null,
			null);
	}

	public static CampaignJoinRequest restore(String id, String campaignId, String userId, String message,
			CampaignJoinRequestStatus status, Instant createdAt, String decidedBy, Instant decidedAt) {
		return new CampaignJoinRequest(id, campaignId, userId, message, status, createdAt, decidedBy, decidedAt);
	}

	public String id() {
		return id;
	}

	public String campaignId() {
		return campaignId;
	}

	public String userId() {
		return userId;
	}

	public String message() {
		return message;
	}

	public CampaignJoinRequestStatus status() {
		return status;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public String decidedBy() {
		return decidedBy;
	}

	public Instant decidedAt() {
		return decidedAt;
	}

	public boolean isPending() {
		return status == CampaignJoinRequestStatus.PENDING;
	}
}
