package com.socialcoisa.rede.corporate.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * O alter ego corporativo: uma identidade de apresentação da MESMA conta.
 *
 * Os valores da conta (faiscas, credibilidade, seguidores, verificação, segundo fator) são
 * compartilhados entre as duas identidades — isto aqui é apenas apresentação e estado. O
 * perfil NUNCA nasce copiado do social: os campos partem vazios e o que vier do perfil social
 * chega por um fluxo de importação com revisão e consentimento explícito (na camada de app).
 */
public record CorporateProfile(String userId, String username, String name, String headline, String bio,
		String avatar, String banner, String bannerHtml, String location, String industry, String externalLinks,
		CorporateProfileStatus status, Instant createdAt, Instant updatedAt) {

	/** Mesmas regras de formato do @ social, em namespace próprio: [a-z0-9._-], com pontos. */
	private static final Pattern USERNAME = Pattern.compile("^[a-z0-9](?:[a-z0-9._-]{1,28})[a-z0-9]$");

	public static CorporateProfile create(String userId, String username, String name, Instant now) {
		return new CorporateProfile(userId, validUsername(username), validName(name), null, null, null, null,
			null, null, null, null, CorporateProfileStatus.DRAFT, now, now);
	}

	public CorporateProfile withDetails(String newHeadline, String newBio, String newAvatar, String newBanner,
			String newBannerHtml, String newLocation, String newIndustry, String newExternalLinks, Instant now) {
		return new CorporateProfile(userId, username, name, newHeadline, newBio, newAvatar, newBanner,
			newBannerHtml, newLocation, newIndustry, newExternalLinks, status, createdAt, now);
	}

	/** Renomear a apresentação não muda o @ profissional (o @ tem fluxo próprio). */
	public CorporateProfile withName(String newName, Instant now) {
		return new CorporateProfile(userId, username, validName(newName), headline, bio, avatar, banner,
			bannerHtml, location, industry, externalLinks, status, createdAt, now);
	}

	/**
	 * Troca o @ profissional (`/profissional/{username}`). Independente do @ social pela própria
	 * natureza do perfil: são duas apresentações da mesma conta, cada uma com seu endereço.
	 * O formato é o mesmo da criação e a unicidade é do banco.
	 */
	public CorporateProfile withUsername(String newUsername, Instant now) {
		return new CorporateProfile(userId, validUsername(newUsername), name, headline, bio, avatar, banner,
			bannerHtml, location, industry, externalLinks, status, createdAt, now);
	}

	public CorporateProfile activate(Instant now) {
		return withStatus(CorporateProfileStatus.ACTIVE, now);
	}

	public CorporateProfile disable(Instant now) {
		return withStatus(CorporateProfileStatus.DISABLED, now);
	}

	/** Reabrir um perfil desativado volta como ACTIVE (nada foi apagado na desativação). */
	public CorporateProfile reopen(Instant now) {
		return withStatus(CorporateProfileStatus.ACTIVE, now);
	}

	public boolean active() {
		return status == CorporateProfileStatus.ACTIVE;
	}

	private CorporateProfile withStatus(CorporateProfileStatus newStatus, Instant now) {
		return new CorporateProfile(userId, username, name, headline, bio, avatar, banner, bannerHtml, location,
			industry, externalLinks, newStatus, createdAt, now);
	}

	private static String validUsername(String username) {
		String value = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
		if (!USERNAME.matcher(value).matches()) {
			throw new IllegalArgumentException(
				"Usuário profissional inválido: use 3 a 30 caracteres entre letras, números, ponto, hífen ou underline.");
		}
		return value;
	}

	private static String validName(String name) {
		if (name == null || name.trim().length() < 3) {
			throw new IllegalArgumentException("O nome do perfil profissional precisa de ao menos 3 caracteres.");
		}
		return name.trim();
	}
}
