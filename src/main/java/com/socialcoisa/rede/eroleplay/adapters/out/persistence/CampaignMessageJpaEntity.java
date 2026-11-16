package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.eroleplay.domain.CampaignMessage;
import com.socialcoisa.rede.eroleplay.domain.ChatChannel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Uma mensagem do chat de campanha.
 *
 * <p>O conteúdo é criado uma vez e não se reescreve; o único estado que muda é a fixação
 * ({@code pinned_by}/{@code pinned_at}), que é decisão do mestre e metadado de leitura — nunca uma
 * segunda versão do que foi dito.</p>
 */
@Entity
@Table(name = "roleplay_campaign_messages")
class CampaignMessageJpaEntity {

	@Id
	private String id;

	@Column(name = "campaign_id", nullable = false)
	private String campaignId;

	@Column(nullable = false, length = 3)
	private String channel;

	@Column(name = "author_id", nullable = false)
	private String authorId;

	@Column(name = "recipient_id")
	private String recipientId;

	@Column(nullable = false, length = 2000)
	private String content;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "pinned_by")
	private String pinnedBy;

	@Column(name = "pinned_at")
	private Instant pinnedAt;

	protected CampaignMessageJpaEntity() {
	}

	CampaignMessageJpaEntity(String id, String campaignId, String channel, String authorId, String recipientId,
			String content, Instant createdAt, String pinnedBy, Instant pinnedAt) {
		this.id = id;
		this.campaignId = campaignId;
		this.channel = channel;
		this.authorId = authorId;
		this.recipientId = recipientId;
		this.content = content;
		this.createdAt = createdAt;
		this.pinnedBy = pinnedBy;
		this.pinnedAt = pinnedAt;
	}

	String id() {
		return id;
	}

	String campaignId() {
		return campaignId;
	}

	String channel() {
		return channel;
	}

	String authorId() {
		return authorId;
	}

	String recipientId() {
		return recipientId;
	}

	String content() {
		return content;
	}

	Instant createdAt() {
		return createdAt;
	}

	String pinnedBy() {
		return pinnedBy;
	}

	Instant pinnedAt() {
		return pinnedAt;
	}

	static CampaignMessageJpaEntity fromDomain(CampaignMessage message) {
		return new CampaignMessageJpaEntity(message.id(), message.campaignId(), message.channel().wire(),
			message.authorId(), message.recipientId(), message.content(), message.createdAt(),
			message.pinnedBy(), message.pinnedAt());
	}

	CampaignMessage toDomain() {
		return CampaignMessage.restore(id, campaignId, ChatChannel.of(channel), authorId, recipientId, content,
			createdAt, pinnedBy, pinnedAt);
	}
}
