package com.socialcoisa.rede.shared.web;

import java.time.Clock;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;

@Component
public class CurrentUserResolver {

	private final AuthSessionRepository authSessionRepository;
	private final Clock clock;

	public CurrentUserResolver(AuthSessionRepository authSessionRepository, Clock clock) {
		this.authSessionRepository = authSessionRepository;
		this.clock = clock;
	}

	public CurrentUser resolve(String userId) {
		if (!StringUtils.hasText(userId)) {
			throw new ApiException(401, "UNAUTHENTICATED", "Usuario autenticado ausente.");
		}
		return new CurrentUser(new UserId(userId.trim()));
	}

	public CurrentUser resolve(String userId, String sessionToken) {
		if (StringUtils.hasText(userId)) {
			return resolve(userId);
		}
		if (!StringUtils.hasText(sessionToken)) {
			throw new ApiException(401, "UNAUTHENTICATED", "Usuario autenticado ausente.");
		}
		String resolvedUserId = authSessionRepository.findActiveUserId(sessionToken.trim(), clock.instant())
			.orElseThrow(() -> new ApiException(401, "UNAUTHENTICATED", "Sessao invalida ou expirada."));
		return new CurrentUser(new UserId(resolvedUserId));
	}
}
