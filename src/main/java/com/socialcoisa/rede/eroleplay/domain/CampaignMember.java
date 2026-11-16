package com.socialcoisa.rede.eroleplay.domain;

import java.time.Instant;

/**
 * A associação de uma pessoa a uma campanha, com o papel dela.
 *
 * <p>Sem id próprio: a identidade é o par (campanha, pessoa), e é ele que o banco torna único.
 * Não existe associação duplicada — entrar duas vezes não é um estado possível.</p>
 */
public class CampaignMember {

	private final String campaignId;
	private final String userId;
	private final CampaignRole role;
	private final Instant joinedAt;

	private CampaignMember(String campaignId, String userId, CampaignRole role, Instant joinedAt) {
		this.campaignId = campaignId;
		this.userId = userId;
		this.role = role;
		this.joinedAt = joinedAt;
	}

	public static CampaignMember join(String campaignId, String userId, CampaignRole role, Instant now) {
		return new CampaignMember(campaignId, userId, role, now);
	}

	public static CampaignMember restore(String campaignId, String userId, CampaignRole role, Instant joinedAt) {
		return new CampaignMember(campaignId, userId, role, joinedAt);
	}

	public String campaignId() {
		return campaignId;
	}

	public String userId() {
		return userId;
	}

	public CampaignRole role() {
		return role;
	}

	public Instant joinedAt() {
		return joinedAt;
	}

	/** Troca o papel (promover a mestre, rebaixar a jogador) preservando a data de entrada. */
	public CampaignMember withRole(CampaignRole newRole) {
		return new CampaignMember(campaignId, userId, newRole, joinedAt);
	}
}
