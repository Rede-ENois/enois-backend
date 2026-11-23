package com.socialcoisa.rede.talentbank.application.port.out;

import java.util.List;

import com.socialcoisa.rede.talentbank.domain.TalentBankEvent;

/** A trilha de eventos de uma banca e de uma inscrição. */
public interface TalentBankEventRepository {

	void save(TalentBankEvent event);

	List<TalentBankEvent> findOfBank(String bankId);

	List<TalentBankEvent> findOfEnrollment(String enrollmentId);
}
