package com.socialcoisa.rede.marketplace.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.marketplace.application.port.out.MarketplaceRepository;
import com.socialcoisa.rede.marketplace.domain.MarketplaceItem;
import com.socialcoisa.rede.marketplace.domain.MarketplaceListing;
import com.socialcoisa.rede.marketplace.domain.MarketplacePurchase;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O marketplace de itens (Fase 4): v1 vende TEMA da galeria de temas. Tudo passa por
 * ESCROW — as faíscas saem do comprador e ficam retidas até a decisão (7 dias):
 * confirmar libera para o criador (−10% de taxa); reembolso devolve 100%. O pacote que
 * quebra a validação do lado do comprador é reembolso, não prejuízo.
 */
@Service
public class MarketplaceService {

	/** Piso de preço do marketplace: 50 faíscas (5% de uma "moeda" na escala interna). */
	public static final long MIN_PRICE = CoinService.FAIS(50L);

	private final MarketplaceRepository listings;
	private final com.socialcoisa.rede.marketplace.application.port.out.MarketplaceItemRepository items;
	private final CoinService coins;
	private final Clock clock;
	/**
	 * Uma transacao por compra nos lacos (liberacao automatica, saneamento). Dentro de uma
	 * transacao so, a excecao de UMA compra (vinda de um metodo @Transactional do CoinService)
	 * marcava a transacao inteira para rollback mesmo capturada: o commit falhava e a rodada
	 * desfazia todas as outras liberacoes, toda hora, enquanto a compra ruim continuasse la.
	 */
	private final TransactionTemplate perPurchase;

	public MarketplaceService(MarketplaceRepository listings,
			com.socialcoisa.rede.marketplace.application.port.out.MarketplaceItemRepository items,
			CoinService coins, Clock clock, PlatformTransactionManager transactionManager) {
		this.listings = listings;
		this.items = items;
		this.coins = coins;
		this.clock = clock;
		this.perPurchase = new TransactionTemplate(transactionManager);
		this.perPurchase.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Transactional
	public MarketplaceListing publish(String sellerId, String itemKind, String itemId, String title,
			String description, long price) {
		MarketplaceListing.ItemKind kind = parseKind(itemKind);
		if (price < MIN_PRICE) {
			throw new ApiException(400, "PRICE_TOO_LOW", "O preço mínimo no marketplace é de 50 faíscas.");
		}
		Instant now = clock.instant();
		listings.findActiveByItem(kind.name(), itemId).ifPresent(existing -> {
			throw new ApiException(409, "ITEM_ALREADY_LISTED", "Este item já está à venda.");
		});
		return listings.saveListing(MarketplaceListing.create(sellerId, kind, itemId, title, description, price, now));
	}

	/**
	 * Garante que um item da galeria apareça no marketplace com o preço mínimo. Idempotente:
	 * se já existe listagem ativa, não faz nada. Usado na migração dos temas da comunidade.
	 */
	@Transactional
	public java.util.Optional<MarketplaceListing> ensureListed(String sellerId, String itemKind, String itemId,
			String title, String description, long price) {
		MarketplaceListing.ItemKind kind = parseKind(itemKind);
		java.util.Optional<MarketplaceListing> existing = listings.findActiveByItem(kind.name(), itemId);
		if (existing.isPresent()) {
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(publish(sellerId, itemKind, itemId, title, description, price));
	}

	/**
	 * Publica um item de CONTEÚDO (template, banner, página, post, blog ou serviço): o texto/HTML
	 * fica em marketplace_items e é entregue ao comprador. Tema continua pelo fluxo da galeria.
	 */
	@Transactional
	public MarketplaceListing publishItem(String sellerId, String itemKind, String title, String description,
			String content, long price) {
		MarketplaceListing.ItemKind kind = parseKind(itemKind);
		if (kind == MarketplaceListing.ItemKind.THEME) {
			throw new ApiException(400, "USE_THEME_FLOW",
				"Para vender um tema, use a Aparência (o pacote precisa da galeria).");
		}
		if (!StringUtils.hasText(title) || title.trim().length() < 3) {
			throw new ApiException(400, "TITLE_REQUIRED", "O título precisa de pelo menos 3 caracteres.");
		}
		if (kind != MarketplaceListing.ItemKind.SERVICE && !StringUtils.hasText(content)) {
			throw new ApiException(400, "CONTENT_REQUIRED", "Cole o conteúdo que o comprador vai receber.");
		}
		Instant now = clock.instant();
		MarketplaceItem item = items.save(MarketplaceItem.create(sellerId, kind, title, description, content, now));
		return publish(sellerId, kind.name(), item.id(), title, description, price);
	}

	/** Conteúdo comprado (o comprador é o único que pode abrir). */
	@Transactional(readOnly = true)
	public PurchasedItem contentForPurchase(String purchaseId, String buyerId) {
		MarketplacePurchase purchase = purchase(purchaseId);
		if (!purchase.ownedBy(buyerId)) {
			throw new ApiException(403, "NOT_THE_BUYER", "Só quem comprou abre este conteúdo.");
		}
		if (purchase.state() == MarketplacePurchase.State.REFUNDED) {
			throw new ApiException(403, "PURCHASE_REFUNDED", "O acesso foi encerrado após o reembolso.");
		}
		MarketplaceListing listing = listings.findListingById(purchase.listingId())
			.orElseThrow(() -> new ApiException(404, "LISTING_NOT_FOUND", "Listagem não encontrada."));
		if (listing.itemKind() == MarketplaceListing.ItemKind.THEME) {
			// Tema instala pela galeria; o front usa o itemId para buscar o pacote.
			return new PurchasedItem(listing.itemKind(), listing.title(), listing.itemId(), null);
		}
		MarketplaceItem item = items.findById(listing.itemId())
			.orElseThrow(() -> new ApiException(404, "ITEM_NOT_FOUND", "Conteúdo não encontrado."));
		return new PurchasedItem(listing.itemKind(), item.title(), item.id(), item.content());
	}

	public record PurchasedItem(MarketplaceListing.ItemKind kind, String title, String itemId, String content) {
	}

	/**
	 * Baixa de um tema pelo proprio autor: nao ha compra nem escrow — o autor ja e dono do
	 * pacote. Somente leitura, porque so valida; quem conta a instalacao e a galeria (#901).
	 */
	@Transactional(readOnly = true)
	public void ownerDownload(String listingId, String ownerId) {
		MarketplaceListing listing = listings.findListingById(listingId)
			.orElseThrow(() -> new ApiException(404, "LISTING_NOT_FOUND", "Listagem não encontrada."));
		if (!listing.ownedBy(ownerId)) {
			throw new ApiException(403, "NOT_THE_OWNER", "Só quem publicou o item baixa sem comprar.");
		}
		if (listing.itemKind() != MarketplaceListing.ItemKind.THEME) {
			throw new ApiException(400, "NOT_A_THEME", "A baixa do autor vale para temas da galeria.");
		}
	}

	@Transactional
	public MarketplacePurchase buy(String listingId, String buyerId) {
		MarketplaceListing listing = listings.findListingByIdForUpdate(listingId)
			.orElseThrow(() -> new ApiException(404, "LISTING_NOT_FOUND", "Listagem não encontrada."));
		// O importador pode anunciar o mesmo item de novo com outro id de listagem.
		if (listings.hasPurchaseForItem(buyerId, listing.itemKind().name(), listing.itemId())) {
			throw new ApiException(409, "ITEM_ALREADY_OWNED", "Você já comprou este item. Acesse Minhas compras.");
		}
		if (listing.status() != MarketplaceListing.Status.ACTIVE) {
			throw new ApiException(409, "LISTING_NOT_ACTIVE", "Esta listagem não está mais aberta.");
		}
		if (listing.ownedBy(buyerId)) {
			throw new ApiException(400, "OWN_ITEM", "Não dá para comprar o seu próprio item.");
		}
		Instant now = clock.instant();
		String purchaseRef = "mkt_" + listing.id().substring(4) + ":" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		coins.escrowHold(buyerId, listing.price(), purchaseRef,
			"Compra no marketplace: " + listing.title());
		MarketplacePurchase purchase = listings
			.savePurchase(MarketplacePurchase.create(listing.id(), buyerId, listing.sellerId(), listing.price(), now));
		// Temas são digitais: a oferta permanece aberta para outros compradores e para
		// uma nova compra após reembolso. A posse é verificada por comprador e item.
		if (listing.itemKind() != MarketplaceListing.ItemKind.THEME) {
			listings.saveListing(listing.markSold(now));
		}
		return purchase;
	}

	/** O comprador confirma (instalou e está satisfeito): criador recebe − 10%. */
	@Transactional
	public MarketplacePurchase confirm(String purchaseId, String buyerId) {
		MarketplacePurchase purchase = purchaseForUpdate(purchaseId);
		if (!purchase.ownedBy(buyerId)) {
			throw new ApiException(403, "NOT_THE_BUYER", "Só quem comprou confirma a compra.");
		}
		Instant now = clock.instant();
		MarketplacePurchase released = purchase.release(now);
		coins.escrowRelease(purchase.sellerId(), purchase.price(), escrowRef(purchase),
			"Venda no marketplace (escrow liberado)");
		return listings.savePurchase(released);
	}

	/** O comprador pede reembolso dentro da janela: devolve 100%. */
	@Transactional
	public MarketplacePurchase refund(String purchaseId, String buyerId) {
		MarketplacePurchase purchase = purchaseForUpdate(purchaseId);
		if (!purchase.ownedBy(buyerId)) {
			throw new ApiException(403, "NOT_THE_BUYER", "Só quem comprou pede o reembolso.");
		}
		Instant now = clock.instant();
		MarketplacePurchase refunded = purchase.refund(now);
		coins.escrowRefund(purchase.buyerId(), purchase.price(), escrowRef(purchase),
			"Reembolso de compra no marketplace");
		// Compatibilidade com compras antigas, cujos anúncios saíam da loja ao vender.
		listings.findListingByIdForUpdate(purchase.listingId()).ifPresent(listing -> {
			if (listing.status() == MarketplaceListing.Status.SOLD
					&& listings.findActiveByItem(listing.itemKind().name(), listing.itemId()).isEmpty()) {
				listings.saveListing(listing.reopen(now));
			}
		});
		return listings.savePurchase(refunded);
	}

	/**
	 * Auto-liberação: compras HELD sem decisão passada a janela liberam para o vendedor
	 * (o criador não fica esperando para sempre). Roda por hora.
	 */
	@Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
	public void releaseExpired() {
		Instant now = clock.instant();
		for (MarketplacePurchase purchase : listings.findHeldOlderThan(50)) {
			if (purchase.decisionExpired(now)) {
				try {
					perPurchase.executeWithoutResult(status -> {
						MarketplacePurchase current = purchaseForUpdate(purchase.id());
						if (!current.decisionExpired(now)) return;
						coins.escrowRelease(current.sellerId(), current.price(), escrowRef(current),
							"Venda no marketplace (liberação automática)");
						listings.savePurchase(current.release(now));
					});
				} catch (RuntimeException ignored) {
					// a próxima rodada tenta de novo; nada aqui pode derrubar o worker
				}
			}
		}
	}

	public List<MarketplaceListing> catalog(int limit) {
		return catalog(limit, null, null);
	}

	/** Todas as listagens, para a limpeza de resquícios do ataque. */
	public List<MarketplaceListing> allListings(int limit) {
		return listings.findAll(limit);
	}

	/**
	 * Remove uma listagem com reembolso do que estiver no escrow. Usado na limpeza dos resquícios:
	 * um tema que não existe mais (ou que não pertence a quem listou) não fica à venda.
	 */
	@Transactional
	public boolean sanitizeListing(MarketplaceListing listing) {
		if (listing.status() != MarketplaceListing.Status.ACTIVE) {
			return false;
		}
		Instant now = clock.instant();
		for (MarketplacePurchase purchase : listings.findHeldByListing(listing.id())) {
			try {
				perPurchase.executeWithoutResult(status -> {
					coins.escrowRefund(purchase.buyerId(), purchase.price(), escrowRef(purchase),
						"Reembolso: listagem removida na limpeza do marketplace");
					listings.savePurchase(purchase.refund(now));
				});
			}
			catch (RuntimeException ignored) {
				// se o reembolso falhar, a listagem ainda sai do ar; o suporte trata o valor.
			}
		}
		// DELETE em vez de status REMOVED: o índice único (item_kind, item_id, status) recusa
		// uma segunda linha REMOVED do mesmo item, e era isso que derrubava o saneamento.
		listings.deleteListing(listing.id());
		return true;
	}

	/** Catálogo com busca (título/descrição) e filtro de categoria; sem nada, devolve tudo. */
	public List<MarketplaceListing> catalog(int limit, String q, String kind) {
		String needle = q == null ? null : q.trim().toLowerCase(Locale.ROOT);
		String wantedKind = kind == null || kind.isBlank() ? null : kind.trim().toUpperCase(Locale.ROOT);
		// Janela fixa de 200 para filtrar em memoria e teto de 100 na resposta: antes era
		// Math.max(limit, 200), e um limit enorme puxava a tabela inteira; limit negativo dava 500.
		int size = Math.min(Math.max(limit, 1), 100);
		return listings.findActive(200).stream()
			.filter(listing -> wantedKind == null || listing.itemKind().name().equals(wantedKind))
			.filter(listing -> needle == null || needle.isEmpty()
				|| listing.title().toLowerCase(Locale.ROOT).contains(needle)
				|| (listing.description() != null
					&& listing.description().toLowerCase(Locale.ROOT).contains(needle)))
			.limit(size)
			.toList();
	}

	public List<MarketplaceListing> mine(String sellerId) {
		return listings.findBySeller(sellerId);
	}

	/** Compras de quem comprou (o painel do escrow: confirmar ou pedir reembolso). */
	public List<MarketplacePurchase> purchasesByBuyer(String buyerId) {
		return listings.findPurchasesByBuyer(buyerId);
	}

	/** Compras com a categoria e o título da listagem, para agrupar em "Minhas compras". */
	@Transactional(readOnly = true)
	public List<PurchaseDetail> purchasesDetailed(String buyerId) {
		return listings.findPurchasesByBuyer(buyerId).stream().map(purchase -> {
			MarketplaceListing listing = listings.findListingById(purchase.listingId()).orElse(null);
			return new PurchaseDetail(purchase, listing == null ? null : listing.itemKind(),
				listing == null ? null : listing.title(), listing == null ? null : listing.itemId());
		}).toList();
	}

	public record PurchaseDetail(MarketplacePurchase purchase, MarketplaceListing.ItemKind kind, String title,
			String itemId) {
	}

	/** Vendas de quem vendeu (o painel do escrow: acompanhar as liberações). */
	public List<MarketplacePurchase> salesBySeller(String sellerId) {
		return listings.findSalesBySeller(sellerId);
	}

	private MarketplaceListing listing(String id) {
		return listings.findListingById(id)
			.orElseThrow(() -> new ApiException(404, "LISTING_NOT_FOUND", "Listagem não encontrada."));
	}

	private MarketplacePurchase purchase(String id) {
		return listings.findPurchaseById(id)
			.orElseThrow(() -> new ApiException(404, "PURCHASE_NOT_FOUND", "Compra não encontrada."));
	}

	private MarketplacePurchase purchaseForUpdate(String id) {
		return listings.findPurchaseByIdForUpdate(id)
			.orElseThrow(() -> new ApiException(404, "PURCHASE_NOT_FOUND", "Compra não encontrada."));
	}

	private MarketplaceListing.ItemKind parseKind(String raw) {
		if (!StringUtils.hasText(raw)) {
			return MarketplaceListing.ItemKind.THEME;
		}
		try {
			return MarketplaceListing.ItemKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ApiException(400, "INVALID_ITEM_KIND", "Tipo de item inválido.");
		}
	}

	private String escrowRef(MarketplacePurchase purchase) {
		return "mcmp:" + purchase.id();
	}
}
