package com.socialcoisa.rede.marketplace.adapters.out.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataMarketplaceItemJpaRepository extends JpaRepository<MarketplaceItemJpaEntity, String> {

	/** Só os ids: checar existência não deve arrastar o conteúdo vendido junto. */
	@Query("select i.id from MarketplaceItemJpaEntity i where i.id in :ids")
	List<String> findExistingIds(@Param("ids") Collection<String> ids);
}
