package com.socialcoisa.rede.profile.domain;

import java.time.Instant;
import java.util.List;

public class UserProfile {

	private final String id;
	private String username;
	private String email;
	private String name;
	private String headline;

	/** Moldura de avatar equipada (loja de cosméticos); null = sem moldura. */
	private String avatarFrame;
	/** Estilo seguro do nome exibido; ids de fonte e cor hex, nunca CSS livre. */
	private String nameTheme;
	private String nameFont;
	private String nameColor;
	/** Cor da tag "Contribuinte" abaixo do nome (hex); so quem tem contribuicao verificada escolhe. */
	private String contributorTagColor;
	/** Itens da loja equipados no cartao do perfil (ids do catalogo); null = nada equipado. */
	private String cardTheme;
	private String nameplate;
	private String bannerItem;
	private String profileEffect;
	/** Recado curto do perfil (texto ja limpo) e o icone de emblema que o acompanha. */
	private String statusText;
	private String statusIcon;
	private String company;
	private String location;
	private String avatar;
	private String banner;
	/** Emblema (PNG pequeno) que aparece ao lado do nome; URL da midia, como avatar e banner. */
	private String badge;
	private String bio;
	/** Bio em HTML/CSS (ja sanitizada no servidor); quando presente, o perfil mostra ela no lugar do texto. */
	private String bioHtml;
	/** Capa em HTML/CSS (ja sanitizada); quando presente, o perfil mostra ela no lugar da imagem. */
	private String bannerHtml;
	/** Apelido: entra entre parenteses depois do nome, em tudo que exibe nome. */
	private String nickname;
	/** Nome social: substitui o nome de cadastro para todo mundo, quando existe. */
	private String socialName;
	/** Pronomes: aparecem so no perfil, como lembrete de como tratar a pessoa. */
	private String pronouns;
	/** Vulgo: como a pessoa e conhecida (etiqueta ao lado do nome, so no perfil). */
	private String aka;
	/** Curriculo em PDF: endereco no storage + nome do arquivo (so o dono ve). */
	private String resumeUrl;
	private String resumeName;
	/** Ate 5 emblemas; o primeiro e o que acompanha o nome por todo o app. */
	private List<String> badges = List.of();
	private List<String> skills;
	private List<String> goals;
	private String availability;
	private String portfolio;
	private List<String> externalLinks;
	private boolean openToWork;
	private boolean mentor;
	/** Papel administrativo imutável: só muda por operação de banco, nunca por edição de perfil. */
	private boolean admin;
	private int connectionsCount;
	private int followersCount;
	private int reputation;
	/**
	 * Publicacoes do perfil social. Embalado: busca, ranking e lista de conexoes nao calculam, e
	 * um 0 ahi seria lido como "essa pessoa nunca postou".
	 */
	private Integer postsCount;
	private final Instant createdAt;
	private Instant updatedAt;
	private Instant termsAcceptedAt;
	private Instant emailVerifiedAt;

	/** Mostrar a secao de ranking no perfil? Preferencia de quem LE (pedido via suporte, 19/10). */
	private boolean showRanking = true;
	private Instant totpEnabledAt;
	private Instant emailOtpEnabledAt;
	private Instant federatedSecondFactorAt;

	/**
	 * ECA Digital (Lei 15.211/2025): data de nascimento declarada. O backend calcula a idade e
	 * decide as protecoes; a data NUNCA sai em leitura publica (nem para o proprio dono a UI
	 * mostra sem pedir). Null = ainda nao regularizou (escritas bloqueadas com BIRTH_DATE_REQUIRED).
	 */
	private java.time.LocalDate birthDate;

	private UserProfile(String id, String username, String email, String name, String headline, String company,
			String location, String avatar, String banner, String badge, String bio, String bioHtml, String bannerHtml,
			List<String> skills, List<String> goals,
			String availability, String portfolio, List<String> externalLinks, boolean openToWork, boolean mentor,
			Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.username = username;
		this.email = email;
		this.name = name;
		this.headline = headline;
		this.company = company;
		this.location = location;
		this.avatar = avatar;
		this.banner = banner;
		this.badge = badge;
		this.bio = bio;
		this.bioHtml = bioHtml;
		this.bannerHtml = bannerHtml;
		this.skills = List.copyOf(skills);
		this.goals = List.copyOf(goals);
		this.availability = availability;
		this.portfolio = portfolio;
		this.externalLinks = List.copyOf(externalLinks);
		this.openToWork = openToWork;
		this.mentor = mentor;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static UserProfile create(String id, String username, String email, String name, String headline,
			Instant now) {
		return new UserProfile(id, username, email, name, headline, null, null, null, null, null, null, null, null,
			List.of(), List.of(), null, null, List.of(), false, false, now, now);
	}

	public static UserProfile restore(String id, String username, String email, String name, String headline,
			String company, String location, String avatar, String banner, String badge, String bio, String bioHtml, String bannerHtml,
			List<String> skills, List<String> goals, String availability, String portfolio, List<String> externalLinks,
			boolean openToWork, boolean mentor, Instant createdAt, Instant updatedAt, boolean showRanking) {
		UserProfile profile = new UserProfile(id, username, email, name, headline, company, location, avatar, banner,
			badge, bio, bioHtml, bannerHtml, skills, goals, availability, portfolio, externalLinks, openToWork, mentor,
			createdAt, updatedAt);
		profile.showRanking = showRanking;
		return profile;
	}

	/** Preferencia da CONTA: esconder a secao de ranking do proprio perfil (quem le decide). */
	public void setShowRanking(boolean showRanking, Instant now) {
		if (this.showRanking != showRanking) {
			this.showRanking = showRanking;
			this.updatedAt = now;
		}
	}

	public boolean showRanking() {
		return showRanking;
	}

	public void update(String name, String headline, String company, String location, String avatar, String banner,
			String badge, String bio, List<String> skills, List<String> goals, String availability, String portfolio,
			List<String> externalLinks, Boolean openToWork, Boolean mentor, String username, String aka, Instant now) {
		this.name = keep(name, this.name);
		this.headline = keep(headline, this.headline);
		this.company = keep(company, this.company);
		this.location = keep(location, this.location);
		this.avatar = keep(avatar, this.avatar);
		this.banner = keep(banner, this.banner);
		this.badge = keep(badge, this.badge);
		this.bio = keep(bio, this.bio);
		this.aka = keep(aka, this.aka);
		this.skills = skills == null ? this.skills : List.copyOf(skills);
		this.goals = goals == null ? this.goals : List.copyOf(goals);
		this.availability = keep(availability, this.availability);
		this.portfolio = keep(portfolio, this.portfolio);
		this.externalLinks = externalLinks == null ? this.externalLinks : List.copyOf(externalLinks);
		this.openToWork = openToWork == null ? this.openToWork : openToWork;
		this.mentor = mentor == null ? this.mentor : mentor;
		this.username = keep(username, this.username);
		this.updatedAt = now;
	}

	/**
	 * Bio em HTML/CSS. Diferente dos outros campos, aqui `null` e uma intencao legitima: limpar o
	 * HTML e voltar para a bio de texto, entao nao ha o "manter o que ja estava" do `keep`.
	 */
	public void updateBioHtml(String sanitizedHtml, Instant now) {
		this.bioHtml = sanitizedHtml;
		this.updatedAt = now;
	}


	/** Instante em que o usuario aceitou os termos no cadastro (null para contas antigas). */
	public void acceptTerms(Instant at) {
		this.termsAcceptedAt = at;
	}

	public Instant termsAcceptedAt() {
		return termsAcceptedAt;
	}

	/** Conta com e-mail confirmado (ou criada por um provedor que ja confirma o e-mail). */
	public boolean emailVerified() {
		return emailVerifiedAt != null;
	}

	public Instant emailVerifiedAt() {
		return emailVerifiedAt;
	}

	public void markEmailVerified(Instant at) {
		if (this.emailVerifiedAt == null) {
			this.emailVerifiedAt = at;
		}
	}

	/** Segundo fator ativo por aplicativo authenticator. */
	public boolean totpEnabled() {
		return totpEnabledAt != null;
	}

	public Instant totpEnabledAt() {
		return totpEnabledAt;
	}

	public void enableTotp(Instant at) {
		this.totpEnabledAt = at;
	}

	public void disableTotp() {
		this.totpEnabledAt = null;
	}

	/** Segundo fator ativo por codigo enviado no e-mail. */
	public boolean emailOtpEnabled() {
		return emailOtpEnabledAt != null;
	}

	public Instant emailOtpEnabledAt() {
		return emailOtpEnabledAt;
	}

	public void enableEmailOtp(Instant at) {
		this.emailOtpEnabledAt = at;
	}

	public void disableEmailOtp() {
		this.emailOtpEnabledAt = null;
	}

	/** Segundo fator coberto pelo provedor (Google/Apple): vale para a verificacao da conta. */
	public boolean federatedSecondFactor() {
		return federatedSecondFactorAt != null;
	}

	public Instant federatedSecondFactorAt() {
		return federatedSecondFactorAt;
	}

	public void enableFederatedSecondFactor(Instant at) {
		this.federatedSecondFactorAt = at;
	}

	public void disableFederatedSecondFactor() {
		this.federatedSecondFactorAt = null;
	}

	/**
	 * Metodos que NOS desafiamos no login (aplicativo ou codigo por e-mail). O segundo fator do
	 * provedor nao entra aqui: ele nao tem codigo nosso para conferir.
	 */
	public boolean challengeableTwoFactor() {
		return totpEnabled() || emailOtpEnabled();
	}

	/** A conta tem segundo fator garantido (por app, por e-mail ou pelo provedor)? */
	public boolean twoFactorEnabled() {
		return challengeableTwoFactor() || federatedSecondFactor();
	}

	/**
	 * Conta "verificada": confirmou o e-mail ou ligou um segundo fator. E o que multiplica a
	 * credibilidade — sem nenhum dos dois, a reputacao nao conta (regra pedida pelo produto).
	 */
	public boolean identityVerified() {
		return emailVerified() || twoFactorEnabled();
	}

	public void changeEmail(String email, Instant now) {
		this.email = email;
		this.updatedAt = now;
	}

	/**
	 * Anonimiza os dados pessoais apos a exclusao da conta, preservando o id para integridade
	 * referencial do conteudo ja publicado.
	 */
	public void anonymize(String anonymizedEmail, String anonymizedUsername, Instant now) {
		this.email = anonymizedEmail;
		this.username = anonymizedUsername;
		this.name = "Usuario removido";
		this.headline = null;
		this.company = null;
		this.location = null;
		this.avatar = null;
		this.banner = null;
		this.bio = null;
		this.skills = List.of();
		this.goals = List.of();
		this.availability = null;
		this.portfolio = null;
		this.externalLinks = List.of();
		this.openToWork = false;
		this.mentor = false;
		this.badges = List.of();
		this.nickname = null;
		this.socialName = null;
		this.pronouns = null;
		this.updatedAt = now;
	}

	private String keep(String value, String current) {
		return value == null ? current : value.trim();
	}

	private static String blankToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** Nome que aparece para todo mundo: o social, quando existe; senao o de cadastro. */
	public String displayName() {
		return socialName != null && !socialName.isBlank() ? socialName : name;
	}

	/** Nome de exibicao com o apelido entre parenteses, quando existe. */
	public String displayWithNickname() {
		String base = displayName();
		return nickname == null || nickname.isBlank() ? base : base + " (" + nickname + ")";
	}

	/**
	 * Atualiza apelido, nome social e pronomes. Valor nulo ou vazio LIMPA o campo (e uma
	 * intencao: "nao quero mais apelido"), ao contrario do `keep` do perfil.
	 */
	public void updateIdentityDisplay(String nickname, String socialName, String pronouns, Instant now) {
		this.nickname = blankToNull(nickname);
		this.socialName = blankToNull(socialName);
		this.pronouns = blankToNull(pronouns);
		this.updatedAt = now;
	}

	/** Hidrata os campos de identidade exibida carregados do banco (padrao do updateBioHtml). */
	public void hydrateIdentityDisplay(String nickname, String socialName, String pronouns) {
		this.nickname = blankToNull(nickname);
		this.socialName = blankToNull(socialName);
		this.pronouns = blankToNull(pronouns);
	}

	/** Hidrata o vulgo (o campo vem da coluna, nao do fluxo de edicao). */
	public void hydrateAka(String aka) {
		this.aka = aka;
	}

	/** Hidrata a lista de emblemas (URLs separadas por linha no banco). */
	public void hydrateBadges(List<String> badges) {
		this.badges = badges == null ? List.of() : List.copyOf(badges);
	}

	/** Ate 5 emblemas; valor nulo/vazio limpa a lista. Recusa acima de 5. */
	public void updateBadges(List<String> badges, Instant now) {
		if (badges == null || badges.isEmpty()) {
			this.badges = List.of();
			// O campo antigo sai junto: com a lista vazia o primaryBadge cai nele, e o emblema que
			// a pessoa acabou de remover voltaria a acompanhar o nome por todo o app.
			this.badge = null;
			this.updatedAt = now;
			return;
		}
		if (badges.size() > 5) {
			throw new IllegalStateException("MAX_BADGES");
		}
		this.badges = List.copyOf(badges);
		// A coluna unica segue a lista (o primeiro emblema): e o valor que as leituras de autor
		// (feed, comentarios, blog) usam quando procuram "o emblema" da pessoa.
		this.badge = this.badges.get(0);
		this.updatedAt = now;
	}

	/** O emblema que acompanha o nome por todo o app: o primeiro da lista. */
	public String primaryBadge() {
		return badges.isEmpty() ? badge : badges.get(0);
	}

	/**
	 * Todos os emblemas, na ordem escolhida — é o que acompanha o nome em TODO lugar (feed,
	 * comentários, blog, jogos), e não só no perfil.
	 *
	 * Lista vazia cai na coluna antiga (`badge`), que guardava um emblema só: uma conta que ainda
	 * não passou pela lista não perde o emblema que tinha.
	 */
	public List<String> allBadges() {
		if (!badges.isEmpty()) {
			return badges;
		}
		return badge == null || badge.isBlank() ? List.of() : List.of(badge);
	}

	public String id() {
		return id;
	}

	public String username() {
		return username;
	}

	public String email() {
		return email;
	}

	public String name() {
		return name;
	}

	public String nickname() {
		return nickname;
	}

	public String socialName() {
		return socialName;
	}

	public String pronouns() {
		return pronouns;
	}

	public String aka() {
		return aka;
	}

	public String resumeUrl() {
		return resumeUrl;
	}

	public String resumeName() {
		return resumeName;
	}

	/** Curriculo do perfil (endereco + nome do arquivo). Vazio limpa os dois. */
	public void updateResume(String url, String name, Instant now) {
		boolean empty = url == null || url.isBlank();
		this.resumeUrl = empty ? null : url.trim();
		this.resumeName = empty || name == null || name.isBlank() ? null : name.trim();
		this.updatedAt = now;
	}

	/** Hidrata o curriculo (vem das colunas, nao do fluxo de edicao). */
	public void hydrateResume(String url, String name) {
		this.resumeUrl = url;
		this.resumeName = name;
	}

	/** Equipa (ou desequipa, com null) a moldura de avatar da loja de cosméticos. */
	public void updateAvatarFrame(String frameId, Instant now) {
		boolean empty = frameId == null || frameId.isBlank();
		this.avatarFrame = empty ? null : frameId.trim();
		this.updatedAt = now;
	}

	/** Hidrata a moldura (vem da coluna, nao do fluxo de compra). */
	public void hydrateAvatarFrame(String frameId) {
		this.avatarFrame = frameId;
	}

	/** Equipa um tema, uma fonte e uma cor aprovados para o nome do perfil. */
	public void updateNameStyle(String theme, String font, String color, Instant now) {
		this.nameTheme = theme;
		this.nameFont = font;
		this.nameColor = color;
		this.updatedAt = now;
	}

	public void hydrateNameStyle(String theme, String font, String color) {
		this.nameTheme = theme;
		this.nameFont = font;
		this.nameColor = color;
	}

	/** Cor da tag de contribuinte; null volta para a cor padrao do tema. */
	public void updateContributorTagColor(String color, Instant now) {
		this.contributorTagColor = color;
		this.updatedAt = now;
	}

	public void hydrateContributorTagColor(String color) {
		this.contributorTagColor = color;
	}

	/** Equipa (ou limpa, com null) os itens de cartao, placa, capa e efeito do perfil. */
	public void updateProfileCosmetics(String cardTheme, String nameplate, String bannerItem, String profileEffect,
			Instant now) {
		hydrateProfileCosmetics(cardTheme, nameplate, bannerItem, profileEffect);
		this.updatedAt = now;
	}

	public void hydrateProfileCosmetics(String cardTheme, String nameplate, String bannerItem, String profileEffect) {
		this.cardTheme = cardTheme;
		this.nameplate = nameplate;
		this.bannerItem = bannerItem;
		this.profileEffect = profileEffect;
	}

	/** Recado do perfil; texto e icone ja validados no service (null limpa). */
	public void updateStatus(String text, String icon, Instant now) {
		hydrateStatus(text, icon);
		this.updatedAt = now;
	}

	public void hydrateStatus(String text, String icon) {
		this.statusText = text;
		this.statusIcon = icon;
	}

	public String cardTheme() {
		return cardTheme;
	}

	public String nameplate() {
		return nameplate;
	}

	public String bannerItem() {
		return bannerItem;
	}

	public String profileEffect() {
		return profileEffect;
	}

	public String statusText() {
		return statusText;
	}

	public String statusIcon() {
		return statusIcon;
	}

	/** Declara (ou regulariza) a data de nascimento. Validacao de plausibilidade fica no service. */
	public void declareBirthDate(java.time.LocalDate birthDate, Instant now) {
		this.birthDate = birthDate;
		this.updatedAt = now;
	}

	/** Hidrata a data de nascimento (vem da coluna, nao do fluxo de edicao). */
	public void hydrateBirthDate(java.time.LocalDate birthDate) {
		this.birthDate = birthDate;
	}

	public java.time.LocalDate birthDate() {
		return birthDate;
	}

	/** A data de nascimento foi declarada? Contas antigas regularizam pelo fluxo proprio. */
	public boolean birthDateDeclared() {
		return birthDate != null;
	}

	/** Idade calculada no backend na data de referencia (nunca a declarada pelo cliente). */
	public int ageOn(java.time.LocalDate today) {
		if (birthDate == null) {
			return -1;
		}
		return java.time.Period.between(birthDate, today).getYears();
	}

	/** Menor de 18 na data de referencia — dispara as protecoes da faixa etaria. */
	public boolean isMinorOn(java.time.LocalDate today) {
		return birthDate != null && ageOn(today) < 18;
	}

	public String headline() {
		return headline;
	}

	public String avatarFrame() {
		return avatarFrame;
	}

	public String nameFont() {
		return nameFont;
	}

	public String nameTheme() {
		return nameTheme;
	}

	public String nameColor() {
		return nameColor;
	}

	public String contributorTagColor() {
		return contributorTagColor;
	}

	public String company() {
		return company;
	}

	public String location() {
		return location;
	}

	public String avatar() {
		return avatar;
	}

	public String banner() {
		return banner;
	}

	public String badge() {
		return badge;
	}

	public List<String> badges() {
		return badges;
	}

	public String bio() {
		return bio;
	}

	public void updateBannerHtml(String sanitizedHtml, Instant now) {
		this.bannerHtml = sanitizedHtml;
		this.updatedAt = now;
	}

	public String bioHtml() {
		return bioHtml;
	}

	public String bannerHtml() {
		return bannerHtml;
	}


	public List<String> skills() {
		return skills;
	}

	public List<String> goals() {
		return goals;
	}

	public String availability() {
		return availability;
	}

	public String portfolio() {
		return portfolio;
	}

	public List<String> externalLinks() {
		return externalLinks;
	}

	public boolean openToWork() {
		return openToWork;
	}

	public boolean mentor() {
		return mentor;
	}

	public boolean admin() {
		return admin;
	}

	/** Hidrata o papel administrativo a partir da coluna; nunca é aceito do cliente. */
	public void hydrateAdmin(boolean admin) {
		this.admin = admin;
	}

	public void setCounts(int connectionsCount, int followersCount, int reputation) {
		this.connectionsCount = connectionsCount;
		this.followersCount = followersCount;
		this.reputation = reputation;
	}

	public int connectionsCount() {
		return connectionsCount;
	}

	public int followersCount() {
		return followersCount;
	}

	public int reputation() {
		return reputation;
	}

	public Integer postsCount() {
		return postsCount;
	}

	public void setPostsCount(Integer postsCount) {
		this.postsCount = postsCount;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}
}
