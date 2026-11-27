package com.socialcoisa.rede.portfolio.adapters.out.persistence;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.portfolio.application.port.out.PortfolioPublicationRepository;
import com.socialcoisa.rede.portfolio.domain.PortfolioPublication;

@Repository
class PortfolioPublicationAdapter implements PortfolioPublicationRepository {

	private final SpringDataPortfolioPublicationJpaRepository jpaRepository;

	PortfolioPublicationAdapter(SpringDataPortfolioPublicationJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	@Override
	public Optional<PortfolioPublication> findByVersion(String userId, int version) {
		return jpaRepository.findByUserIdAndVersion(userId, version).map(PortfolioPublicationJpaEntity::toDomain);
	}

	@Override
	public Optional<PortfolioPublication> findLatest(String userId) {
		return jpaRepository.findFirstByUserIdOrderByVersionDesc(userId)
			.map(PortfolioPublicationJpaEntity::toDomain);
	}

	@Override
	public Map<String, Map<Integer, String>> publishedHtml(Collection<String> userIds, Collection<Integer> versions) {
		if (userIds.isEmpty() || versions.isEmpty()) {
			return Map.of();
		}
		Map<String, Map<Integer, String>> byOwner = new HashMap<>();
		for (PortfolioPublication publication : jpaRepository.findPublished(userIds, versions).stream()
			.map(PortfolioPublicationJpaEntity::toDomain)
			.toList()) {
			byOwner.computeIfAbsent(publication.userId(), owner -> new HashMap<>())
				.put(publication.version(), publication.html());
		}
		return byOwner;
	}

	@Override
	public PortfolioPublication save(PortfolioPublication publication) {
		return jpaRepository.save(PortfolioPublicationJpaEntity.fromDomain(publication)).toDomain();
	}
}
