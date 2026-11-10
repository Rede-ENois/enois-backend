package com.socialcoisa.rede.marketplace.application.port.out;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import com.socialcoisa.rede.marketplace.domain.MarketplaceItem;

public interface MarketplaceItemRepository {

	MarketplaceItem save(MarketplaceItem item);

	Optional<MarketplaceItem> findById(String id);

	/**
	 * Quais destes ids existem, em uma consulta. Só a existência é perguntada — trazer o conteúdo
	 * (longtext) de cada item para conferir se ele existe é carregar o produto inteiro à toa.
	 */
	Set<String> existingIds(Collection<String> ids);
}