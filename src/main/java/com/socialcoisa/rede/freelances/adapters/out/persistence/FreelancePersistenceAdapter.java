package com.socialcoisa.rede.freelances.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.freelances.application.port.out.FreelanceRepository;
import com.socialcoisa.rede.freelances.domain.Freelance;

@Repository
class FreelancePersistenceAdapter implements FreelanceRepository {
	private final SpringDataFreelanceJpaRepository repo;
	FreelancePersistenceAdapter(SpringDataFreelanceJpaRepository repo) { this.repo = repo; }
	@Override public Freelance save(Freelance f) { return repo.save(FreelanceJpaEntity.fromDomain(f)).toDomain(); }
	@Override public Optional<Freelance> findById(String id) { return repo.findById(id).map(FreelanceJpaEntity::toDomain); }
	@Override public List<Freelance> findOpen(String query, int limit, int offset) {
		int size = Math.max(1, limit);
		int page = Math.max(0, offset) / size;
		PageRequest pageable = PageRequest.of(page, size);
		List<FreelanceJpaEntity> found = query == null || query.isBlank()
			? repo.findByStatusOrderByCreatedAtDesc(Freelance.Status.OPEN, pageable)
			: repo.searchOpen(Freelance.Status.OPEN,
				com.socialcoisa.rede.shared.web.LikePattern.contains(query), pageable);
		return found.stream().skip(Math.max(0, offset) % size).map(FreelanceJpaEntity::toDomain).toList();
	}
	@Override public List<Freelance> findByUser(String userId) {
		return repo.findByUserIdOrderByCreatedAtDesc(userId).stream().map(FreelanceJpaEntity::toDomain).toList();
	}
	@Override public long countOpen() { return repo.countByStatus(Freelance.Status.OPEN); }
	@Override public void deleteById(String id) { repo.deleteById(id); }
}
