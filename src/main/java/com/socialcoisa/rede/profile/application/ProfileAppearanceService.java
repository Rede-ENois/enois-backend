package com.socialcoisa.rede.profile.application;

import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.preferences.domain.ThemeIds;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.ProfileAppearance;
import com.socialcoisa.rede.profile.domain.ProfileLook;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.application.CacheConfig;
import com.socialcoisa.rede.shared.application.CacheInvalidator;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * A aparência da vitrine: o que o dono do perfil escolhe para quem visita ver.
 *
 * Diferente do tema da conta (que é a preferência de quem está olhando), esta escolha
 * viaja com o perfil — por isso social e profissional são independentes: são duas
 * identidades e cada uma tem o seu visual.
 *
 * Cada valor é uma lista FECHADA. Tema precisa existir no catálogo, cor é hex de seis
 * dígitos, fonte/cabeçalho/borda são nomes conhecidos e a decoração tem três níveis.
 * Nada disso vira CSS livre: o pior que uma requisição consegue é escolher outra opção
 * válida, nunca injetar regra na página.
 */
@Service
public class ProfileAppearanceService {

	/** Famílias de fonte seguras (as mesmas do nome do perfil). */
	static final Set<String> FONTS = Set.of("sans", "serif", "mono", "rounded", "display");
	/** Estilo do cabeçalho: cor sólida, degradê do tema ou limpo (sem pintura). */
	static final Set<String> HEADERS = Set.of("solid", "gradient", "clean");
	/** Cantos: arredondados do tema ou retos (máquinas antigas). */
	static final Set<String> BORDERS = Set.of("rounded", "square");
	/** 0 = sem decoração, 1 = discreta, 2 = marcante. */
	static final int MAX_DECORATION = 2;

	private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

	private final UserProfileRepository repository;
	private final CacheInvalidator cacheInvalidator;
	private final Clock clock;

	public ProfileAppearanceService(UserProfileRepository repository, CacheInvalidator cacheInvalidator, Clock clock) {
		this.repository = repository;
		this.cacheInvalidator = cacheInvalidator;
		this.clock = clock;
	}

	public ProfileAppearance appearanceOf(String userId) {
		return profileOf(userId).profileAppearance();
	}

	/**
	 * Grava a aparência e derruba a projeção pública de quem salvou.
	 *
	 * <p>A aparência viaja dentro do perfil público, que tem cache próprio. Sem esta limpeza, quem
	 * visita continuaria recebendo o visual anterior até o TTL vencer — a mudança só apareceria
	 * para o dono, que lê a própria conta. Só as entradas DESTA pessoa saem (por id e por nome),
	 * como no resto do perfil.</p>
	 */
	@Transactional
	public ProfileAppearance update(String userId, ProfileAppearance requested) {
		UserProfile profile = profileOf(userId);
		ProfileAppearance clean = new ProfileAppearance(sanitize(requested == null ? null : requested.social()),
			sanitize(requested == null ? null : requested.professional()));
		profile.updateProfileAppearance(clean, clock.instant());
		ProfileAppearance saved = repository.save(profile).profileAppearance();
		invalidarPerfilPublico(profile);
		return saved;
	}

	private void invalidarPerfilPublico(UserProfile profile) {
		cacheInvalidator.evictAfterCommit(CacheConfig.PUBLIC_PROFILES, "i:" + profile.id());
		String username = profile.username();
		if (username != null) {
			cacheInvalidator.evictAfterCommit(CacheConfig.PUBLIC_PROFILES, "u:" + username.toLowerCase(Locale.ROOT));
		}
	}

	private UserProfile profileOf(String userId) {
		return repository.findById(userId)
			.orElseThrow(() -> new ApiException(404, "PROFILE_NOT_FOUND", "Perfil nao encontrado."));
	}

	private ProfileLook sanitize(ProfileLook look) {
		if (look == null) {
			return ProfileLook.empty();
		}
		return new ProfileLook(theme(look.theme()), accent(look.accent()), choice("fonte", look.font(), FONTS),
			choice("cabecalho", look.header(), HEADERS), look.textures(),
			choice("borda", look.borders(), BORDERS), decoration(look.decoration()));
	}

	private String theme(String theme) {
		if (theme == null || theme.isBlank()) {
			return null;
		}
		String normalized = theme.trim();
		if (!ThemeIds.ALL.contains(normalized)) {
			throw new ApiException(400, "INVALID_PROFILE_THEME", "Tema de perfil desconhecido: escolha um dos temas disponiveis.");
		}
		return normalized;
	}

	private String accent(String accent) {
		if (accent == null || accent.isBlank()) {
			return null;
		}
		String normalized = accent.trim().toLowerCase(Locale.ROOT);
		if (!HEX.matcher(normalized).matches()) {
			throw new ApiException(400, "INVALID_PROFILE_ACCENT", "Cor do perfil precisa ser um hex como #7c3aed.");
		}
		return normalized;
	}

	private String choice(String label, String value, Set<String> allowed) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		if (!allowed.contains(normalized)) {
			throw new ApiException(400, "INVALID_PROFILE_APPEARANCE", "Valor de " + label + " nao reconhecido.");
		}
		return normalized;
	}

	private Integer decoration(Integer level) {
		if (level == null) {
			return null;
		}
		if (level < 0 || level > MAX_DECORATION) {
			throw new ApiException(400, "INVALID_PROFILE_APPEARANCE",
				"Decoracao do perfil vai de 0 a " + MAX_DECORATION + ".");
		}
		return level;
	}
}
