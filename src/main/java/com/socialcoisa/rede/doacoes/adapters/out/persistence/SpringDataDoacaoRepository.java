package com.socialcoisa.rede.doacoes.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.doacoes.domain.DoacaoStatus;

interface SpringDataDoacaoRepository extends JpaRepository<DoacaoJpaEntity, String> {

	Optional<DoacaoJpaEntity> findByUserIdAndStatus(String userId, DoacaoStatus status);

	List<DoacaoJpaEntity> findByUserIdOrderByCreatedAtDesc(String userId);

	List<DoacaoJpaEntity> findByStatusOrderByCreatedAtAsc(DoacaoStatus status, Pageable page);

	long countByStatus(DoacaoStatus status);

	/**
	 * Quem já tem uma doação confirmada — uma consulta só, para carimbar o selo nas listas.
	 * Os ids vêm de quem chama, que já sabe quem aparece na tela.
	 */
	@Query("select distinct d.userId from DoacaoJpaEntity d "
		+ "where d.userId in :userIds and d.status = com.socialcoisa.rede.doacoes.domain.DoacaoStatus.CONFIRMADA")
	Set<String> confirmedUserIds(@Param("userIds") Collection<String> userIds);

	/**
	 * Se algum destes identificadores de pix já confirmou uma doação (de qualquer conta). É isto que
	 * impede o mesmo comprovante — cujo texto nunca é guardado — de pagar o selo de duas pessoas.
	 */
	@Query("select count(d) from DoacaoJpaEntity d join d.pixIds p where p in :pixIds")
	long countUsingPixIds(@Param("pixIds") Collection<String> pixIds);
}
