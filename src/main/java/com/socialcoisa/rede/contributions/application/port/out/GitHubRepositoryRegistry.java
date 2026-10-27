package com.socialcoisa.rede.contributions.application.port.out;

import java.util.List;

import com.socialcoisa.rede.contributions.domain.GitHubRepository;

public interface GitHubRepositoryRegistry {

	List<GitHubRepository> findAll();

	List<GitHubRepository> findEnabled();

	GitHubRepository save(GitHubRepository repository);
}
