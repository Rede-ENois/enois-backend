package com.socialcoisa.rede.jobs.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.jobs.domain.TalentPoolOptIn;

/**
 * O consentimento do banco de talentos.
 *
 * <p>Toda leitura que o recrutamento faz passa por aqui, e só as linhas ativas
 * ({@code withdrawingAt} nulo) existem para ele: não-retirado é a condição da consulta, não uma
 * checagem que um chamador novo possa esquecer de fazer.
 */
public interface TalentPoolRepository {

	TalentPoolOptIn save(TalentPoolOptIn optIn);

	/** O consentimento do par, ativo ou já retirado — o candidato precisa ver o próprio estado. */
	Optional<TalentPoolOptIn> findByCandidateAndCompany(String candidateId, String companyId);

	/** Os consentimentos ativos de uma pessoa, mais recentes primeiro. */
	List<TalentPoolOptIn> activeOfCandidate(String candidateId);

	/**
	 * Uma página do banco de talentos de uma organização, com o cursor keyset
	 * (consentedAt desc, id desc). Só linhas ativas.
	 *
	 * @param beforeConsentedAt instante da última linha entregue (nulo = primeira página)
	 * @param beforeId id da última linha entregue: desempata dentro do mesmo instante e diz se o
	 * próximo bloco entra ou sai do instante
	 */
	List<TalentPoolOptIn> page(String companyId, Instant beforeConsentedAt, String beforeId, int limit);

	int countActive(String companyId);

	// ---------- LGPD ----------

	/** Apaga os consentimentos da pessoa — exclusão da conta é exclusão do que ela consentiu. */
	void purgeCandidate(String candidateId);

	/**
	 * Expurga as linhas retiradas antes de {@code before}, de todas as organizações de uma vez: a
	 * retirada já tirou o acesso no ato, aqui só some o registro.
	 */
	int purgeWithdrawnBefore(Instant before);
}
