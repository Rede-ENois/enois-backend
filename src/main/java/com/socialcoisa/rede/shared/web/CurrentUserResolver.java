package com.socialcoisa.rede.shared.web;

import java.time.Clock;
import java.util.Optional;

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
		if (!StringUtils.hasText(sessionToken)) {
			throw new ApiException(401, "UNAUTHENTICATED", "Sessao invalida ou expirada.");
		}
		String resolvedUserId = activeUserId(sessionToken)
			.orElseThrow(() -> new ApiException(401, "UNAUTHENTICATED", "Sessao invalida ou expirada."));
		// Set before the verification check so a 403 EMAIL_NOT_VERIFIED is still attributed to the
		// user. RequestObservabilityFilter logs it on the access line and clears it.
		MDC.put(RequestObservabilityFilter.MDC_USER_ID, resolvedUserId);
		return requireVerifiedIdentity(new CurrentUser(new UserId(resolvedUserId)));
	}

	/**
	 * Quem é a sessão, quando ela é válida agora. O `resolve` LANÇA para leitura e escrita
	 * autenticadas; este é o chamador que precisa saber quem agiu sem transformar ausência de sessão
	 * em erro — a trilha de recusas administrativas, por exemplo, precisa do id quando ele existe e
	 * segue sem ele quando não existe.
	 */
	public Optional<String> activeUserId(String sessionToken) {
		if (!StringUtils.hasText(sessionToken)) {
			return Optional.empty();
		}
		return authSessionRepository.findActiveUserId(sessionToken.trim(), clock.instant());
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
