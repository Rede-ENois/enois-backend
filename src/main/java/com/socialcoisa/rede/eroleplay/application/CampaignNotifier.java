package com.socialcoisa.rede.eroleplay.application;

import java.util.List;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.eroleplay.application.port.out.EroleplayRepository;
import com.socialcoisa.rede.eroleplay.domain.Campaign;
import com.socialcoisa.rede.eroleplay.domain.CampaignMember;
import com.socialcoisa.rede.eroleplay.domain.CampaignRole;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;

/**
 * Notificações do contexto de Éroleplay.
 *
 * Regra de privacidade: o corpo NUNCA leva o conteúdo da campanha — nem resumo, nem anotação do
 * mestre. A caixa de notificações é lida fora da campanha e o destino (a página da campanha) é
 * quem exige autorização no backend — então só o nome da campanha, que já é metadado público da
 * descoberta, viaja.
 *
 * Quem administra recebe com kind/cta próprios; o autor de uma ação nunca é notificado dela.
 */
@Component
class CampaignNotifier {

	private final NotificationService notifications;
	private final EroleplayRepository repository;
	private final UserProfileRepository profiles;

	CampaignNotifier(NotificationService notifications, EroleplayRepository repository,
			UserProfileRepository profiles) {
		this.notifications = notifications;
		this.repository = repository;
		this.profiles = profiles;
	}

	/** Entrada aberta: avisa quem administra que a pessoa entrou. */
	void memberJoined(Campaign campaign, String memberId) {
		String actorName = nameOf(memberId);
		for (CampaignMember member : masters(campaign, memberId)) {
			notifications.create(member.userId(), "ROLEPLAY_CAMPAIGN_MEMBER_JOINED", memberId,
				"Novo participante", actorName + " entrou em " + campaign.name() + ".", href(campaign),
				"Ver campanha");
		}
	}

	/** Pedido pendente: avisa quem administra. O solicitante ainda não participa e não recebe nada. */
	void joinRequested(Campaign campaign, String requesterId) {
		String actorName = nameOf(requesterId);
		for (CampaignMember member : masters(campaign, requesterId)) {
			notifications.create(member.userId(), "ROLEPLAY_CAMPAIGN_JOIN_REQUEST", requesterId,
				"Pedido de entrada", actorName + " pediu para entrar em " + campaign.name() + ".",
				href(campaign), "Analisar pedido");
		}
	}

	/** Convite: o convidado é avisado de que entrou. */
	void memberInvited(Campaign campaign, String targetUserId, String actorId) {
		if (targetUserId.equals(actorId)) {
			return;
		}
		notifications.create(targetUserId, "ROLEPLAY_CAMPAIGN_INVITED", actorId, "Convite para campanha",
			"Você foi convidado para " + campaign.name() + ".", href(campaign), "Abrir campanha");
	}

	void requestDecided(Campaign campaign, String requesterId, String actorId, boolean approved) {
		if (approved) {
			notifications.create(requesterId, "ROLEPLAY_CAMPAIGN_JOIN_APPROVED", actorId, "Entrada aprovada",
				"Seu pedido para entrar em " + campaign.name() + " foi aprovado.", href(campaign),
				"Abrir campanha");
			return;
		}
		notifications.create(requesterId, "ROLEPLAY_CAMPAIGN_JOIN_REJECTED", actorId, "Entrada recusada",
			"Seu pedido para entrar em " + campaign.name() + " foi recusado.", href(campaign), "Ver campanha");
	}

	/** Mudança de comando: quem assume o comando precisa saber. */
	void masterTransferred(Campaign campaign, String newMasterId, String actorId) {
		if (newMasterId.equals(actorId)) {
			return;
		}
		notifications.create(newMasterId, "ROLEPLAY_CAMPAIGN_MASTERSHIP_TRANSFERRED", actorId,
			"Você agora é mestre", "O comando da campanha " + campaign.name() + " passou para você.",
			href(campaign), "Abrir campanha");
	}

	/** Os mestres da campanha, menos quem fez a ação (o autor não é notificado dela). */
	private List<CampaignMember> masters(Campaign campaign, String actorId) {
		return repository.listMembers(campaign.id()).stream()
			.filter(member -> member.role() == CampaignRole.GAME_MASTER)
			.filter(member -> !member.userId().equals(actorId))
			.toList();
	}

	private String nameOf(String userId) {
		return profiles.findById(userId).map(profile -> profile.name()).orElse("Alguém");
	}

	private static String href(Campaign campaign) {
		return "/roleplay/campaigns/" + campaign.slug();
	}
}
