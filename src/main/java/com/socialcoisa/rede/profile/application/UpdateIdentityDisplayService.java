package com.socialcoisa.rede.profile.application;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.profile.application.port.out.BadgeKitRepository;
import com.socialcoisa.rede.profile.application.port.out.OfficialBadgeReader;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.BadgeKit;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.text.PlainText;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Identidade exibida: apelido, nome social e pronomes.
 *
 * Regras do produto: o nome social substitui o nome de cadastro para todo mundo (o
 * de cadastro fica para o dono da conta e para a empresa que recebe a candidatura);
 * o apelido entra entre parenteses depois do nome; os pronomes aparecem so no
 * perfil. Valor nulo/vazio LIMPA o campo — "nao quero mais" e uma intencao.
 */
@Service
public class UpdateIdentityDisplayService {

	/** Teto de emblemas por perfil — a mesma régua do domínio, do design system e do front. */
	private static final int MAX_BADGES = UserProfile.MAX_BADGES;

	private final UserProfileRepository repository;
	private final OfficialBadgeReader officialBadges;
	private final BadgeKitRepository badgeKits;
	private final Clock clock;

	public UpdateIdentityDisplayService(UserProfileRepository repository, OfficialBadgeReader officialBadges,
			BadgeKitRepository badgeKits, Clock clock) {
		this.repository = repository;
		this.officialBadges = officialBadges;
		this.badgeKits = badgeKits;
		this.clock = clock;
	}

	public UserProfile update(String userId, String nickname, String socialName, String pronouns) {
		UserProfile profile = repository.findById(userId)
			.orElseThrow(() -> new ApiException(404, "PROFILE_NOT_FOUND", "Perfil nao encontrado."));
		check("apelido", nickname, 60);
		check("nome social", socialName, 160);
		check("pronomes", pronouns, 60);
		profile.updateIdentityDisplay(PlainText.defuse(nickname), PlainText.defuse(socialName),
			PlainText.defuse(pronouns), clock.instant());
		return repository.save(profile);
	}

	/**
	 * Ate {@link #MAX_BADGES} emblemas por perfil. Cada um e uma URL do nosso CDN (upload pela
	 * media) ou id oficial ({@code emblema-*}); o primeiro acompanha o nome por todo o app, todos
	 * aparecem no perfil. Uma lista nula ou vazia limpa.
	 *
	 * <p>Quando o conjunto muda de verdade, o conjunto ANTERIOR entra no historico de kits
	 * (maximo 3) antes da troca — e aplicar um kit do historico tambem passa por aqui, entao o
	 * conjunto substituido volta para o historico e nada se perde.</p>
	 */
	public UserProfile updateBadges(String userId, List<String> badges) {
		UserProfile profile = repository.findById(userId)
			.orElseThrow(() -> new ApiException(404, "PROFILE_NOT_FOUND", "Perfil nao encontrado."));
		if (badges != null && badges.size() > MAX_BADGES) {
			throw new ApiException(400, "MAX_BADGES", "Sao no maximo " + MAX_BADGES + " emblemas.");
		}
		List<String> clean = badges == null ? List.of()
			: badges.stream()
				.map(String::trim)
				.filter(value -> !value.isEmpty())
				.peek(value -> check("emblema", value, 500))
				.toList();
		if (!officialBadges.ownsAllOfficial(userId, clean)) {
			throw new ApiException(403, "NOT_OWNED", "Voce so pode exibir emblemas oficiais que tem.");
		}
		List<String> previous = profile.allBadges();
		if (!clean.equals(previous) && !previous.isEmpty() && !isNewestKit(userId, previous)) {
			// Snapshot do ANTERIOR: o historico guarda o que a pessoa tinha, nao o que escolheu agora.
			// Lista anterior vazia nao vira kit (nao ha nada a restaurar) e repetir o kit mais
			// recente nao acrescenta nada a tela.
			badgeKits.record(userId, BadgeKit.create(previous, clock.instant()));
		}
		profile.updateBadges(clean, clock.instant());
		return repository.save(profile);
	}

	/** Historico de kits da pessoa, do mais recente para o mais antigo (maximo 3). */
	public List<BadgeKit> badgeHistory(String userId) {
		return badgeKits.history(userId);
	}

	/** Aplica um kit do historico (as regras do updateBadges valem: posse, limite, validacao). */
	public UserProfile applyBadgeKit(String userId, String kitId) {
		BadgeKit kit = badgeKits.find(userId, kitId)
			.orElseThrow(() -> new ApiException(404, "KIT_NOT_FOUND", "Kit nao encontrado no historico."));
		UserProfile profile = updateBadges(userId, kit.badges());
		// O kit aplicado virou o conjunto atual: ficar no historico so duplicaria a tela.
		badgeKits.delete(userId, kitId);
		return profile;
	}

	private boolean isNewestKit(String userId, List<String> badges) {
		List<BadgeKit> history = badgeKits.history(userId);
		return !history.isEmpty() && history.get(0).badges().equals(badges);
	}

	private void check(String field, String value, int max) {
		if (value != null && value.trim().length() > max) {
			throw new ApiException(400, "FIELD_TOO_LONG", field + " deve ter no maximo " + max + " caracteres.");
		}
	}
}
