package com.socialcoisa.rede.identity.adapters.in.web;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.identity.adapters.out.oauth.OAuthProperties;
import com.socialcoisa.rede.identity.application.AuthResponse;
import com.socialcoisa.rede.identity.domain.OAuthProvider;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.SessionCookies;
import com.socialcoisa.rede.shared.web.SiteDomains;

/**
 * Ends a provider callback (a browser navigation) by sending the person back to the app on the site the
 * flow started on: the session cookies are set by the API host of that site, so returning to another
 * domain would look like a failed login.
 */
@Component
class OAuthBrowserReturn {

	private final OAuthProperties oAuthProperties;
	private final SiteDomains siteDomains;
	private final SessionCookies sessionCookies;

	OAuthBrowserReturn(OAuthProperties oAuthProperties, SiteDomains siteDomains, SessionCookies sessionCookies) {
		this.oAuthProperties = oAuthProperties;
		this.siteDomains = siteDomains;
		this.sessionCookies = sessionCookies;
	}

	Object success(AuthResponse auth, String siteDomain) {
		String redirectUrl = frontendRedirectUrl(siteDomain);
		if (!StringUtils.hasText(redirectUrl)) {
			return auth;
		}
		if (auth.twoFactor() != null) {
			return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl + "?status=two_factor_required"))
				.header(HttpHeaders.SET_COOKIE, sessionCookies.challenge(auth.twoFactor().challengeId(),
					auth.twoFactor().expiresAt()).toArray(String[]::new)).build();
		}
		// No credential in the URL (history, logs, Referer): the session rides on the 302 cookies.
		String scope = "SANCTIONS_ONLY".equals(auth.accessScope()) ? "&accessScope=SANCTIONS_ONLY" : "";
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl + "?status=ok" + scope))
			.header(HttpHeaders.SET_COOKIE, sessionCookies.issue(auth.token(), auth.expiresAt(), auth.refreshToken(),
				auth.refreshExpiresAt()).toArray(String[]::new))
			.build();
	}

	/** A cancel or failure on the provider screen comes back to the app with a readable code, not a raw error. */
	Object failure(OAuthProvider provider, String error, String siteDomain) {
		String code = StringUtils.hasText(error) ? error.trim().toUpperCase(Locale.ROOT) : "OAUTH_FAILED";
		String redirectUrl = frontendRedirectUrl(siteDomain);
		if (!StringUtils.hasText(redirectUrl)) {
			throw new ApiException(401, "OAUTH_" + code, "Nao foi possivel concluir o login com " + provider.name() + ".");
		}
		return ResponseEntity.status(HttpStatus.FOUND)
			.location(URI.create(redirectUrl + "?error=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
				+ "&provider=" + provider.name()))
			.build();
	}

	private String frontendRedirectUrl(String siteDomain) {
		return siteDomains.localize(oAuthProperties.getFrontendRedirectUrl(), siteDomain);
	}
}
