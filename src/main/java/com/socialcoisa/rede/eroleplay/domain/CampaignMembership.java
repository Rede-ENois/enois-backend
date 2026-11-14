package com.socialcoisa.rede.eroleplay.domain;

/** Uma campanha do ponto de vista de quem participa dela. */
public record CampaignMembership(Campaign campaign, CampaignRole role) {
}
