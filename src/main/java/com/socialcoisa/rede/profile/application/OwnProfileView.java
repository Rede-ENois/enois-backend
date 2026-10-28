package com.socialcoisa.rede.profile.application;

import java.util.List;

import com.socialcoisa.rede.profile.domain.UserProfile;

/**
 * Perfil do proprio usuario autenticado (inclui o email). Usado apenas em endpoints do tipo
 * {@code /me}; nunca deve ser exposto em consultas a terceiros.
 */
public record OwnProfileView(String id, String username, String name, String headline, String email, String company,
		String location, String avatar, String banner, String badge, String bio, String bioHtml,
		String bannerHtml,
		List<String> skills,
		List<String> goals,
		String availability, String portfolio, List<String> externalLinks, boolean openToWork, boolean isMentor,
		int connectionsCount, int followersCount, int reputation, Integer postsCount, String nickname, String socialName,
		String pronouns, List<String> badges, String aka, String resumeUrl, String resumeName, boolean showRanking,
		String nameTheme, String nameFont, String nameColor, String contributorTagColor,
		/** Itens da loja equipados (ids do catalogo de cosmeticos) e o recado do perfil. */
		String avatarFrame, String cardTheme, String nameplate, String bannerItem, String profileEffect,
		String statusText, String statusIcon) {

	public static OwnProfileView from(UserProfile profile) {
		return new OwnProfileView(profile.id(), profile.username(), profile.name(), profile.headline(), profile.email(),
			profile.company(), profile.location(), profile.avatar(), profile.banner(), profile.badge(),
			profile.bio(), null, null,
			profile.skills(),
			profile.goals(), profile.availability(), profile.portfolio(), profile.externalLinks(), profile.openToWork(),
			profile.mentor(), profile.connectionsCount(), profile.followersCount(), profile.reputation(),
			profile.postsCount(), profile.nickname(), profile.socialName(), profile.pronouns(), profile.badges(),
			profile.aka(), profile.resumeUrl(), profile.resumeName(), profile.showRanking(), profile.nameTheme(),
			profile.nameFont(), profile.nameColor(), profile.contributorTagColor(), profile.avatarFrame(),
			profile.cardTheme(), profile.nameplate(), profile.bannerItem(), profile.profileEffect(),
			profile.statusText(), profile.statusIcon());

	}

	/**
	 * Anexa a bio em HTML. O `from` NAO traz: a resposta da edicao de perfil passa pela camada de
	 * idempotencia, que guarda o corpo da resposta numa coluna de texto — devolver o HTML ali
	 * estourava essa coluna (foi o mesmo problema do post de pagina). Quem precisa da bio em HTML
	 * e a leitura do proprio perfil.
	 */
	public OwnProfileView withBioHtml(String bioHtml) {
		return new OwnProfileView(id, username, name, headline, email, company, location, avatar, banner, badge, bio,
			bioHtml, bannerHtml, skills, goals, availability, portfolio, externalLinks, openToWork, isMentor,
			connectionsCount, followersCount, reputation, postsCount, nickname, socialName, pronouns, badges, aka,
			resumeUrl, resumeName, showRanking, nameTheme, nameFont, nameColor, contributorTagColor, avatarFrame,
			cardTheme, nameplate, bannerItem, profileEffect, statusText, statusIcon);
	}

	/** Capa em HTML/CSS; so a leitura do perfil anexa (mesmo motivo da bio). */
	public OwnProfileView withBannerHtml(String bannerHtml) {
		return new OwnProfileView(id, username, name, headline, email, company, location, avatar, banner, badge, bio,
			bioHtml, bannerHtml, skills, goals, availability, portfolio, externalLinks, openToWork, isMentor,
			connectionsCount, followersCount, reputation, postsCount, nickname, socialName, pronouns, badges, aka,
			resumeUrl, resumeName, showRanking, nameTheme, nameFont, nameColor, contributorTagColor, avatarFrame,
			cardTheme, nameplate, bannerItem, profileEffect, statusText, statusIcon);
	}
}
