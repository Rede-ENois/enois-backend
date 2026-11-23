package com.socialcoisa.rede.profile.application;

import java.util.List;

import com.socialcoisa.rede.profile.domain.ProfileLook;
import com.socialcoisa.rede.profile.domain.ReputationGarden;
import com.socialcoisa.rede.profile.domain.UserProfile;

/**
 * Projecao publica de perfil, exposta a terceiros (consulta por username/id, busca e ranking).
 * De proposito nao inclui dados sensiveis como o email.
 */
public record UserProfileView(String id, String username, String name, String headline, String company,
		String location, String avatar, String banner,
		/**
		 * Endereco da foto para ESTE ambiente, ao lado do `avatar` original. A foto gravada pode
		 * apontar para outro ambiente (midia escrita antes de um QA nascer de uma copia); este campo
		 * e o que a tela deve abrir (ver {@link com.socialcoisa.rede.media.application.MediaPublicHost}).
		 */
		String avatarUrl,
		String badge, String bio, String bioHtml,
		String bannerHtml,
		List<String> skills,
		List<String> goals,
		String availability, String portfolio, List<String> externalLinks, boolean openToWork, boolean isMentor,
		int connectionsCount, int followersCount, int reputation, Integer postsCount, String pronouns,
		List<String> badges, String aka, String avatarFrame,
		String nameTheme, String nameFont, String nameColor, String contributorTagColor,
		/** Itens da loja equipados no cartao e o recado do perfil (ids do catalogo de cosmeticos). */
		String cardTheme, String nameplate, String bannerItem, String profileEffect, String statusText,
		String statusIcon,
		/** Quando a pessoa chegou na plataforma: o "ranking" público lista quem chegou por último. */
		String joinedAt,
		/** Cidade/UF: so saem quando a pessoa ligou `showCity` (privado por padrao). */
		String city, String state,
		/** Equipped GARDEN item id; null = the default garden. */
		String gardenStyle,
		/**
		 * Reputation garden stages. Only the single-profile reads ({@code /users/{username}},
		 * {@code /users/by-id/{id}}) compute it; lists and write responses carry null.
		 */
		ReputationGarden garden,
		/**
		 * Aparência da vitrine SOCIAL escolhida pelo dono. Quem visita recebe o visual do perfil
		 * visitado, não o tema da própria conta.
		 */
		ProfileLook profileLook,
		/**
		 * Selo "Humanidade Comprovada": verdadeiro só depois de aprovação confirmada pelo provedor
		 * de identidade. Substitui a forma pública posterior no record.
		 */
		boolean humanityVerified,
		/**
		 * Quantas advertências administrativas ativas a conta acumulou. Só a abertura de perfil por
		 * um administrador preenche (ver {@code ProfileService}); para qualquer outro leitor é
		 * {@code null}, e o histórico não é público.
		 */
		Integer activeWarnings,
		/**
		 * Tag "Aniversariante": faz aniversario hoje (horario de Brasilia). So a leitura de UM
		 * perfil calcula; listas devolvem falso.
		 */
		boolean birthdayToday) {

	public static UserProfileView from(UserProfile profile) {
		return new UserProfileView(profile.id(), profile.username(), profile.displayWithNickname(), profile.headline(),
			profile.company(), profile.location(), profile.avatar(), profile.banner(), null, profile.badge(), profile.bio(), null, null,
			profile.skills(), profile.goals(), profile.availability(), profile.portfolio(), profile.externalLinks(),
			profile.openToWork(), profile.mentor(), profile.connectionsCount(), profile.followersCount(),
			profile.reputation(), profile.postsCount(), profile.pronouns(), profile.badges(), profile.aka(),
			profile.avatarFrame(), profile.nameTheme(), profile.nameFont(), profile.nameColor(),
			profile.contributorTagColor(), profile.cardTheme(), profile.nameplate(), profile.bannerItem(),
			profile.profileEffect(), profile.statusText(), profile.statusIcon(), profile.createdAt() == null ? null : profile.createdAt().toString(),
			profile.publicPlace() == null ? null : profile.publicPlace().city(),
			profile.publicPlace() == null ? null : profile.publicPlace().state(), profile.gardenStyle(),
			profile.garden(), profile.profileAppearance().social(), false, null, false);

	}

	/**
	 * Anexa a bio em HTML. So a leitura de um perfil faz isso: a lista de conexoes, a busca e as
	 * sugestoes usam o mesmo view, e nao tem por que carregar markup em todas elas.
	 */
	public UserProfileView withBioHtml(String bioHtml) {
		return new UserProfileView(id, username, name, headline, company, location, avatar, banner, avatarUrl, badge, bio, bioHtml,
			bannerHtml, skills, goals, availability, portfolio, externalLinks, openToWork, isMentor, connectionsCount,
			followersCount, reputation, postsCount, pronouns, badges, aka, avatarFrame, nameTheme, nameFont, nameColor,
			contributorTagColor, cardTheme, nameplate, bannerItem, profileEffect, statusText, statusIcon, joinedAt, city, state,
			gardenStyle, garden, profileLook, humanityVerified, activeWarnings, birthdayToday);
	}

	/**
	 * Selo lido de {@code identity_verifications}: expõe apenas o booleano — a verificação em si
	 * nunca vira dado público.
	 */
	public UserProfileView withHumanityVerified(boolean verified) {
		return new UserProfileView(id, username, name, headline, company, location, avatar, banner, avatarUrl, badge, bio, bioHtml,
			bannerHtml, skills, goals, availability, portfolio, externalLinks, openToWork, isMentor, connectionsCount,
			followersCount, reputation, postsCount, pronouns, badges, aka, avatarFrame, nameTheme, nameFont, nameColor,
			contributorTagColor, cardTheme, nameplate, bannerItem, profileEffect, statusText, statusIcon, joinedAt, city, state,
			gardenStyle, garden, profileLook, verified, activeWarnings, birthdayToday);
	}

	/** Foto de perfil no host deste ambiente ({@code avatar} continua o endereco gravado, sem troca). */
	public UserProfileView withAvatarUrl(String localizedAvatarUrl) {
		return new UserProfileView(id, username, name, headline, company, location, avatar, banner, localizedAvatarUrl, badge, bio, bioHtml,
			bannerHtml, skills, goals, availability, portfolio, externalLinks, openToWork, isMentor, connectionsCount,
			followersCount, reputation, postsCount, pronouns, badges, aka, avatarFrame, nameTheme, nameFont, nameColor,
			contributorTagColor, cardTheme, nameplate, bannerItem, profileEffect, statusText, statusIcon, joinedAt, city, state,
			gardenStyle, garden, profileLook, humanityVerified, activeWarnings, birthdayToday);
	}

	/** Capa em HTML/CSS; so a leitura do perfil anexa (mesmo motivo da bio). */
	public UserProfileView withBannerHtml(String bannerHtml) {
		return new UserProfileView(id, username, name, headline, company, location, avatar, banner, avatarUrl, badge, bio, bioHtml,
			bannerHtml, skills, goals, availability, portfolio, externalLinks, openToWork, isMentor, connectionsCount,
			followersCount, reputation, postsCount, pronouns, badges, aka, avatarFrame, nameTheme, nameFont, nameColor,
			contributorTagColor, cardTheme, nameplate, bannerItem, profileEffect, statusText, statusIcon, joinedAt, city, state,
			gardenStyle, garden, profileLook, humanityVerified, activeWarnings, birthdayToday);
	}

	/**
	 * Advertências ativas da conta. Só o administrador que abre o perfil recebe o número: a
	 * advertência é dado de moderação, não informação pública do perfil.
	 */
	public UserProfileView withActiveWarnings(Integer activeWarnings) {
		return new UserProfileView(id, username, name, headline, company, location, avatar, banner, avatarUrl, badge,
			bio, bioHtml, bannerHtml, skills, goals, availability, portfolio, externalLinks, openToWork, isMentor,
			connectionsCount, followersCount, reputation, postsCount, pronouns, badges, aka, avatarFrame, nameTheme,
			nameFont, nameColor, contributorTagColor, cardTheme, nameplate, bannerItem, profileEffect, statusText,
			statusIcon, joinedAt, city, state, gardenStyle, garden, profileLook, humanityVerified, activeWarnings, birthdayToday);
	}

	/** Tag "Aniversariante" na leitura de um perfil (so ela calcula; listas ficam em falso). */
	public UserProfileView withBirthdayToday(boolean fazAniversario) {
		return new UserProfileView(id, username, name, headline, company, location, avatar, banner, avatarUrl, badge,
			bio, bioHtml, bannerHtml, skills, goals, availability, portfolio, externalLinks, openToWork, isMentor,
			connectionsCount, followersCount, reputation, postsCount, pronouns, badges, aka, avatarFrame, nameTheme,
			nameFont, nameColor, contributorTagColor, cardTheme, nameplate, bannerItem, profileEffect, statusText,
			statusIcon, joinedAt, city, state, gardenStyle, garden, profileLook, humanityVerified, activeWarnings,
			fazAniversario);
	}
}
