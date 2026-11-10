package com.socialcoisa.rede.marketplace.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.profile.application.port.out.AccountIdentity;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.themes.application.port.out.ThemeGalleryRepository;
import com.socialcoisa.rede.themes.domain.ThemePackage;

/**
 * Migração dos temas da comunidade para o marketplace.
 *
 * <p>A aba "Temas da comunidade" deixou de existir: todo tema publicado na galeria passa a ser
 * uma listagem do marketplace, no preço mínimo (50 faíscas), com o autor como vendedor. É
 * idempotente (só cria quando não existe listagem ativa), então roda no boot e de hora em hora
 * sem duplicar nada — inclusive para temas publicados antes desta mudança.</p>
 *
 * <p>Quem vai ser pulado é decidido em LOTE (sanções e nomes dos autores em uma consulta cada).
 * A criação da listagem em si continua uma a uma, porque cada uma é uma escrita própria — só a
 * leitura que antecede a decisão deixou de ser por tema.</p>
 */
@Component
public class MarketplaceGalleryImportWorker {

	private static final Logger LOG = LoggerFactory.getLogger(MarketplaceGalleryImportWorker.class);

	private static final int MAX_THEMES = 500;

	private final ThemeGalleryRepository themes;
	private final MarketplaceService marketplace;
	private final SanctionGateReader sanctionGate;
	private final UserProfileRepository profiles;
	private final SuspiciousNames suspicious;

	public MarketplaceGalleryImportWorker(ThemeGalleryRepository themes, MarketplaceService marketplace,
			SanctionGateReader sanctionGate, UserProfileRepository profiles,
			@Value("${rede.security.suspicious.name-patterns:probe}") String patterns) {
		this.themes = themes;
		this.marketplace = marketplace;
		this.sanctionGate = sanctionGate;
		this.profiles = profiles;
		this.suspicious = new SuspiciousNames(patterns);
	}

	@Scheduled(initialDelayString = "${rede.marketplace.gallery-import-initial-delay-ms:90000}",
		fixedDelayString = "${rede.marketplace.gallery-import-delay-ms:3600000}")
	public void importPublishedThemes() {
		List<ThemePackage> published = themes.findPublished(MAX_THEMES);
		if (published.isEmpty()) {
			return;
		}
		List<String> authorIds = published.stream().map(ThemePackage::authorId).distinct().toList();
		Set<String> banned = sanctionGate.bannedAmong(authorIds);
		Map<String, AccountIdentity> authors = profiles.identitiesOf(authorIds).stream()
			.collect(Collectors.toMap(AccountIdentity::id, Function.identity(), (first, duplicate) -> first));
		int created = 0;
		for (ThemePackage theme : published) {
			// Tema de conta banida, excluída ou de teste não volta a ser listado — senão o
			// saneamento remove e este import recria na mesma rodada.
			if (shouldSkip(theme, banned, authors)) {
				continue;
			}
			try {
				boolean listed = marketplace.ensureListed(theme.authorId(), "THEME", theme.id(), theme.name(),
					theme.description(), MarketplaceService.MIN_PRICE).isPresent();
				if (listed) {
					created++;
				}
			}
			catch (RuntimeException perTheme) {
				// Um pacote problemático não pode impedir a migração dos demais.
				LOG.warn("Falha ao levar o tema {} para o marketplace: {}", theme.id(), perTheme.getMessage());
			}
		}
		if (created > 0) {
			LOG.info("Marketplace: {} tema(s) da comunidade migrados para listagem.", created);
		}
	}

	private boolean shouldSkip(ThemePackage theme, Set<String> banned, Map<String, AccountIdentity> authors) {
		if (banned.contains(theme.authorId())) {
			return true;
		}
		if (!authors.containsKey(theme.authorId())) {
			return true;
		}
		return suspicious.in(authors.get(theme.authorId())) || suspicious.in(theme.name());
	}
}