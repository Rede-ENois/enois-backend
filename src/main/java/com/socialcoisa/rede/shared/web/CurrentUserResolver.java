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

	/**
	 * O tratamento global de excecoes nao pode injetar este bean sem quebrar o boot (bean web
	 * resolvido antes do contexto completar), mas precisa distinguir sessao encerrada de token
	 * inexistente. Sao as mesmas dependencias que o proprio handler ja injeta por construtor.
	 */
	private static final ThreadLocal<AuthSessionRepository> AUTH_SESSIONS = new ThreadLocal<>();
	private static final ThreadLocal<Clock> CLOCK = new ThreadLocal<>();


	public CurrentUserResolver(AuthSessionRepository authSessionRepository, UserProfileRepository userProfileRepository,
			Clock clock) {
		this.authSessionRepository = authSessionRepository;
		this.userProfileRepository = userProfileRepository;
		this.clock = clock;
		AUTH_SESSIONS.set(authSessionRepository);
		CLOCK.set(clock);
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
			: activeUserId(sessionToken);
		String resolvedUserId = identity
			.orElseThrow(() -> unauthenticated(sessionToken, true));
		// Set before the verification check so a 403 EMAIL_NOT_VERIFIED is still attributed to the
		// user. RequestObservabilityFilter logs it on the access line and clears it.
		MDC.put(RequestObservabilityFilter.MDC_USER_ID, resolvedUserId);
		return requireVerifiedIdentity(new CurrentUser(new UserId(resolvedUserId)));
	}

	/**
	 * Recusa de sessao. Token que o banco conhece e que alguem encerrou ganha o detalhe
	 * {@code SESSION_REVOKED}: sem ele o app mandava "sua sessao expirou" para quem tinha acabado de
	 * trocar a senha. Token desconhecido continua igual — nada e dito sobre valor inventado.
	 *
	 * <p>{@code tokenDoPedido} diz que o token veio do pedido atual — o {@code SessionCookieFilter} ja
	 * o leu do cabecalho ou derivou do cookie. E o que separa "esta sessao foi encerrada" de um token
	 * qualquer que apareceu no caminho.</p>
	 * <p>Quando o token chegou, e ele que manda: a leitura em cache e da mesma requisicao e pode ter
	 * sido feita ANTES de a troca de senha revogar (a troca consulta a sessao para decidir se e admin).
	 * Nesse caso o banco ja responde certo, e por isso o motivo vem sempre de uma leitura sem cache.</p>
	 */
	static ApiException unauthenticated(String sessionToken, boolean tokenDoPedido) {
		ApiException refusal = new ApiException(401, "UNAUTHENTICATED", "Sessao invalida ou expirada.");
		if (!tokenDoPedido || !StringUtils.hasText(sessionToken) || AUTH_SESSIONS.get() == null) {
			return refusal;
		}
		boolean revoked = AUTH_SESSIONS.get()
			.findInactiveReason(sessionToken.trim(), CLOCK.get().instant())
			.filter(reason -> reason == AuthSessionRepository.InactiveSession.REVOKED)
			.isPresent();
		return revoked ? refusal.withDetail("SESSION_REVOKED") : refusal;
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
