package com.socialcoisa.rede.releasenotes.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.releasenotes.application.ReleaseNoteService;
import com.socialcoisa.rede.releasenotes.application.port.out.ReleaseNoteRepository;
import com.socialcoisa.rede.releasenotes.domain.ReleaseNote;
import com.socialcoisa.rede.releasenotes.domain.ReleaseNoteItem;

import tools.jackson.databind.ObjectMapper;

@Repository
class ReleaseNotePersistenceAdapter implements ReleaseNoteRepository {

	private final SpringDataReleaseNoteJpaRepository repository;
	private final ObjectMapper objectMapper;

	ReleaseNotePersistenceAdapter(SpringDataReleaseNoteJpaRepository repository, ObjectMapper objectMapper) {
		this.repository = repository;
		this.objectMapper = objectMapper;
	}

	@Override
	public Optional<ReleaseNote> findCurrent() {
		return repository.findById(ReleaseNoteService.RELEASE_NOTE_ID).map(this::toDomain);
	}

	@Override
	public void save(ReleaseNote releaseNote) {
		repository.save(new ReleaseNoteJpaEntity(releaseNote.id(), objectMapper.writeValueAsString(releaseNote.items()),
			releaseNote.publishedAt(), releaseNote.updatedAt()));
	}

	@Override
	public void clear() {
		if (repository.existsById(ReleaseNoteService.RELEASE_NOTE_ID)) {
			repository.deleteById(ReleaseNoteService.RELEASE_NOTE_ID);
		}
	}

	private ReleaseNote toDomain(ReleaseNoteJpaEntity entity) {
		List<ReleaseNoteItem> items = List.of(objectMapper.readValue(entity.itemsJson(), ReleaseNoteItem[].class));
		return new ReleaseNote(entity.id(), items, entity.publishedAt(), entity.updatedAt());
	}
}
