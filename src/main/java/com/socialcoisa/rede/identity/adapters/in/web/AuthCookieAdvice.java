package com.socialcoisa.rede.identity.adapters.in.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import com.socialcoisa.rede.identity.application.AuthResponse;
import com.socialcoisa.rede.shared.web.SessionCookies;

/**
 * Every {@link AuthResponse} written by the auth endpoints (login, 2FA, e-mail verification,
 * refresh, OAuth token) leaves the tokens in HttpOnly cookies; the JSON body never carries them.
 */
@ControllerAdvice(assignableTypes = AuthController.class)
class AuthCookieAdvice implements ResponseBodyAdvice<Object> {

	private final SessionCookies cookies;

	AuthCookieAdvice(SessionCookies cookies) {
		this.cookies = cookies;
	}

	@Override
	public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
		return true;
	}

	@Override
	public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
			Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request,
			ServerHttpResponse response) {
		if (body instanceof AuthResponse auth) {
			var valores = auth.twoFactor() == null
				? cookies.issue(auth.token(), auth.expiresAt(), auth.refreshToken(), auth.refreshExpiresAt())
				: cookies.challenge(auth.twoFactor().challengeId(), auth.twoFactor().expiresAt());
			valores
				.forEach(value -> response.getHeaders().add(HttpHeaders.SET_COOKIE, value));
		}
		return body;
	}
}
