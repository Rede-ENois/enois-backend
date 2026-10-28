package com.socialcoisa.rede.profile.application;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.profile.application.port.out.OfficialBadgeReader;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
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

	private final UserProfileRepository repository;
	private final OfficialBadgeReader officialBadges;
	private final Clock clock;

	public UpdateIdentityDisplayService(UserProfileRepository repository, OfficialBadgeReader officialBadges,
			Clock clock) {
		this.repository = repository;
		this.officialBadges = officialBadges;
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
	 * Ate 5 emblemas por perfil. Cada um e uma URL do nosso CDN (upload pela media);
	 * o primeiro acompanha o nome por todo o app, todos aparecem no perfil. Uma lista
	 * nula ou vazia limpa.
	 */
	public UserProfile updateBadges(String userId, List<String> badges) {
		UserProfile profile = repository.findById(userId)
			.orElseThrow(() -> new ApiException(404, "PROFILE_NOT_FOUND", "Perfil nao encontrado."));
		if (badges != null && badges.size() > 5) {
			throw new ApiException(400, "MAX_BADGES", "Sao no maximo 5 emblemas.");
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
		profile.updateBadges(clean, clock.instant());
		return repository.save(profile);
	}

	private void check(String field, String value, int max) {
		if (value != null && value.trim().length() > max) {
			throw new ApiException(400, "FIELD_TOO_LONG", field + " deve ter no maximo " + max + " caracteres.");
		}
	}
}
