package com.socialcoisa.rede.cosmetics.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.cosmetics.application.port.out.CommunityCosmeticRepository;
import com.socialcoisa.rede.cosmetics.domain.CommunityCosmetic;
import com.socialcoisa.rede.cosmetics.domain.CommunityCosmeticKind;
import com.socialcoisa.rede.cosmetics.domain.CommunityCosmeticStatus;
import com.socialcoisa.rede.cosmetics.domain.CosmeticKind;
import com.socialcoisa.rede.contributors.application.port.out.VerifiedContributorReader;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.web.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Casos de uso dos visuais criados pela comunidade; separado da loja nativa de molduras. */
@Service
public class CommunityCosmeticsService {

	private static final int MAX_MANIFEST_CHARS = 16_384;
	private static final int MAX_DESCRIPTION_CHARS = 300;
	private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
	private static final Set<String> LICENSES = Set.of("ORIGINAL", "OPEN_SOURCE", "COMMERCIAL_LICENSE");
	private static final Set<String> NAME_THEMES = Set.of("DEFAULT", "PIXEL", "GOTHIC", "NEON", "GLITCH", "CANDY",
		"RETRO", "OCEAN", "SUNSET", "MATRIX", "GOLD", "ICE");
	private static final Set<String> NAME_FONTS = Set.of("SYSTEM", "SERIF", "MONO", "ROUNDED", "DISPLAY", "PIXEL",
		"GOTHIC", "CURSIVE", "CONDENSED", "COMIC", "SLAB");
	private static final Map<CommunityCosmeticKind, Set<String>> TOP_LEVEL_KEYS = Map.of(
		CommunityCosmeticKind.FONT, Set.of("version", "kind", "family", "assetRef", "fallback"),
		CommunityCosmeticKind.PALETTE, Set.of("version", "kind", "colors"),
		CommunityCosmeticKind.EFFECT, Set.of("version", "kind", "effect"),
		CommunityCosmeticKind.PROFILE_BORDER, Set.of("version", "kind", "layers"),
		CommunityCosmeticKind.BUNDLE, Set.of("version", "kind", "cosmeticIds"));

	private final CommunityCosmeticRepository cosmetics;
	private final Clock clock;
	private final ObjectMapper objectMapper;
	private final UserProfileRepository profiles;
	private final VerifiedContributorReader verifiedContributors;
	private final CosmeticCatalog shopCatalog;
	private final CosmeticsService shop;

	public CommunityCosmeticsService(CommunityCosmeticRepository cosmetics, Clock clock, ObjectMapper objectMapper,
		UserProfileRepository profiles, VerifiedContributorReader verifiedContributors, CosmeticCatalog shopCatalog,
		CosmeticsService shop) {
		this.cosmetics = cosmetics;
		this.clock = clock;
		this.objectMapper = objectMapper;
		this.profiles = profiles;
		this.verifiedContributors = verifiedContributors;
		this.shopCatalog = shopCatalog;
		this.shop = shop;
	}

	/**
	 * Theme: one of the free presets, or the id of a NAME_STYLE shop item the person owns (stored as
	 * the id, e.g. "nome-saci"). Font and color are optional (null = design system default).
	 */
	@Transactional
	public UserProfile updateNameStyle(String userId, String theme, String font, String color) {
		String normalizedTheme = normalizeTheme(userId, theme);
		if (font != null && !NAME_FONTS.contains(font)) {
			throw new ApiException(400, "INVALID_NAME_FONT", "Fonte de nome inválida.");
		}
		if (color != null && !HEX.matcher(color).matches()) {
			throw new ApiException(400, "INVALID_NAME_COLOR", "A cor do nome precisa ser hexadecimal.");
		}
		UserProfile profile = profiles.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Perfil não encontrado."));
		profile.updateNameStyle(normalizedTheme, font, color == null ? null : color.toLowerCase(Locale.ROOT),
			clock.instant());
		return profiles.save(profile);
	}

	private String normalizeTheme(String userId, String theme) {
		String trimmed = theme == null || theme.isBlank() ? "DEFAULT" : theme.trim();
		var shopTheme = shopCatalog.item(trimmed).filter(item -> item.kind() == CosmeticKind.NAME_STYLE);
		if (shopTheme.isPresent()) {
			if (!shop.owns(userId, trimmed)) {
				throw new ApiException(403, "NOT_OWNED", "Você precisa ter este estilo de nome antes de usar.");
			}
			return trimmed;
		}
		String preset = trimmed.toUpperCase(Locale.ROOT);
		if (!NAME_THEMES.contains(preset)) {
			throw new ApiException(400, "INVALID_NAME_THEME", "Tema de nome inválido.");
		}
		return preset;
	}

	/** Cor da tag "Contribuinte"; só quem tem contribuição verificada pode escolher (null = padrão). */
	@Transactional
	public UserProfile updateContributorTagColor(String userId, String color) {
		if (verifiedContributors.find(userId).isEmpty()) {
			throw new ApiException(403, "CONTRIBUTOR_NOT_VERIFIED",
				"A tag de contribuinte é exclusiva de quem tem contribuição verificada pelo GitHub.");
		}
		String normalized = color == null || color.isBlank() ? null : color.trim().toLowerCase(Locale.ROOT);
		if (normalized != null && !HEX.matcher(normalized).matches()) {
			throw new ApiException(400, "INVALID_CONTRIBUTOR_TAG_COLOR", "A cor da tag precisa ser hexadecimal.");
		}
		UserProfile profile = profiles.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Perfil não encontrado."));
		profile.updateContributorTagColor(normalized, clock.instant());
		return profiles.save(profile);
	}

	@Transactional
	public CommunityCosmetic createDraft(String creatorId, String rawKind, String name, String description,
		String manifestJson, String license, Boolean rightsConfirmed) {
		CommunityCosmeticKind kind = parseKind(rawKind);
		validateFields(name, description, license, rightsConfirmed);
		validateManifest(kind, manifestJson);
		Instant now = clock.instant();
		return cosmetics.save(CommunityCosmetic.create("pcos_" + UUID.randomUUID().toString().replace("-", ""),
			creatorId, kind, name.trim(), normalizeDescription(description), manifestJson.trim(),
			license.trim().toUpperCase(Locale.ROOT), true, now));
	}

	@Transactional
	public CommunityCosmetic update(String id, String creatorId, String name, String description, String manifestJson,
		String license, Boolean rightsConfirmed) {
		CommunityCosmetic existing = owned(id, creatorId);
		if (existing.status() != CommunityCosmeticStatus.DRAFT && existing.status() != CommunityCosmeticStatus.REJECTED) {
			throw new ApiException(409, "COSMETIC_NOT_EDITABLE", "Este visual já foi enviado para moderação.");
		}
		if (name != null) validateName(name);
		if (description != null) validateDescription(description);
		if (manifestJson != null) validateManifest(existing.kind(), manifestJson);
		if (license != null || rightsConfirmed != null) {
			validateFields(name == null ? existing.name() : name, description, license == null ? existing.license() : license,
				rightsConfirmed == null ? existing.rightsConfirmed() : rightsConfirmed);
		}
		return cosmetics.save(existing.edit(name == null ? null : name.trim(),
			description == null ? null : description.trim(), manifestJson == null ? null : manifestJson.trim(),
			license == null ? null : license.trim().toUpperCase(Locale.ROOT),
			rightsConfirmed == null ? existing.rightsConfirmed() : rightsConfirmed, clock.instant()));
	}

	@Transactional
	public CommunityCosmetic submit(String id, String creatorId) {
		CommunityCosmetic existing = owned(id, creatorId);
		return cosmetics.save(existing.submit(clock.instant()));
	}

	@Transactional(readOnly = true)
	public List<CommunityCosmetic> catalog(int limit, String rawKind) {
		CommunityCosmeticKind kind = rawKind == null || rawKind.isBlank() ? null : parseKind(rawKind);
		return cosmetics.findByStatus(CommunityCosmeticStatus.PUBLISHED, Math.min(Math.max(limit, 1), 100)).stream()
			.filter(item -> kind == null || item.kind() == kind).toList();
	}

	@Transactional(readOnly = true)
	public CommunityCosmetic getPublished(String id) {
		CommunityCosmetic item = find(id);
		if (item.status() != CommunityCosmeticStatus.PUBLISHED) {
			throw new ApiException(404, "COSMETIC_NOT_FOUND", "Visual não encontrado no catálogo.");
		}
		return item;
	}

	@Transactional(readOnly = true)
	public List<CommunityCosmetic> mine(String creatorId) {
		return cosmetics.findByCreator(creatorId, 50);
	}

	@Transactional
	public CommunityCosmetic report(String id) {
		CommunityCosmetic item = find(id);
		if (item.status() != CommunityCosmeticStatus.PUBLISHED) {
			throw new ApiException(404, "COSMETIC_NOT_FOUND", "Visual não encontrado no catálogo.");
		}
		return cosmetics.save(item.report(clock.instant()));
	}

	private CommunityCosmetic owned(String id, String creatorId) {
		CommunityCosmetic item = find(id);
		if (!item.ownedBy(creatorId)) {
			throw new ApiException(403, "NOT_AUTHORIZED", "Só o criador pode editar este visual.");
		}
		return item;
	}

	private CommunityCosmetic find(String id) {
		return cosmetics.findById(id)
			.orElseThrow(() -> new ApiException(404, "COSMETIC_NOT_FOUND", "Visual não encontrado."));
	}

	private CommunityCosmeticKind parseKind(String raw) {
		if (!StringUtils.hasText(raw)) {
			throw new ApiException(400, "INVALID_COSMETIC_KIND", "Tipo de visual é obrigatório.");
		}
		try {
			return CommunityCosmeticKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ApiException(400, "INVALID_COSMETIC_KIND", "Tipo de visual inválido.");
		}
	}

	private void validateFields(String name, String description, String license, Boolean rightsConfirmed) {
		validateName(name);
		validateDescription(description);
		if (!StringUtils.hasText(license) || !LICENSES.contains(license.trim().toUpperCase(Locale.ROOT))) {
			throw new ApiException(400, "INVALID_COSMETIC_LICENSE", "A licença do visual é inválida.");
		}
		if (!Boolean.TRUE.equals(rightsConfirmed)) {
			throw new ApiException(400, "RIGHTS_CONFIRMATION_REQUIRED",
				"Confirme que você tem autorização para publicar este visual.");
		}
	}

	private void validateName(String name) {
		if (!StringUtils.hasText(name) || name.trim().length() < 3 || name.trim().length() > 80) {
			throw new ApiException(400, "INVALID_COSMETIC_NAME", "O nome precisa de 3 a 80 caracteres.");
		}
	}

	private void validateDescription(String description) {
		if (description != null && description.trim().length() > MAX_DESCRIPTION_CHARS) {
			throw new ApiException(400, "INVALID_COSMETIC_DESCRIPTION",
				"A descrição passa de " + MAX_DESCRIPTION_CHARS + " caracteres.");
		}
	}

	private String normalizeDescription(String description) {
		return StringUtils.hasText(description) ? description.trim() : null;
	}

	private void validateManifest(CommunityCosmeticKind kind, String manifestJson) {
		if (!StringUtils.hasText(manifestJson) || manifestJson.length() > MAX_MANIFEST_CHARS) {
			throw new ApiException(400, "INVALID_COSMETIC_MANIFEST", "Manifest inválido ou grande demais.");
		}
		String lower = manifestJson.toLowerCase(Locale.ROOT);
		for (String forbidden : List.of("<script", "<style", "javascript:", "@import", "url(", "expression(", "data:")) {
			if (lower.contains(forbidden)) {
				throw new ApiException(400, "INVALID_COSMETIC_MANIFEST", "O manifest não pode conter código ou URLs externos.");
			}
		}
		final JsonNode root;
		try {
			root = objectMapper.readTree(manifestJson);
		} catch (RuntimeException e) {
			throw new ApiException(400, "INVALID_COSMETIC_MANIFEST", "O manifest precisa ser um JSON válido.");
		}
		if (root == null || !root.isObject() || root.get("version") == null || !root.get("version").isNumber()
			|| root.get("version").asInt() != 1 || root.get("kind") == null
			|| !kind.name().equals(root.get("kind").asText())) {
			throw new ApiException(400, "INVALID_COSMETIC_MANIFEST", "Versão ou tipo do manifest não corresponde ao visual.");
		}
		var keys = root.propertyNames().stream().toList();
		if (!TOP_LEVEL_KEYS.get(kind).containsAll(keys)) {
			throw new ApiException(400, "INVALID_COSMETIC_MANIFEST", "O manifest contém campos não permitidos.");
		}
		switch (kind) {
			case FONT -> validateFont(root);
			case PALETTE -> validatePalette(root);
			case EFFECT -> validateEffect(root);
			case PROFILE_BORDER -> validateLayers(root);
			case BUNDLE -> validateBundle(root);
		}
	}

	private void validateFont(JsonNode root) {
		String family = root.get("family") == null ? "" : root.get("family").asText();
		if (!family.matches("[A-Za-zÀ-ÿ][A-Za-z0-9À-ÿ _-]{0,63}")) {
			throw new ApiException(400, "INVALID_COSMETIC_MANIFEST", "A fonte precisa informar uma família válida.");
		}
	}

	private void validatePalette(JsonNode root) {
		JsonNode colors = root.get("colors");
		if (colors == null || !colors.isObject() || colors.size() < 2 || colors.size() > 16) {
			throw new ApiException(400, "INVALID_COSMETIC_MANIFEST", "A paleta precisa ter de 2 a 16 cores.");
		}
		colors.properties().forEach(entry -> {
			if (!entry.getValue().isTextual() || !HEX.matcher(entry.getValue().asText()).matches()) {
				throw new ApiException(400, "INVALID_COSMETIC_MANIFEST", "As cores devem ser hexadecimais de 6 dígitos.");
			}
		});
	}

	private void validateEffect(JsonNode root) {
		JsonNode effect = root.get("effect");
		if (effect == null || !effect.isObject() || effect.get("type") == null || !effect.get("type").isTextual()
			|| effect.get("type").asText().length() > 32) {
			throw new ApiException(400, "INVALID_COSMETIC_MANIFEST", "O efeito precisa informar um tipo válido.");
		}
	}

	private void validateLayers(JsonNode root) {
		JsonNode layers = root.get("layers");
		if (layers == null || !layers.isArray() || layers.size() < 1 || layers.size() > 4) {
			throw new ApiException(400, "INVALID_COSMETIC_MANIFEST", "A borda precisa ter de 1 a 4 camadas.");
		}
		layers.forEach(layer -> {
			if (!layer.isObject()) {
				throw new ApiException(400, "INVALID_COSMETIC_MANIFEST", "Cada camada da borda precisa ser declarativa.");
			}
		});
	}

	private void validateBundle(JsonNode root) {
		JsonNode ids = root.get("cosmeticIds");
		if (ids == null || !ids.isArray() || ids.size() < 1 || ids.size() > 12) {
			throw new ApiException(400, "INVALID_COSMETIC_MANIFEST", "O pacote precisa ter de 1 a 12 cosméticos.");
		}
		ids.forEach(id -> {
			if (!id.isTextual() || id.asText().isBlank()) {
				throw new ApiException(400, "INVALID_COSMETIC_MANIFEST", "O pacote contém um cosmético inválido.");
			}
		});
	}
}
