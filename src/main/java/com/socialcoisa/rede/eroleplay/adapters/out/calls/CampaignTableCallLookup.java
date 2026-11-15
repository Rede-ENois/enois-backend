package com.socialcoisa.rede.eroleplay.adapters.out.calls;

import java.util.List;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.eroleplay.application.CampaignService;
import com.socialcoisa.rede.eroleplay.domain.CampaignCapability;
import com.socialcoisa.rede.eroleplay.domain.CampaignPermissions;
import com.socialcoisa.rede.eroleplay.domain.CampaignRole;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * A mesa do Éroleplay, para as chamadas. A existência da mesa e o papel de quem pede são relidos
 * do banco a cada verificação: id inventado não abre sala de voz, e quem não participa da
 * campanha não entra na voz da mesa — nem pedindo direto ao serviço de chamadas.
 */
@Component
public class CampaignTableCallLookup implements com.socialcoisa.rede.calls.application.port.out.TableCallLookup {

	private final CampaignService campaigns;

	public CampaignTableCallLookup(CampaignService campaigns) {
		this.campaigns = campaigns;
	}

	@Override
	public String requireCampaignOf(String tableId) {
		return campaigns.tableCampaignId(tableId);
	}

	@Override
	public void requireMemberOfCampaign(String tableId, String userId) {
		CampaignRole role = campaigns.roleOf(campaigns.tableCampaignId(tableId), userId);
		if (!CampaignPermissions.can(role, CampaignCapability.VIEW_CAMPAIGN)) {
			throw new ApiException(403, "NOT_CAMPAIGN_MEMBER", "Voce nao participa desta campanha.");
		}
	}

	@Override
	public List<String> campaignMembersOf(String tableId) {
		return campaigns.tableAudience(tableId);
	}
}
