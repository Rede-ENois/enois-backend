package com.socialcoisa.rede.eroleplay.application;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.eroleplay.domain.Campaign;
import com.socialcoisa.rede.shared.audit.AuditTrail;

/**
 * Os atos de campanha que o RFC (§7) manda auditar, gravados na trilha única do sistema.
 *
 * <p>Não é trilha própria do módulo: o Éroleplay usa o MESMO {@link AuditTrail} das demais ações
 * administrativas, com ação própria (prefixo {@code ROLEPLAY_}) e a campanha como alvo. O metadata
 * é JSON mínimo — identificadores, papéis e mudanças de estado. O conteúdo da campanha (nome de
 * campo livre à parte, ambientação, descrição) NÃO entra: a trilha é lida por administradores,
 * não pelos participantes, e o painel precisa dos atos, não do texto.</p>
 */
@Component
class CampaignAuditTrail {

	private final AuditTrail audit;

	CampaignAuditTrail(AuditTrail audit) {
		this.audit = audit;
	}

	/** A criação: nome e slug entram porque identificam a campanha no painel; o resto é enum. */
	void created(Campaign campaign, String actorId) {
		audit.record(actorId, "ROLEPLAY_CAMPAIGN_CREATED", campaign.id(),
			"{\"name\":\"" + escape(campaign.name()) + "\",\"slug\":\"" + campaign.slug()
				+ "\",\"visibility\":\"" + campaign.visibility() + "\",\"system\":\"" + campaign.gameSystem()
				+ "\"}");
	}

	void updated(String campaignId, String actorId) {
		audit.record(actorId, "ROLEPLAY_CAMPAIGN_UPDATED", campaignId, "{}");
	}

	void memberJoined(String campaignId, String actorId) {
		audit.record(actorId, "ROLEPLAY_CAMPAIGN_MEMBER_JOINED", campaignId, "{}");
	}

	void joinRequested(String campaignId, String requesterId) {
		audit.record(requesterId, "ROLEPLAY_CAMPAIGN_JOIN_REQUESTED", campaignId, "{}");
	}

	void memberInvited(String campaignId, String actorId, String memberId) {
		audit.record(actorId, "ROLEPLAY_CAMPAIGN_MEMBER_INVITED", campaignId,
			"{\"member\":\"" + memberId + "\"}");
	}

	void memberLeft(String campaignId, String actorId) {
		audit.record(actorId, "ROLEPLAY_CAMPAIGN_MEMBER_LEFT", campaignId, "{}");
	}

	void memberRemoved(String campaignId, String actorId, String memberId) {
		audit.record(actorId, "ROLEPLAY_CAMPAIGN_MEMBER_REMOVED", campaignId,
			"{\"member\":\"" + memberId + "\"}");
	}

	void mastershipTransferred(String campaignId, String actorId, String newMasterId) {
		audit.record(actorId, "ROLEPLAY_CAMPAIGN_MASTERSHIP_TRANSFERRED", campaignId,
			"{\"from\":\"" + actorId + "\",\"to\":\"" + newMasterId + "\"}");
	}

	void joinRequestDecided(String campaignId, String actorId, String requestId, String memberId, boolean approved) {
		audit.record(actorId,
			approved ? "ROLEPLAY_CAMPAIGN_JOIN_REQUEST_APPROVED" : "ROLEPLAY_CAMPAIGN_JOIN_REQUEST_REJECTED",
			campaignId, "{\"request\":\"" + requestId + "\",\"member\":\"" + memberId + "\"}");
	}

	void archived(String campaignId, String actorId, String previousStatus) {
		audit.record(actorId, "ROLEPLAY_CAMPAIGN_ARCHIVED", campaignId,
			"{\"from\":\"" + previousStatus + "\"}");
	}

	void tableCreated(String campaignId, String actorId, String tableId) {
		audit.record(actorId, "ROLEPLAY_TABLE_CREATED", campaignId, "{\"table\":\"" + tableId + "\"}");
	}

	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
