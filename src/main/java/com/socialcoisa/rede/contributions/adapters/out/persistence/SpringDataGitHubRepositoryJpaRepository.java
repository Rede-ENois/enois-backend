package com.socialcoisa.rede.contributions.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataGitHubRepositoryJpaRepository extends JpaRepository<GitHubRepositoryJpaEntity, String> {

	List<GitHubRepositoryJpaEntity> findByEnabledTrueOrderByRepositoryAsc();
}
