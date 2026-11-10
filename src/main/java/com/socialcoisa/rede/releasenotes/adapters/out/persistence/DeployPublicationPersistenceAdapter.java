package com.socialcoisa.rede.releasenotes.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.releasenotes.application.port.out.DeployPublicationRepository;
import com.socialcoisa.rede.releasenotes.domain.ReleaseNoteItem;

import tools.jackson.databind.ObjectMapper;

@Repository
class DeployPublicationPersistenceAdapter implements DeployPublicationRepository {

	private final SpringDataDeployPublicationJpaRepository repository;
	private final ObjectMapper objectMapper;

	DeployPublicationPersistenceAdapter(SpringDataDeployPublicationJpaRepository repository,
			ObjectMapper objectMapper) {
		this.repository = repository;
		this.objectMapper = objectMapper;
	}

	@Override
	public Optional<String> lastPublishedCommitSha() {
		return repository.findLastCommitSha();
	}

	@Override
	public boolean existsByCommitSha(String commitSha) {
		return repository.existsById(commitSha);
	}

	@Override
	public void save(String commitSha, List<ReleaseNoteItem> items, Instant publishedAt) {
		repository.save(new DeployPublicationJpaEntity(commitSha, objectMapper.writeValueAsString(items),
			publishedAt));
	}
}