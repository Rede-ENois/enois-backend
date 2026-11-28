package com.socialcoisa.rede.portfolio.adapters.out.persistence;

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
	public Map<String, Map<Integer, String>> publishedHtml(Map<String, Integer> live) {
		if (live == null || live.isEmpty()) {
			return Map.of();
		}
		Map<String, Map<Integer, String>> byOwner = new HashMap<>();
		// Uma consulta por dono, com a versão DELE: o par (dono, versão) é o que a página lê.
		live.forEach((userId, version) -> jpaRepository.findPublishedVersion(userId, version)
			.ifPresent(entity -> byOwner.computeIfAbsent(userId, owner -> new HashMap<>())
				.put(version, entity.toDomain().html())));
		return byOwner;
	}

	@Override
	public PortfolioPublication save(PortfolioPublication publication) {
		return jpaRepository.save(PortfolioPublicationJpaEntity.fromDomain(publication)).toDomain();
	}
}
