package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataNodeCertificateJpaRepository extends JpaRepository<NodeCertificateJpaEntity, String> {

	/**
	 * O certificado vigente do nó: o mais recente entre os não revogados.
	 *
	 * <p>"Mais recente" e não "o único" de propósito: durante uma rotação os dois ficam válidos por
	 * um instante, e é o novo que o handshake deve aceitar.</p>
	 */
	@Query("select c from NodeCertificateJpaEntity c where c.nodeId = :nodeId and c.revokedAt is null "
			+ "order by c.createdAt desc")
	List<NodeCertificateJpaEntity> findActiveByNode(@Param("nodeId") String nodeId);

	@Query("select c from NodeCertificateJpaEntity c where c.nodeId in :nodeIds and c.revokedAt is null "
			+ "order by c.createdAt desc")
	List<NodeCertificateJpaEntity> findActiveByNodes(@Param("nodeIds") Collection<String> nodeIds);

	boolean existsBySerial(String serial);

	boolean existsByFingerprintSha256(String fingerprintSha256);

	@Modifying
	@Query("update NodeCertificateJpaEntity c set c.revokedAt = :now, c.revokedBy = :actor, "
			+ "c.revokedReason = :reason where c.nodeId = :nodeId and c.revokedAt is null")
	int revokeActiveByNode(@Param("nodeId") String nodeId, @Param("actor") String actor,
			@Param("reason") String reason, @Param("now") Instant now);
}