package com.socialcoisa.rede.contributions.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.contributions.domain.ContributionCategory;
import com.socialcoisa.rede.contributions.domain.ContributionKind;
import com.socialcoisa.rede.contributions.domain.ContributionStatus;
import com.socialcoisa.rede.contributions.domain.GitHubContribution;

public interface GitHubContributionRepository {

	GitHubContribution save(GitHubContribution contribution);

	Optional<GitHubContribution> findById(String id);

	/** Chave idempotente: o mesmo objeto externo não vira duas evidências. */
	Optional<GitHubContribution> findByRepositoryAndKindAndNumber(String repository, ContributionKind kind,
			int number);

	List<GitHubContribution> list(ContributionStatus status, ContributionKind kind, int limit);

	/** Evidências que contam para o selo: validadas, não revertidas, da categoria pedida. */
	List<GitHubContribution> findCountableByUser(String userId, ContributionCategory category, int limit);

	int countCountableByUser(String userId, ContributionCategory category);

	List<GitHubContribution> findAllByUser(String userId, int limit);
}
