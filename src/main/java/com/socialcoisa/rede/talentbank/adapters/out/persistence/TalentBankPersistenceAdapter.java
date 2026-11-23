package com.socialcoisa.rede.talentbank.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.talentbank.application.port.out.TalentBankRepository;
import com.socialcoisa.rede.talentbank.domain.TalentBank;

@Repository
class TalentBankPersistenceAdapter implements TalentBankRepository {

	private final SpringDataTalentBankJpaRepository repository;

	TalentBankPersistenceAdapter(SpringDataTalentBankJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public TalentBank save(TalentBank bank) {
		return repository.save(TalentBankJpaEntity.fromDomain(bank)).toDomain();
	}

	@Override
	public Optional<TalentBank> findById(String id) {
		return repository.findById(id).map(TalentBankJpaEntity::toDomain);
	}

	@Override
	public List<TalentBank> findAllByIds(Collection<String> ids) {
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		return repository.findAllById(ids).stream().map(TalentBankJpaEntity::toDomain).toList();
	}

	@Override
	public List<TalentBank> findByOwner(String ownerId) {
		return repository.findByOwnerIdOrderByCreatedAtDescIdDesc(ownerId).stream()
			.map(TalentBankJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<TalentBank> pageDiscoverable(Instant beforeCreatedAt, String beforeId, int limit) {
		return repository.pageDiscoverableMatching("", "", beforeCreatedAt, beforeId, "%%",
			PageRequest.of(0, Math.max(1, limit))).stream()
			.map(TalentBankJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<TalentBank> pageDiscoverableMatching(String search, String area, Instant beforeCreatedAt, String beforeId,
			int limit) {
		String term = search == null ? "" : search.trim().toLowerCase(java.util.Locale.ROOT);
		String pattern = "%" + escapeLike(term) + "%";
		return repository.pageDiscoverableMatching(term, area == null ? "" : area.trim(), beforeCreatedAt, beforeId,
			pattern, PageRequest.of(0, Math.max(1, limit))).stream()
			.map(TalentBankJpaEntity::toDomain)
			.toList();
	}

	private static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
