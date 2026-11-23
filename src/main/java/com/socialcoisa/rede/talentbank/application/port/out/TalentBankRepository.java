package com.socialcoisa.rede.talentbank.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.talentbank.domain.TalentBank;

/**
 * A leitura e a escrita das bancas de recrutamento.
 *
 * <p>Toda consulta de painel vai pelo dono; a vitrine pública vai por outra porta, porque é a única
 * que mostra bancas de gente que não é quem está perguntando — e ela não devolve nada de quem se
 * inscreveu.</p>
 */
public interface TalentBankRepository {

	TalentBank save(TalentBank bank);

	Optional<TalentBank> findById(String id);

	/** Várias bancas em uma leitura só — as que a pessoa tem inscrição. */
	List<TalentBank> findAllByIds(Collection<String> ids);

	/** As bancas do dono, da mais recente para a mais antiga. */
	List<TalentBank> findByOwner(String ownerId);

	/**
	 * A vitrine pública, com o cursor por (criado em, id).
	 *
	 * @param beforeCreatedAt exclusivo; nulo começa do início
	 * @param beforeId exclusivo, desempate
	 */
	List<TalentBank> pageDiscoverable(Instant beforeCreatedAt, String beforeId, int limit);

	/**
	 * A vitrine pública filtrada: busca livre (título, finalidade, descrição, área e skills) e
	 * área exata.
	 */
	List<TalentBank> pageDiscoverableMatching(String search, String area, Instant beforeCreatedAt, String beforeId,
			int limit);
}
