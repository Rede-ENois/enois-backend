package com.socialcoisa.rede.shared.web;

import java.time.Clock;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;

@Component
public class CurrentUserResolver {

	private final AuthSessionRepository authSessionRepository;
	private final UserProfileRepository userProfileRepository;
	private final Clock clock;

	public CurrentUserResolver(AuthSessionRepository authSessionRepository, UserProfileRepository userProfileRepository,
			Clock clock) {
		this.authSessionRepository = authSessionRepository;
		this.userProfileRepository = userProfileRepository;
		this.clock = clock;
	}

	public CurrentUser resolve(String sessionToken) {
		return resolve(sessionToken, false);
	}

	/** Apenas os endpoints da própria decisão e recurso podem aceitar o escopo limitado. */
	public CurrentUser resolveSanctions(String sessionToken) {
		return resolve(sessionToken, true);
	}

	private CurrentUser resolve(String sessionToken, boolean sanctionsOnly) {
		if (!StringUtils.hasText(sessionToken)) {
			throw new ApiException(401, "UNAUTHENTICATED", "Sessao invalida ou expirada.");
		}
		var identity = sanctionsOnly
			? authSessionRepository.findActiveSanctionsUserId(sessionToken.trim(), clock.instant())
			: authSessionRepository.findActiveUserId(sessionToken.trim(), clock.instant());
		String resolvedUserId = identity
			.orElseThrow(() -> new ApiException(401, "UNAUTHENTICATED", "Sessao invalida ou expirada."));
		// Set before the verification check so a 403 EMAIL_NOT_VERIFIED is still attributed to the
		// user. RequestObservabilityFilter logs it on the access line and clears it.
		MDC.put(RequestObservabilityFilter.MDC_USER_ID, resolvedUserId);
		return requireVerifiedIdentity(new CurrentUser(new UserId(resolvedUserId)));
	}

	/**
	 * Conta sem identidade verificada nao usa as funcionalidades protegidas. Vale confirmar o e-mail
	 * OU ter segundo fator ativo (por aplicativo ou por e-mail) — e a mesma regra que zera a
	 * credibilidade. O cadastro nao emite sessao, entao esta e a segunda barreira: pega uma sessao
	 * criada por outro caminho que ainda nao tenha passado pela verificacao.
	 */
	private CurrentUser requireVerifiedIdentity(CurrentUser user) {
		userProfileRepository.findById(user.id().value()).ifPresent(profile -> {
			if (!profile.identityVerified()) {
				throw new ApiException(403, "EMAIL_NOT_VERIFIED",
					"Confirme seu e-mail ou ative a verificacao em duas etapas para usar a plataforma.");
			}
		});
		return user;
	}
}
