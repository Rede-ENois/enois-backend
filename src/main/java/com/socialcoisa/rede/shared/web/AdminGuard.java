package com.socialcoisa.rede.shared.web;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;

/**
 * Restringe operacoes administrativas a uma allowlist de e-mails definida em
 * {@code REDE_ADMIN_EMAILS} (lista separada por virgula). O papel de admin nao existe no schema,
 * entao a autorizacao e por configuracao.
 */
@Component
public class AdminGuard {

	private final UserProfileRepository profileRepository;
	private final Set<String> adminEmails;

	public AdminGuard(UserProfileRepository profileRepository,
			@Value("${rede.admin.emails:}") String adminEmails) {
		this.profileRepository = profileRepository;
		this.adminEmails = Arrays.stream(adminEmails.split(","))
			.map(String::trim)
			.filter(email -> !email.isEmpty())
			.map(email -> email.toLowerCase(Locale.ROOT))
			.collect(Collectors.toUnmodifiableSet());
	}

	public void requireAdmin(CurrentUser user) {
		String email = profileRepository.findById(user.id().value())
			.map(UserProfile::email)
			.orElse(null);
		if (email == null || !adminEmails.contains(email.toLowerCase(Locale.ROOT))) {
			throw new ApiException(403, "FORBIDDEN", "Acesso restrito a administradores.");
		}
	}
}