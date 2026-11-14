package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.eroleplay.domain.CampaignJoinRequest;
import com.socialcoisa.rede.eroleplay.domain.CampaignJoinRequestStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "roleplay_join_requests")
class CampaignJoinRequestJpaEntity {

	@Id
	private String id;

	@Column(name = "campaign_id", nullable = false)
	private String campaignId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(length = 500)
	private String message;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private CampaignJoinRequestStatus status;

	/** Vale o user_id enquanto PENDING e vira NULL quando decidido — o indice unico garante um pedido por pessoa. */
	@Column(name = "pending_slot")
	private String pendingSlot;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "decided_by")
	private String decidedBy;

	@Column(name = "decided_at")
	private Instant decidedAt;

	protected CampaignJoinRequestJpaEntity() {
	}

	static CampaignJoinRequestJpaEntity fromDomain(CampaignJoinRequest request) {
		CampaignJoinRequestJpaEntity entity = new CampaignJoinRequestJpaEntity();
		entity.id = request.id();
		entity.campaignId = request.campaignId();
		entity.userId = request.userId();
		entity.message = request.message();
		entity.status = request.status();
		entity.pendingSlot = request.isPending() ? request.userId() : null;
		entity.createdAt = request.createdAt();
		entity.decidedBy = request.decidedBy();
		entity.decidedAt = request.decidedAt();
		return entity;
	}

	CampaignJoinRequest toDomain() {
		return CampaignJoinRequest.restore(id, campaignId, userId, message, status, createdAt, decidedBy, decidedAt);
	}

	String id() {
		return id;
	}

	String campaignId() {
		return campaignId;
	}

	CampaignJoinRequestStatus status() {
		return status;
	}

	void decide(CampaignJoinRequestStatus newStatus, String actorId, Instant now) {
		this.status = newStatus;
		this.pendingSlot = null;
		this.decidedBy = actorId;
		this.decidedAt = now;
	}
}
