package com.socialcoisa.rede.talentbank.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.talentbank.domain.EnrollmentState;
import com.socialcoisa.rede.talentbank.domain.TalentBankEnrollment;

/**
 * As inscrições de uma banca e as inscrições de uma pessoa.
 *
 * <p>A leitura do recrutador sempre passa pelo par (banca, candidato): o método recebe a banca e
 * filtra por ela, então um id de inscrição de outra banca não devolve nada.</p>
 */
public interface TalentBankEnrollmentRepository {

	TalentBankEnrollment save(TalentBankEnrollment enrollment);

	Optional<TalentBankEnrollment> findById(String id);

	/** A inscrição de uma pessoa numa banca — o par é único. */
	Optional<TalentBankEnrollment> findByBankAndCandidate(String bankId, String candidateId);

	/** As inscrições de uma pessoa, das mais recentes para as mais antigas. */
	List<TalentBankEnrollment> findByCandidate(String candidateId);

	/** Os ids dos candidatos com inscrição num estado, na banca — é o conjunto que o recrutador busca. */
	List<String> candidateIdsByState(String bankId, EnrollmentState state);

	/** Os ids de todos os candidatos da banca, em qualquer estado. */
	List<String> candidateIdsOfBank(String bankId);

	/** Quantas inscrições a banca tem num estado — o total do painel. */
	long countByBankAndState(String bankId, EnrollmentState state);

	/**
	 * Uma página das inscrições da banca, da mais atualizada para a mais antiga.
	 *
	 * @param candidateIds quando não é nulo, só estas inscrições entram na página
	 * @param state quando não é nulo, só as inscrições neste estado entram
	 */
	List<TalentBankEnrollment> pageOfBank(String bankId, Collection<String> candidateIds, EnrollmentState state,
			Instant beforeUpdatedAt, String beforeId, int limit);
}
