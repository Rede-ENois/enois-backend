package com.socialcoisa.rede.identity.application.port.out;

import java.util.Optional;

import com.socialcoisa.rede.identity.domain.PasswordResetToken;

public interface PasswordResetTokenRepository {

	void save(PasswordResetToken token);

	Optional<PasswordResetToken> findByToken(String token);

	void markUsed(String token);
}
