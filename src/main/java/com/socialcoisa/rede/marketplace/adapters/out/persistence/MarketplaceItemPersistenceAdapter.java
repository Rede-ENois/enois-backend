package com.socialcoisa.rede.marketplace.adapters.out.persistence;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.marketplace.application.port.out.MarketplaceItemRepository;
import com.socialcoisa.rede.marketplace.domain.MarketplaceItem;

@Repository
class MarketplaceItemPersistenceAdapter implements MarketplaceItemRepository {

	private final SpringDataMarketplaceItemJpaRepository repository;

	MarketplaceItemPersistenceAdapter(SpringDataMarketplaceItemJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public MarketplaceItem save(MarketplaceItem item) {
		return repository.save(MarketplaceItemJpaEntity.from(item)).toDomain();
	}

	@Override
	public Optional<MarketplaceItem> findById(String id) {
		return repository.findById(id).map(MarketplaceItemJpaEntity::toDomain);
	}

	@Override
	public Set<String> existingIds(Collection<String> ids) {
		return Set.copyOf(repository.findExistingIds(ids));
	}
}
