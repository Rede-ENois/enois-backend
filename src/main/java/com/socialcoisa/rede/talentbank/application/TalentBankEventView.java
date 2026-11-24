package com.socialcoisa.rede.talentbank.application;

import com.socialcoisa.rede.talentbank.domain.TalentBankEvent;

/** Um evento da trilha de uma banca, como as duas partes leem. */
public record TalentBankEventView(String action, String actorId, String detail, String createdAt) {

	public static TalentBankEventView of(TalentBankEvent event) {
		return new TalentBankEventView(event.action().name(), event.actorId(), event.detail(),
			event.createdAt().toString());
	}
}
