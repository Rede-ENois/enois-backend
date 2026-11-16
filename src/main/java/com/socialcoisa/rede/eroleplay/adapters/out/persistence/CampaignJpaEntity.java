package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.eroleplay.domain.Campaign;
import com.socialcoisa.rede.eroleplay.domain.CampaignStatus;
import com.socialcoisa.rede.eroleplay.domain.CampaignVisibility;
import com.socialcoisa.rede.eroleplay.domain.GameSystemFamily;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "roleplay_campaigns")
class CampaignJpaEntity {

	@Id
	private String id;

	@Column(nullable = false, length = 80)
	private String slug;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(length = 600)
	private String description;

	/** A ambientacao: o mundo onde a campanha acontece. */
	@Column(length = 300)
	private String setting;

	/** O identificador do sistema de regras, resolvido pelo Registry para um adapter. */
	@Column(name = "game_system", nullable = false, length = 80)
	private String gameSystem;

	/** A familia do motor (D20, D100, CUSTOM) — como o motor generico interpreta o resultado. */
	@Enumerated(EnumType.STRING)
	@Column(name = "system_family", nullable = false, length = 16)
	private GameSystemFamily systemFamily;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private CampaignVisibility visibility;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private CampaignStatus status;

	@Column(name = "owner_id", nullable = false)
	private String ownerId;

	@Column(name = "member_count", nullable = false)
	private int memberCount;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected CampaignJpaEntity() {
	}

	static CampaignJpaEntity fromDomain(Campaign campaign) {
		CampaignJpaEntity entity = new CampaignJpaEntity();
		entity.id = campaign.id();
		entity.slug = campaign.slug();
		entity.name = campaign.name();
		entity.description = campaign.description();
		entity.setting = campaign.setting();
		entity.gameSystem = campaign.gameSystem();
		entity.systemFamily = campaign.systemFamily();
		entity.visibility = campaign.visibility();
		entity.status = campaign.status();
		entity.ownerId = campaign.ownerId();
		entity.memberCount = campaign.memberCount();
		entity.createdAt = campaign.createdAt();
		entity.updatedAt = campaign.updatedAt();
		return entity;
	}

	Campaign toDomain() {
		return Campaign.restore(id, slug, name, description, setting, gameSystem, systemFamily, visibility, status,
			ownerId, memberCount, createdAt, updatedAt);
	}

	String id() {
		return id;
	}
}
