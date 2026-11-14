package com.socialcoisa.rede.moderation.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.moderation.application.port.out.BanContentArchiveRepository;

@Repository
class BanContentArchivePersistenceAdapter implements BanContentArchiveRepository {

	private final SpringDataBanContentArchiveJpaRepository repository;

	BanContentArchivePersistenceAdapter(SpringDataBanContentArchiveJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public void archive(String sanctionId, String userId, String contentType, String contentId,
			String contentSnapshot, Instant archivedAt) {
		String id = "archive_" + UUID.randomUUID().toString().replace("-", "");
		repository.save(new BanContentArchiveJpaEntity(id, sanctionId, userId, contentType,
			contentId, contentSnapshot, archivedAt));
	}

	@Override
	public List<BanArchiveEntry> findBySanction(String sanctionId) {
		return repository.findBySanctionId(sanctionId).stream()
			.map(e -> new BanArchiveEntry(e.id(), e.sanctionId(), e.userId(), e.contentType(),
				e.contentId(), e.contentSnapshot(), e.archivedAt()))
			.toList();
	}

	@Override
	public java.util.Optional<String> findSnapshot(String sanctionId, String userId, String contentType, String contentId) {
		return repository.findFirstBySanctionIdAndUserIdAndContentTypeAndContentIdOrderByArchivedAtDesc(
			sanctionId, userId, contentType, contentId).map(BanContentArchiveJpaEntity::contentSnapshot);
	}
}
