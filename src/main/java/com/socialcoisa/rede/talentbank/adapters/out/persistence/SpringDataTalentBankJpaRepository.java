package com.socialcoisa.rede.talentbank.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataTalentBankJpaRepository extends JpaRepository<TalentBankJpaEntity, String> {

	List<TalentBankJpaEntity> findByOwnerIdOrderByCreatedAtDescIdDesc(String ownerId);

	/**
	 * A vitrine pública, do mais novo para o mais antigo. O cursor traz o par (criado em, id) e o
	 * desempate por id cobre as bancas criadas no mesmo instante.
	 *
	 * <p>Os filtros entram como texto: string vazia é "sem filtro", assim a consulta não depende de
	 * parâmetro nulo — que o JPQL trataria como tipo desconhecido.</p>
	 */
	@Query("""
			select b from TalentBankJpaEntity b
			where b.discoverable = true
			  and (:beforeCreatedAt is null
			       or b.createdAt < :beforeCreatedAt
			       or (b.createdAt = :beforeCreatedAt and b.id < :beforeId))
			  and (:area = '' or lower(coalesce(b.focusArea, '')) = lower(:area))
			  and (:search = ''
			       or lower(b.title) like :pattern escape '\\' or lower(b.purpose) like :pattern escape '\\'
			       or lower(coalesce(b.description, '')) like :pattern escape '\\'
			       or lower(coalesce(b.focusArea, '')) like :pattern escape '\\'
			       or lower(coalesce(b.desiredSkills, '')) like :pattern escape '\\')
			order by b.createdAt desc, b.id desc
			""")
	List<TalentBankJpaEntity> pageDiscoverableMatching(@Param("search") String search, @Param("area") String area,
			@Param("beforeCreatedAt") Instant beforeCreatedAt, @Param("beforeId") String beforeId,
			@Param("pattern") String pattern, Pageable pageable);
}
