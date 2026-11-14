package com.socialcoisa.rede.shared.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Session transport for browsers: HttpOnly cookies, so no script on the origin can read the tokens.
 * The access cookie is SameSite=Lax (top-level navigations keep the login); the refresh cookie is
 * Strict because only the app itself calls /auth/refresh. Writes still need the CSRF header, see
 * {@link SessionCookieFilter}.
 */
@Component
public class SessionCookies {

	public static final String SESSION = "enois_session";
	public static final String REFRESH = "enois_refresh";
	public static final String TWO_FACTOR = "enois_2fa_challenge";

	private final boolean secure;
	private final Clock clock;

	public SessionCookies(@Value("${rede.auth.cookie-secure:true}") boolean secure, Clock clock) {
		this.secure = secure;
		this.clock = clock;
	}

	/** Set-Cookie values that carry a freshly issued session. */
	public List<String> issue(String token, Instant expiresAt, String refreshToken, Instant refreshExpiresAt) {
		Instant now = clock.instant();
		return List.of(
			cookie(SESSION, token, "Lax", Duration.between(now, expiresAt)),
			cookie(REFRESH, refreshToken, "Strict", refreshExpiresAt == null ? Duration.ZERO : Duration.between(now, refreshExpiresAt)),
			cookie(TWO_FACTOR, "", "Lax", Duration.ZERO));
	}

	public List<String> challenge(String id, Instant expiresAt) {
		return List.of(cookie(SESSION, "", "Lax", Duration.ZERO), cookie(REFRESH, "", "Strict", Duration.ZERO),
			cookie(TWO_FACTOR, id, "Lax", Duration.between(clock.instant(), expiresAt)));
	}

	/** Set-Cookie values that delete both cookies. */
	public List<String> clear() {
		return List.of(cookie(SESSION, "", "Lax", Duration.ZERO), cookie(REFRESH, "", "Strict", Duration.ZERO),
			cookie(TWO_FACTOR, "", "Lax", Duration.ZERO));
	}

	private String cookie(String name, String value, String sameSite, Duration maxAge) {
		return ResponseCookie.from(name, value == null ? "" : value)
			.httpOnly(true)
			.secure(secure)
			.sameSite(sameSite)
			.path("/")
			.maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
			.build()
			.toString();
	}
}
