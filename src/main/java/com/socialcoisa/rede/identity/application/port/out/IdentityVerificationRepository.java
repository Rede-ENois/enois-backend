package com.socialcoisa.rede.identity.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import com.socialcoisa.rede.identity.domain.IdentityVerification;

public interface IdentityVerificationRepository {

	Optional<IdentityVerification> findByUserId(String userId);

	/** Localiza pela tentativa/state (callback do provedor chega sem sessao). */
	Optional<IdentityVerification> findByAttemptId(String attemptId);

	void save(IdentityVerification verification);

	/** Tentativas iniciadas desde {@code since} por um provedor — base da contabilidade mensal. */
	int countStartedSince(String provider, Instant since);

	/** Selo "Humanidade Comprovada": verdadeiro so quando existe tentativa VERIFIED para a conta. */
	boolean isVerified(String userId);

	/** Quem da pagina carrega o selo — uma consulta por pagina, nunca uma por item. */
	Set<String> verifiedAmong(Collection<String> userIds);
}
