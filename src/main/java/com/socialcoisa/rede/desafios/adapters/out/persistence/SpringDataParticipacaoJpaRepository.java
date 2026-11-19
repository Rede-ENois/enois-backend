package com.socialcoisa.rede.desafios.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.desafios.domain.ParticipacaoStatus;

interface SpringDataParticipacaoJpaRepository extends JpaRepository<ParticipacaoJpaEntity, String> {

	List<ParticipacaoJpaEntity> findByDesafioIdAndStatusOrderByCreatedAtAsc(String desafioId,
		ParticipacaoStatus status);

	Optional<ParticipacaoJpaEntity> findByDesafioIdAndAuthorIdAndStatus(String desafioId, String authorId,
		ParticipacaoStatus status);

	long countByDesafioIdAndStatus(String desafioId, ParticipacaoStatus status);

	interface ParticipacaoCount {
		String getDesafioId();

		long getTotal();
	}

	@Query("""
		select p.desafioId as desafioId, count(p) as total
		from ParticipacaoJpaEntity p
		where p.desafioId in :desafioIds and p.status = :status
		group by p.desafioId
		""")
	List<ParticipacaoCount> countByDesafioIdIn(@Param("desafioIds") List<String> desafioIds,
		@Param("status") ParticipacaoStatus status);
}
