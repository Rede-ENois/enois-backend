package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.eroleplay.domain.CampaignMember;
import com.socialcoisa.rede.eroleplay.domain.CampaignRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "roleplay_campaign_members")
class CampaignMemberJpaEntity {

	@Id
	private String id;

	@Column(name = "campaign_id", nullable = false)
	private String campaignId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private CampaignRole role;

	@Column(name = "joined_at", nullable = false)
	private Instant joinedAt;

	protected CampaignMemberJpaEntity() {
	}

	static CampaignMemberJpaEntity fromDomain(CampaignMember member) {
		CampaignMemberJpaEntity entity = new CampaignMemberJpaEntity();
		entity.id = entityId(member.campaignId(), member.userId());
		entity.campaignId = member.campaignId();
		entity.userId = member.userId();
		entity.role = member.role();
		entity.joinedAt = member.joinedAt();
		return entity;
	}

	/**
	 * A identidade da associacao e o par (campanha, pessoa): a chave primaria deriva do par, que
	 * e o que o indice unico garante. Derivar o id evita uma coluna a mais sem mudar o modelo.
	 */
	private static String entityId(String campaignId, String userId) {
		return campaignId + ":" + userId;
	}

	CampaignMember toDomain() {
		return CampaignMember.restore(campaignId, userId, role, joinedAt);
	}

	String campaignId() {
		return campaignId;
	}

	String userId() {
		return userId;
	}

	CampaignRole role() {
		return role;
	}
}
