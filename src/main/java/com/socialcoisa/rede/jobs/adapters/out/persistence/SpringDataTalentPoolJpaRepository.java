package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataTalentPoolJpaRepository extends JpaRepository<TalentPoolJpaEntity, String> {

	Optional<TalentPoolJpaEntity> findByCandidateIdAndCompanyId(String candidateId, String companyId);

	List<TalentPoolJpaEntity> findByCandidateIdAndWithdrawingAtIsNullOrderByConsentedAtDesc(String candidateId);

	/**
	 * A página da organização: só linhas ativas, do consentimento mais novo para o mais antigo.
	 *
	 * <p>O cursor vem em dois parâmetros nulos quando é a primeira página. O desempate por id
	 * (segundo parâmetro) cobre o instante repetido — a coluna guarda segundos, e paginar só pelo
	 * instante pularia ou repetiria as linhas que empataram com ele.</p>
	 */
	@Query("""
			select o from TalentPoolJpaEntity o
			where o.companyId = :companyId and o.withdrawingAt is null
			  and (:beforeConsentedAt is null
			       or o.consentedAt < :beforeConsentedAt
			       or (o.consentedAt = :beforeConsentedAt and o.id < :beforeId))
			order by o.consentedAt desc, o.id desc
			""")
	List<TalentPoolJpaEntity> pageOfCompany(@Param("companyId") String companyId,
			@Param("beforeConsentedAt") Instant beforeConsentedAt, @Param("beforeId") String beforeId,
			Pageable pageable);

	long countByCompanyIdAndWithdrawingAtIsNull(String companyId);

	@Modifying
	@Query("delete from TalentPoolJpaEntity o where o.candidateId = :candidateId")
	int deleteByCandidateId(@Param("candidateId") String candidateId);

	@Modifying
	@Query("delete from TalentPoolJpaEntity o where o.withdrawingAt is not null and o.withdrawingAt < :before")
	int deleteWithdrawnBefore(@Param("before") Instant before);
}
