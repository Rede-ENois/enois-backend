package com.socialcoisa.rede.communityprojects.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.communityprojects.application.port.out.CommunityProjectRepository;
import com.socialcoisa.rede.communityprojects.domain.CommunityProject;
import com.socialcoisa.rede.communityprojects.domain.CommunityProjectStatus;

@Repository
class CommunityProjectPersistenceAdapter implements CommunityProjectRepository {

	private final SpringDataCommunityProjectJpaRepository jpaRepository;

	CommunityProjectPersistenceAdapter(SpringDataCommunityProjectJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	@Override
	public CommunityProject save(CommunityProject project) {
		return jpaRepository.save(CommunityProjectJpaEntity.fromDomain(project)).toDomain();
	}

	@Override
	public Optional<CommunityProject> findById(String id) {
		return jpaRepository.findById(id).map(CommunityProjectJpaEntity::toDomain);
	}

	@Override
	public Optional<CommunityProject> findByIdForUpdate(String id) {
		return jpaRepository.findByIdForUpdate(id).map(CommunityProjectJpaEntity::toDomain);
	}

	@Override
	public Optional<CommunityProject> findByUserAndSlug(String userId, String slug) {
		return jpaRepository.findByUserIdAndSlug(userId, slug).map(CommunityProjectJpaEntity::toDomain);
	}

	@Override
	public List<CommunityProject> findByUser(String userId) {
		return jpaRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
			.map(CommunityProjectJpaEntity::toDomain).toList();
	}

	@Override
	public List<CommunityProject> findByUserAndStatus(String userId, CommunityProjectStatus status, int limit) {
		return jpaRepository
			.findByUserIdAndStatusOrderByPublishedAtDesc(userId, status, PageRequest.of(0, Math.max(1, limit)))
			.stream().map(CommunityProjectJpaEntity::toDomain).toList();
	}

	@Override
	public List<CommunityProject> findByStatus(CommunityProjectStatus status, int limit) {
		return jpaRepository.findByStatusOrderBySubmittedAtAsc(status, PageRequest.of(0, Math.max(1, limit)))
			.stream().map(CommunityProjectJpaEntity::toDomain).toList();
	}

	@Override
	public boolean existsByUserAndSlug(String userId, String slug) {
		return jpaRepository.existsByUserIdAndSlug(userId, slug);
	}

	@Override
	public SearchPage search(String query, String order, int limit, int offset) {
		String pattern = com.socialcoisa.rede.shared.web.LikePattern.contains(query);
		int size = Math.max(1, limit) + 1; // item extra responde "tem próxima?" sem count
		PageRequest page = PageRequest.of(Math.max(0, offset) / size, size);
		List<CommunityProjectJpaEntity> rows = "title".equalsIgnoreCase(order)
			? jpaRepository.searchByTitle(CommunityProjectStatus.PUBLISHED, pattern, page)
			: jpaRepository.searchRecent(CommunityProjectStatus.PUBLISHED, pattern, page);
		// PageRequest por página + offset fracionário não serve: busca manual sobre a lista.
		if (Math.max(0, offset) % size != 0) {
			rows = rows.stream().skip(Math.max(0, offset) % size).toList();
		}
		boolean hasMore = rows.size() > limit;
		return new SearchPage(rows.stream().limit(limit).map(CommunityProjectJpaEntity::toDomain).toList(), hasMore);
	}

	@Override
	public void delete(String id) {
		jpaRepository.deleteById(id);
	}

}
