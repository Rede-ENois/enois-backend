package com.socialcoisa.rede.marketplace.application;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.marketplace.application.port.out.MarketplaceItemRepository;
import com.socialcoisa.rede.marketplace.domain.MarketplaceListing;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.application.port.out.AccountIdentity;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.themes.application.port.out.ThemeGalleryRepository;
import com.socialcoisa.rede.themes.application.port.out.ThemeIdentity;

/**
 * Limpeza única dos resquícios do ataque no marketplace.
 *
 * <p>Listagens cujo item não existe mais (tema apagado), cujo vendedor não é o autor do tema ou
 * cujo vendedor foi BANIDO (contas do ataque) são removidas, com reembolso do que estiver retido
 * no escrow. Depois, todos os temas publicados na galeria entram à venda pelo piso de 50 faíscas —
 * é o que substitui a aba "Temas da comunidade". Idempotente: roda no boot e de hora em hora.</p>
 *
 * <p>A decisão é tomada em LOTE: o que a listagem precisa saber (sanção do vendedor, perfil do
 * vendedor, item ou tema) é buscado em uma consulta por conjunto, não por linha. A rotina varre
 * até 500 listagens e, em quase todas, a resposta é "está tudo bem" — consultar linha a linha
 * custava quatro viagens ao banco por listagem, ~2.000 consultas por hora por instância.</p>
 */
@Component
public class MarketplaceSanitizeWorker {

	private static final Logger LOG = LoggerFactory.getLogger(MarketplaceSanitizeWorker.class);

	private static final int MAX_LISTINGS = 500;

	private final MarketplaceService marketplace;
	private final ThemeGalleryRepository themes;
	private final MarketplaceItemRepository items;
	private final SanctionGateReader sanctionGate;
	private final UserProfileRepository profiles;
	private final SuspiciousNames suspicious;
	private final MarketplaceGalleryImportWorker galleryImport;

	public MarketplaceSanitizeWorker(MarketplaceService marketplace, ThemeGalleryRepository themes,
			MarketplaceItemRepository items, SanctionGateReader sanctionGate, UserProfileRepository profiles,
			@Value("${rede.security.suspicious.name-patterns:probe}") String patterns,
			MarketplaceGalleryImportWorker galleryImport) {
		this.marketplace = marketplace;
		this.themes = themes;
		this.items = items;
		this.sanctionGate = sanctionGate;
		this.profiles = profiles;
		this.suspicious = new SuspiciousNames(patterns);
		this.galleryImport = galleryImport;
	}

	@Scheduled(initialDelayString = "${rede.marketplace.sanitize-initial-delay-ms:90000}",
		fixedDelayString = "${rede.marketplace.sanitize-delay-ms:3600000}")
	public void sanitize() {
		sanitizeNow();
	}

	/**
	 * Mesma rotina, chamável pelo endpoint administrativo (diagnóstico e limpeza sob demanda).
	 *
	 * SEM @Transactional no método inteiro de propósito: cada sanitizeListing tem a própria
	 * transação. Com uma transação única, a primeira listagem que falhasse marcava a transação
	 * como rollback-only e o lote inteiro era descartado no commit — as "Probe Escrow" nunca
	 * saíam do catálogo.
	 */
	public int sanitizeNow() {
		List<MarketplaceListing> listings = marketplace.allListings(MAX_LISTINGS).stream()
			.filter(listing -> listing.status() == MarketplaceListing.Status.ACTIVE)
			.toList();
		Evidence evidence = loadEvidence(listings);
		int removed = 0;
		for (MarketplaceListing listing : listings) {
			try {
				if (isOrphan(listing, evidence) && marketplace.sanitizeListing(listing)) {
					removed++;
				}
			}
			catch (RuntimeException perListing) {
				LOG.warn("Falha ao limpar a listagem {}: {}", listing.id(), perListing.getMessage());
			}
		}
		// Tudo o que está publicado na galeria volta à venda pelo piso de 50 faíscas.
		galleryImport.importPublishedThemes();
		if (removed > 0) {
			LOG.info("Marketplace: {} listagem(ns) de resquício removidas; temas da comunidade à venda.",
				removed);
		}
		return removed;
	}

	/** O que a decisão de uma listagem precisa saber, buscado de uma vez para o lote inteiro. */
	private record Evidence(Set<String> bannedSellers, Map<String, AccountIdentity> sellerProfiles,
			Map<String, ThemeIdentity> themes, Set<String> itemIds) {
	}

	private Evidence loadEvidence(List<MarketplaceListing> listings) {
		List<String> sellerIds = listings.stream()
			.map(MarketplaceListing::sellerId)
			.filter(Objects::nonNull)
			.distinct()
			.toList();
		List<String> themeIds = listings.stream()
			.filter(listing -> listing.itemKind() == MarketplaceListing.ItemKind.THEME)
			.map(MarketplaceListing::itemId)
			.filter(Objects::nonNull)
			.distinct()
			.toList();
		List<String> itemIds = listings.stream()
			.filter(listing -> listing.itemKind() != MarketplaceListing.ItemKind.THEME)
			.map(MarketplaceListing::itemId)
			.filter(Objects::nonNull)
			.distinct()
			.toList();
		return new Evidence(
			sellerIds.isEmpty() ? Set.of() : sanctionGate.bannedAmong(sellerIds),
			sellerIds.isEmpty() ? Map.of() : byId(profiles.identitiesOf(sellerIds), AccountIdentity::id),
			themeIds.isEmpty() ? Map.of() : byId(themes.authorsOf(themeIds), ThemeIdentity::id),
			itemIds.isEmpty() ? Set.of() : items.existingIds(itemIds));
	}

	private static <T> Map<String, T> byId(List<T> found, Function<T, String> id) {
		return found.stream().collect(Collectors.toMap(id, Function.identity(), (first, duplicate) -> first));
	}

	private boolean isOrphan(MarketplaceListing listing, Evidence evidence) {
		// Vendedor banido (contas do ataque): a listagem cai, com reembolso do escrow.
		if (listing.sellerId() != null && evidence.bannedSellers().contains(listing.sellerId())) {
			return true;
		}
		// Contas de teste/ataque reconhecidas pelo mesmo padrão da triagem de suspeitos
		// ("probe"), no NOME do vendedor ou no TÍTULO da listagem.
		if (listing.sellerId() != null && suspicious.in(evidence.sellerProfiles().get(listing.sellerId()))) {
			return true;
		}
		if (suspicious.in(listing.title())) {
			return true;
		}
		// Vendedor excluído: a conta não existe mais, então a listagem não pode continuar.
		if (listing.sellerId() != null && !evidence.sellerProfiles().containsKey(listing.sellerId())) {
			return true;
		}
		if (listing.itemId() == null) {
			// Sem item não há como provar que a listagem é resquício; a versão anterior também não
			// a removia (a consulta por id nulo falhava e caía no catch por listagem).
			return false;
		}
		if (listing.itemKind() == MarketplaceListing.ItemKind.THEME) {
			ThemeIdentity theme = evidence.themes().get(listing.itemId());
			return theme == null || !theme.authorId().equals(listing.sellerId());
		}
		return !evidence.itemIds().contains(listing.itemId());
	}
}