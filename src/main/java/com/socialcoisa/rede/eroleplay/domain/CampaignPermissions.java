package com.socialcoisa.rede.eroleplay.domain;

/**
 * Quem pode o quê dentro de uma campanha: uma tabela só, papel por capacidade.
 *
 * <p>Papel nulo é visitante, e visitante não alcança NADA dentro da campanha — nem o nome dos
 * participantes, nem as mesas. O que um visitante enxerga de uma campanha pública é só a
 * apresentação, e essa apresentação vem de fora da autorização, na leitura pública do serviço.</p>
 *
 * <p>O mestre administra a campanha; não administra a plataforma. Papel de campanha nunca é
 * privilégio de plataforma: nenhum destes alcança função global da rede.</p>
 */
public final class CampaignPermissions {

	private CampaignPermissions() {
	}

	public static boolean can(CampaignRole role, CampaignCapability capability) {
		if (role == null) {
			return false;
		}
		if (role == CampaignRole.GAME_MASTER) {
			return true;
		}
		return switch (capability) {
			case VIEW_CAMPAIGN, POST_IN_CAMPAIGN, ROLL_DICE -> true;
			case MANAGE_CAMPAIGN, MANAGE_MEMBERS, TRANSFER_MASTERSHIP, MANAGE_TABLES -> false;
		};
	}
}
