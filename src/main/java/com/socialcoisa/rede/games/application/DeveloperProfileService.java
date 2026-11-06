package com.socialcoisa.rede.games.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.games.application.port.out.DeveloperFollowRepository;
import com.socialcoisa.rede.games.application.port.out.DeveloperProfileRepository;
import com.socialcoisa.rede.games.application.port.out.GameRepository;
import com.socialcoisa.rede.games.domain.DeveloperProfile;
import com.socialcoisa.rede.games.domain.Game;
import com.socialcoisa.rede.games.domain.GameStatus;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * A página do criador: quem ele é, o que publicou e quem o acompanha.
 *
 * A identidade NÃO é duplicada: nome, @, foto e capa continuam vindo do perfil do Énóis, e a página
 * do criador só acrescenta o que é do Games (nome de estúdio, chamada, bio de criador, links). Quem
 * não preencheu nada não ganha página — a listagem dele continua sendo `/games/{username}`.
 */
@Service
public class DeveloperProfileService {

	private static final int MAX_NAME = 120;
	private static final int MAX_HEADLINE = 200;
	private static final int MAX_BIO = 2000;
	private static final int MAX_LINKS = 500;

	private final DeveloperProfileRepository profiles;
	private final DeveloperFollowRepository follows;
	private final GameRepository games;
	private final UserProfileRepository userProfiles;
	private final com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate;
	private final Clock clock;

	public DeveloperProfileService(DeveloperProfileRepository profiles, DeveloperFollowRepository follows,
			GameRepository games, UserProfileRepository userProfiles, com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate,
			Clock clock) {
		this.sanctionGate = sanctionGate;
		this.profiles = profiles;
		this.follows = follows;
		this.games = games;
		this.userProfiles = userProfiles;
		this.clock = clock;
	}

	/** O que a página pública precisa, numa resposta só. */
	public record DeveloperPage(UserProfile user, DeveloperProfile profile, List<Game> games, long followers,
			boolean following, boolean owner, long totalFollowers) {
	}

	@Transactional(readOnly = true)
	public DeveloperPage page(String username, String viewerId) {
		UserProfile user = userProfiles.findByUsername(username)
			.filter(found -> !sanctionGate.banned(found.id()))
			.orElseThrow(() -> new ApiException(404, "DEVELOPER_NOT_FOUND", "Criador não encontrado."));
		DeveloperProfile profile = profiles.findByUserId(user.id()).orElse(null);
		List<Game> published = games.findByUserAndStatus(user.id(), GameStatus.PUBLISHED, 60);
		boolean owner = viewerId != null && viewerId.equals(user.id());
		// Página "vazia" não é página: um perfil de desenvolvedor criado sem nenhum campo
		// preenchido (ex.: abriu o painel e salvou em branco) NÃO conta como presença de dev.
		if (published.isEmpty() && !hasContent(profile) && !owner) {
			// Sem jogo publicado e sem página preenchida não há o que mostrar (nem para indexar).
			throw new ApiException(404, "DEVELOPER_NOT_FOUND", "Criador não encontrado.");
		}
		long followers = follows.countByDeveloper(user.id());
		boolean following = viewerId != null && follows.exists(viewerId, user.id());
		return new DeveloperPage(user, profile, published, followers, following, owner, followers);
	}

	private boolean hasContent(DeveloperProfile profile) {
		return profile != null && (StringUtils.hasText(profile.displayName())
			|| StringUtils.hasText(profile.headline()) || StringUtils.hasText(profile.bio())
			|| StringUtils.hasText(profile.links()));
	}

	/** O dono edita a própria página. Vazio limpa o campo. */
	@Transactional
	public DeveloperProfile save(String userId, String displayName, String headline, String bio, String links,
			String avatar, String cover) {
		DeveloperProfile profile = profiles.findByUserId(userId).orElseGet(() -> DeveloperProfile.create(userId,
			clock.instant()));
		return profiles.save(profile.update(clean(displayName, MAX_NAME), clean(headline, MAX_HEADLINE),
			clean(bio, MAX_BIO), com.socialcoisa.rede.shared.web.SafeUrl.requireHttpUrls(clean(links, MAX_LINKS), "[,\\n]", "links"),
			clean(avatar, 500), clean(cover, 500), clock.instant()));
	}

	@Transactional(readOnly = true)
	public DeveloperProfile mine(String userId) {
		return profiles.findByUserId(userId).orElse(null);
	}

	/** Acompanhar é de mão única e idempotente; acompanhar a si mesmo não faz sentido. */
	@Transactional
	public boolean toggleFollow(String followerId, String developerUserId) {
		if (followerId.equals(developerUserId)) {
			throw new ApiException(400, "CANNOT_FOLLOW_SELF", "Você não pode acompanhar você mesmo.");
		}
		if (follows.exists(followerId, developerUserId)) {
			follows.unfollow(followerId, developerUserId);
			return false;
		}
		follows.follow("dfol_" + UUID.randomUUID().toString().replace("-", ""), followerId, developerUserId,
			clock.instant());
		return true;
	}

	@Transactional(readOnly = true)
	public List<String> followedBy(String followerId) {
		return follows.followedBy(followerId);
	}

	/** Perfis de vários criadores, para a listagem não fazer uma consulta por card. */
	@Transactional(readOnly = true)
	public Map<String, DeveloperProfile> byUserIds(List<String> userIds) {
		Map<String, DeveloperProfile> map = new HashMap<>();
		profiles.findAllByUserIds(new ArrayList<>(userIds)).forEach(profile -> map.put(profile.userId(), profile));
		return map;
	}

	private String clean(String value, int max) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		String trimmed = value.trim();
		if (trimmed.length() > max) {
			throw new ApiException(400, "FIELD_TOO_LONG", "O campo aceita no máximo " + max + " caracteres.");
		}
		return trimmed;
	}
}
