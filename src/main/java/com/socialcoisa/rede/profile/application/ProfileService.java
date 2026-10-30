package com.socialcoisa.rede.profile.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.profile.application.port.in.GetMeUseCase;
import com.socialcoisa.rede.profile.application.port.in.GetUserProfileUseCase;
import com.socialcoisa.rede.profile.application.port.in.UpdateProfileUseCase;
import com.socialcoisa.rede.profile.application.port.out.OfficialBadgeReader;
import com.socialcoisa.rede.profile.application.port.out.SocialPostCountReader;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.html.SafeHtml;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class ProfileService implements GetMeUseCase, GetUserProfileUseCase, UpdateProfileUseCase {

	private final UserProfileRepository repository;
	private final ReputationService reputationService;
	private final org.springframework.beans.factory.ObjectProvider<com.socialcoisa.rede.media.application.LazyUrlMigrationService> lazyMedia;
	private final com.socialcoisa.rede.age.application.port.out.ParentalLinkRepository parentalLinks;
	private final SocialPostCountReader postCounts;
	private final SanctionGateReader sanctionGate;
	private final OfficialBadgeReader officialBadges;
	private final Clock clock;

	public ProfileService(UserProfileRepository repository, ReputationService reputationService,
			org.springframework.beans.factory.ObjectProvider<com.socialcoisa.rede.media.application.LazyUrlMigrationService> lazyMedia,
			com.socialcoisa.rede.age.application.port.out.ParentalLinkRepository parentalLinks,
			SocialPostCountReader postCounts, SanctionGateReader sanctionGate, OfficialBadgeReader officialBadges,
			Clock clock) {
		this.repository = repository;
		this.reputationService = reputationService;
		this.lazyMedia = lazyMedia;
		this.parentalLinks = parentalLinks;
		this.postCounts = postCounts;
		this.sanctionGate = sanctionGate;
		this.officialBadges = officialBadges;
		this.clock = clock;
	}

	/** Foto/capa lidas no perfil: agenda a migração para o S3 (sem bloquear a resposta). */
	private void agendarMigracaoDeMidia(UserProfile profile) {
		lazyMedia.ifAvailable(lazy -> {
			lazy.onAccess(profile.avatar());
			lazy.onAccess(profile.banner());
		});
	}

	@Override
	@Transactional(readOnly = true)
	public OwnProfileView getMe(String userId) {
		UserProfile profile = repository.findById(userId)
			.map(this::populateCounts)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		// A bio em HTML so vai na leitura (ver OwnProfileView.withBioHtml).
		return OwnProfileView.from(profile).withBioHtml(profile.bioHtml()).withBannerHtml(profile.bannerHtml());
	}

	@Override
	@Transactional(readOnly = true)
	public UserProfileView getByUsername(String username) {
		UserProfile profile = repository.findByUsername(username)
			.map(this::populateCounts)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		// Conta banida some da leitura publica como se nao existisse (link direto incluso).
		if (sanctionGate.banned(profile.id())) {
			throw new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado.");
		}
		agendarMigracaoDeMidia(profile);
		return UserProfileView.from(profile).withBioHtml(profile.bioHtml()).withBannerHtml(profile.bannerHtml());
	}

	/** Liga/desliga a secao de ranking na tela de perfil (preferencia de quem le). */
	@Transactional
	public com.socialcoisa.rede.shared.web.StatusResponse setShowRanking(String userId, boolean showRanking) {
		UserProfile profile = repository.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		profile.setShowRanking(showRanking, clock.instant());
		repository.save(profile);
		return new com.socialcoisa.rede.shared.web.StatusResponse(userId,
			showRanking ? "RANKING_VISIBLE" : "RANKING_HIDDEN");
	}

	@Override
	@Transactional(readOnly = true)
	public UserProfileView getById(String userId) {
		UserProfile profile = repository.findById(userId)
			.map(this::populateCounts)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		return UserProfileView.from(profile).withBioHtml(profile.bioHtml()).withBannerHtml(profile.bannerHtml());
	}

	/**
	 * Limites de cada campo, com mensagem dizendo QUAL campo passou.
	 *
	 * Sem isso o valor longo chegava ao banco, estourava a coluna e virava um 409 generico — que a
	 * tela antiga ainda rotulava como "nome de usuario ja esta em uso".
	 */
	private void validateLengths(UpdateProfileCommand command) {
		check("nome", command.name(), 160);
		check("headline", command.headline(), 180);
		check("empresa", command.company(), 160);
		check("local", command.location(), 160);
		check("disponibilidade", command.availability(), 160);
		check("portfolio", command.portfolio(), 500);
		check("bio", command.bio(), 1024);
		check("avatar", command.avatar(), 500);
		check("banner", command.banner(), 500);
		check("emblema", command.badge(), 500);
		check("vulgo", command.aka(), 60);
		check("curriculo", command.resumeUrl(), 500);
		check("nome do curriculo", command.resumeName(), 200);
		if (command.externalLinks() != null) {
			command.externalLinks().forEach(link -> check("link", link, 500));
		}
		if (command.skills() != null) {
			// Mesmo teto do campo no front: o texto livre la aceita 1600 caracteres, entao um item
			// pode ocupar o campo inteiro (quem escreve habilidades como frase, nao como tags).
			command.skills().forEach(skill -> check("habilidade", skill, 1600));
		}
		if (command.goals() != null) {
			// Mesmo caso das habilidades: o campo e texto livre e aceita 1600.
			command.goals().forEach(goal -> check("objetivo", goal, 1600));
		}
	}

	private void check(String field, String value, int max) {
		if (value != null && value.length() > max) {
			throw new ApiException(400, "FIELD_TOO_LONG",
				"O campo " + field + " pode ter no maximo " + max + " caracteres.");
		}
	}

	private UserProfile populateCounts(UserProfile profile) {
		ReputationService.Counts counts = reputationService.countsFor(profile.id());
		profile.setCounts(counts.connections(), counts.followers(), counts.reputation());
		profile.setPostsCount(postCounts.countByUser(profile.id()));
		repository.save(profile);
		return profile;
	}

	/** Busca pública de perfis: conta banida não é sugestão de conexão (mesma regra da vitrine). */
	public List<UserProfileView> search(String query) {
		return repository.searchByQuery(query).stream()
			.filter(profile -> !sanctionGate.banned(profile.id()))
			.map(UserProfileView::from)
			.toList();
	}

	/**
	 * O "ranking" público é a vitrine de QUEM ACABOU DE CHEGAR (mudança de 2026-10-20): os perfis
	 * mais recentes primeiro, e o rótulo na tela diz isso. A credibilidade continua sendo o número
	 * do perfil e o critério da busca — ela não ordena mais esta lista.
	 *
	 * Conta banida sai da vitrine: depois da limpeza dos cadastros em série (2026-10-22) elas
	 * continuavam aparecendo como "chegando agora" — a conta está punida, o perfil é que ficava.
	 */
	public List<UserProfileView> getRanking(int limit) {
		int wanted = Math.max(1, limit);
		// A vitrine é curta: buscar um pouco além do pedido cobre as banidas sem varrer a tabela.
		return repository.findNewestProfiles(wanted * 3).stream()
			.filter(profile -> !sanctionGate.banned(profile.id()))
			.limit(wanted)
			.map(UserProfileView::from)
			.toList();
	}

	/**
	 * ECA Digital: regularizacao da base. O usuario declara (ou corrige) a data de nascimento; a
	 * validacao de plausibilidade e o calculo de idade sao do BACKEND. A data nunca volta em
	 * resposta — so o estado resultante.
	 */
	@Transactional
	public AgeStateView declareBirthDate(String userId, String birthDateIso) {
		LocalDate date;
		try {
			date = birthDateIso == null || birthDateIso.isBlank() ? null : LocalDate.parse(birthDateIso.trim());
		}
		catch (Exception e) {
			throw new ApiException(400, "INVALID_BIRTH_DATE", "Data de nascimento invalida (use aaaa-mm-dd).");
		}
		if (date == null) {
			throw new ApiException(400, "BIRTH_DATE_REQUIRED", "Informe a data de nascimento.");
		}
		LocalDate today = LocalDate.now(clock.withZone(java.time.ZoneId.of("America/Sao_Paulo")));
		if (date.isAfter(today)) {
			throw new ApiException(400, "INVALID_BIRTH_DATE", "A data de nascimento nao pode ser no futuro.");
		}
		int age = java.time.Period.between(date, today).getYears();
		if (age < 0 || age > 120) {
			throw new ApiException(400, "INVALID_BIRTH_DATE", "A data de nascimento nao parece real.");
		}
		UserProfile profile = repository.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		profile.declareBirthDate(date, java.time.Instant.now(clock));
		repository.save(profile);
		boolean minor = age < 18;
		boolean supervised = minor && parentalLinks.findActiveByMinor(userId).isPresent();
		return new AgeStateView(minor ? (supervised ? "MINOR_SUPERVISED" : "MINOR_CONSENT_PENDING")
			: "ADULT_DECLARED", minor, true, supervised);
	}

	/** Estado de idade para o front decidir se mostra a tela de regularizacao (sem expor a data). */
	@Transactional(readOnly = true)
	public AgeStateView ageState(String userId) {
		UserProfile profile = repository.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		if (!profile.birthDateDeclared()) {
			return new AgeStateView("BIRTH_DATE_REQUIRED", false, false, false);
		}
		LocalDate today = LocalDate.now(clock.withZone(java.time.ZoneId.of("America/Sao_Paulo")));
		boolean minor = profile.isMinorOn(today);
		// O estado de supervisao e derivado do vinculo ACEITO por um adulto — nunca de uma flag do
		// cliente. Completar 18 anos encerra a exigencia automaticamente (isMinorOn recalcula).
		boolean supervised = minor && parentalLinks.findActiveByMinor(userId).isPresent();
		return new AgeStateView(minor ? (supervised ? "MINOR_SUPERVISED" : "MINOR_CONSENT_PENDING")
			: "ADULT_DECLARED", minor, true, supervised);
	}

	public record AgeStateView(String state, boolean minor, boolean declared, boolean supervised) {
	}

	@Override
	@Transactional
	public OwnProfileView update(UpdateProfileCommand command) {
		UserProfile profile = repository.findById(command.userId())
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		String username = trim(command.username());
		if (username != null && !username.equals(profile.username())) {
			String slug = slug(username);
			if (repository.existsByUsername(slug)) {
				throw new ApiException(409, "USERNAME_TAKEN", "Nome de usuario ja esta em uso.");
			}
			if (com.socialcoisa.rede.shared.web.AdminGuard.isReservedUsername(slug) && !profile.admin()) {
				throw new ApiException(409, "USERNAME_RESERVED",
					"Este nome de usuario e reservado para a equipe.");
			}
			username = slug;
		}
		validateLengths(command);
		// The single badge column is shown like the badge list: an official id there needs ownership too.
		if (command.badge() != null && !officialBadges.ownsAllOfficial(profile.id(), List.of(command.badge().trim()))) {
			throw new ApiException(403, "NOT_OWNED", "Voce so pode exibir emblemas oficiais que tem.");
		}
		String portfolio = com.socialcoisa.rede.shared.web.SafeUrl.requireHttpUrl(trim(command.portfolio()), "portfolio");
		List<String> externalLinks = com.socialcoisa.rede.shared.web.SafeUrl
			.requireHttpUrls(clean(command.externalLinks()), "link");
		profile.update(trim(command.name()), trim(command.headline()), trim(command.company()), trim(command.location()),
			trim(command.avatar()), trim(command.banner()), trim(command.badge()), trim(command.bio()),
			clean(command.skills()),
			clean(command.goals()), trim(command.availability()), portfolio, externalLinks,
			command.openToWork(), command.isMentor(), username, trim(command.aka()), clock.instant());
		// Curriculo: ausente no corpo da requisicao mantem o que ja estava; vazio limpa. Nao passa
		// pelo `update` geral porque nao e campo de exibicao — e arquivo, e so o dono le.
		if (command.resumeUrl() != null) {
			profile.updateResume(com.socialcoisa.rede.shared.web.SafeUrl.requireHttpUrl(command.resumeUrl(), "curriculo"), command.resumeName(),
				clock.instant());
		}
		// A bio em HTML e sanitizada aqui, como o HTML do post de pagina: allowlist de tags e CSS.
		// Vazio limpa (o perfil volta para a bio de texto); ausente no corpo da requisicao mantem.
		if (command.bioHtml() != null) {
			profile.updateBioHtml(StringUtils.hasText(command.bioHtml())
				? SafeHtml.requireProfileHtml(command.bioHtml())
				: null, clock.instant());
		}
		// A capa em HTML/CSS segue a mesma regra: sanitizada aqui, vazia limpa (volta para a foto),
		// ausente no corpo da requisicao mantem.
		if (command.bannerHtml() != null) {
			profile.updateBannerHtml(StringUtils.hasText(command.bannerHtml())
				? SafeHtml.requireProfileHtml(command.bannerHtml())
				: null, clock.instant());
		}
		// Data de nascimento: ausente no corpo preserva; presente salva via declareBirthDate
		// (mesma validacao do endpoint dedicado).
		if (command.birthDate() != null) {
			if (StringUtils.hasText(command.birthDate())) {
				java.time.LocalDate date;
				try {
					date = java.time.LocalDate.parse(command.birthDate().trim());
				}
				catch (Exception e) {
					throw new ApiException(400, "INVALID_BIRTH_DATE",
						"Data de nascimento invalida (use aaaa-mm-dd).");
				}
				profile.declareBirthDate(date, clock.instant());
			}
		}
		return OwnProfileView.from(repository.save(profile));
	}

	private String slug(String value) {
		if (!StringUtils.hasText(value)) return null;
		String slug = value.toLowerCase(java.util.Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
		return StringUtils.hasText(slug) ? slug : null;
	}

	private String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : value;
	}

	private List<String> clean(List<String> values) {
		return values == null ? null : values.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
	}
}
