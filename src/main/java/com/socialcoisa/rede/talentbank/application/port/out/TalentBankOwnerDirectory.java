package com.socialcoisa.rede.talentbank.application.port.out;

import java.util.Collection;
import java.util.Map;

/**
 * Os dados públicos de quem é dono de uma banca.
 *
 * <p>A elegibilidade vem do modo "Sou recrutador" da conta — é esse sinal que decide se a banca
 * pode ser administrada e se aparece na vitrine. Ele é lido do dono, nunca enviado pelo cliente, e
 * desligar o modo tira a banca da descoberta sem apagar banca, inscrições nem histórico.</p>
 */
public interface TalentBankOwnerDirectory {

	/** A conta está no modo recrutador? */
	boolean recruiter(String userId);

	/**
	 * Os dados dos donos informados, em uma leitura só.
	 *
	 * @return mapa por id; quem não existe no diretório simplesmente não entra
	 */
	Map<String, Owner> owners(Collection<String> ownerIds);

	/** O dono na vitrine: como a pessoa se apresenta e se ela pode recrutar. */
	record Owner(String userId, String name, String username, String avatar, String headline, String location,
			boolean recruiter) {
	}
}
