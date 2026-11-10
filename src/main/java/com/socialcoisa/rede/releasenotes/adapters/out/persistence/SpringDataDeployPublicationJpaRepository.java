package com.socialcoisa.rede.releasenotes.adapters.out.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface SpringDataDeployPublicationJpaRepository extends JpaRepository<DeployPublicationJpaEntity, String> {

	@Query("SELECT p.commitSha FROM DeployPublicationJpaEntity p ORDER BY p.publishedAt DESC LIMIT 1")
	Optional<String> findLastCommitSha();
}