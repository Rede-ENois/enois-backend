package com.socialcoisa.rede.media.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.media.domain.MediaRenderJobStatus;

interface SpringDataMediaRenderJobJpaRepository extends JpaRepository<MediaRenderJobJpaEntity, String> {

	List<MediaRenderJobJpaEntity> findByStatusAndUpdatedAtLessThanEqualOrderByUpdatedAtAsc(
			MediaRenderJobStatus status, Instant updatedBefore, Pageable pageable);

	List<MediaRenderJobJpaEntity> findByStatusOrderByUpdatedAtAsc(MediaRenderJobStatus status, Pageable pageable);

	/** Pedidos com folha gravada, do carimbo mais antigo para o mais novo. */
	List<MediaRenderJobJpaEntity> findByStatusInAndPageKeysIsNotNullAndPageKeysNot(Collection<MediaRenderJobStatus> status,
		String semPagina, Pageable pageable);

	/**
	 * O documento dono desta chave de folha. A comparação é por trecho exato da lista de chaves — a
	 * chave da folha não tem curinga, então o casamento é o da própria string, não um LIKE.
	 */
	@Query("select j from MediaRenderJobJpaEntity j where locate(:objectKey, j.pageKeys) > 0")
	List<MediaRenderJobJpaEntity> findByPageKeysContaining(@Param("objectKey") String objectKey, Pageable pageable);

}
