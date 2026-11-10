package com.socialcoisa.rede.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.identity.application.port.out.IdentityReleaseGate;
import com.socialcoisa.rede.identity.application.port.out.IdentityVerificationProvider;
import com.socialcoisa.rede.identity.application.port.out.IdentityVerificationRepository;
import com.socialcoisa.rede.identity.adapters.out.provider.IdentityVerificationProviderRegistry;
import com.socialcoisa.rede.identity.domain.IdentityVerification;
import com.socialcoisa.rede.identity.domain.IdentityVerificationStatus;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Orquestra a verificacao de identidade civil. Nao conhece fornecedor: usa o provedor registrado.
 * Sem provedor, a verificacao fica INDISPONIVEL (fail-safe) — o estado nunca vai a VERIFIED por
 * falha tecnica ou ausencia de integracao.
 */
@Service
public class IdentityVerificationService {

	private final IdentityVerificationRepository repository;
	private final IdentityVerificationProviderRegistry providers;
	private final IdentityReleaseGate release;
	private final Clock clock;

	public IdentityVerificationService(IdentityVerificationRepository repository,
			IdentityVerificationProviderRegistry providers, IdentityReleaseGate release, Clock clock) {
		this.repository = repository;
		this.providers = providers;
		this.release = release;
		this.clock = clock;
	}

	public boolean providerAvailable() {
		return providers.isAvailable();
	}

	@Transactional
	public IdentityVerification current(String userId) {
		IdentityVerification existing = repository.findByUserId(userId).orElse(null);
		if (existing == null) {
			return IdentityVerification.notVerified("ident_" + UUID.randomUUID().toString().replace("-", ""),
				userId, clock.instant());
		}
		// Vinculacao vencida deixa de valer (expira na leitura, sem laudo externo).
		if (existing.state() == IdentityVerificationStatus.VERIFIED && !existing.isValidAt(clock.instant())) {
			IdentityVerification expired = existing.transitionTo(IdentityVerificationStatus.EXPIRED, null, null,
				clock.instant());
			repository.save(expired);
			return expired;
		}
		return existing;
	}

	public boolean isVerified(String userId) {
		return current(userId).isValidAt(clock.instant());
	}

	public IdentityVerificationStatus statusOf(String userId) {
		return current(userId).state();
	}

	@Transactional
	public IdentityVerificationProvider.Start start(String userId, String consentVersion) {
		IdentityVerificationProvider provider = providers.active()
			.orElseThrow(() -> new ApiException(503, "IDENTITY_PROVIDER_UNAVAILABLE",
				"A verificacao de identidade ainda nao esta disponivel."));
		// Provedor pago: so quem foi liberado pela rodada pode iniciar, e so se houver capacidade
		// no ciclo. As duas checagens vem ANTES da chamada externa, para nao gerar cobranca.
		if (provider.requiresRelease()) {
			if (!release.granted(userId)) {
				throw new ApiException(403, "IDENTITY_NOT_RELEASED",
					"A verificacao de identidade ainda nao esta liberada para esta conta.");
			}
			release.requireCapacity();
		}
		IdentityVerification verification = current(userId);
		IdentityVerificationProvider.Start started = provider.start(userId, consentVersion);
		IdentityVerification pending = verification.transitionTo(IdentityVerificationStatus.PENDING, provider.name(),
			started.attemptId(), clock.instant());
		repository.save(pending);
		if (provider.requiresRelease()) {
			release.consume(userId);
		}
		return started;
	}

	/** Consulta o provedor e aplica o desfecho confirmado (o navegador nao decide nada). */
	@Transactional
	public IdentityVerification refresh(String userId) {
		IdentityVerificationProvider provider = providers.active()
			.orElseThrow(() -> new ApiException(503, "IDENTITY_PROVIDER_UNAVAILABLE",
				"A verificacao de identidade ainda nao esta disponivel."));
		IdentityVerification verification = current(userId);
		if (verification.state() != IdentityVerificationStatus.PENDING || verification.attemptId() == null) {
			return verification;
		}
		var outcome = provider.fetchOutcome(verification.attemptId());
		if (outcome.isEmpty()) {
			return verification;
		}
		IdentityVerification updated = verification
			.transitionTo(outcome.get().status(), null, null, clock.instant())
			.withReason(outcome.get().reasonCode(), clock.instant());
		repository.save(updated);
		return updated;
	}

	/**
	 * Conclui um retorno por callback (OAuth/OIDC): localiza a tentativa pelo state (uso unico),
	 * troca o codigo no backend e aplica o desfecho. O navegador nao decide nada.
	 */
	@Transactional
	public IdentityVerification completeCallback(String state, String code) {
		if (state == null || state.isBlank() || code == null || code.isBlank()) {
			throw new ApiException(400, "INVALID_IDENTITY_CALLBACK", "Retorno de verificacao invalido.");
		}
		IdentityVerification verification = repository.findByAttemptId(state)
			.orElseThrow(() -> new ApiException(404, "IDENTITY_ATTEMPT_NOT_FOUND",
				"Verificacao nao encontrada ou ja concluida."));
		// Uso unico: so vale a partir de PENDING.
		if (verification.state() != IdentityVerificationStatus.PENDING) {
			throw new ApiException(409, "IDENTITY_ATTEMPT_USED", "Esta verificacao ja foi concluida.");
		}
		IdentityVerificationProvider provider = providers.active()
			.orElseThrow(() -> new ApiException(503, "IDENTITY_PROVIDER_UNAVAILABLE",
				"A verificacao de identidade nao esta disponivel."));
		var outcome = provider.handleCallback(state, code)
			.orElseThrow(() -> new ApiException(502, "IDENTITY_PROVIDER_ERROR",
				"Nao foi possivel confirmar a verificacao com o provedor."));
		IdentityVerification updated = verification
			.transitionTo(outcome.status(), null, null, clock.instant())
			.withReason(outcome.reasonCode(), clock.instant());
		repository.save(updated);
		return updated;
	}

	/** Desvincula a identidade: a conta volta a nao verificada (nao apaga historico sensivel). */
	@Transactional
	public IdentityVerification revoke(String userId) {
		IdentityVerification verification = current(userId);
		if (verification.state() == IdentityVerificationStatus.NOT_VERIFIED) {
			return verification;
		}
		IdentityVerification revoked = verification.transitionTo(IdentityVerificationStatus.REVOKED, null, null,
			clock.instant());
		repository.save(revoked);
		return revoked;
	}

	/**
	 * Aplica o desfecho anunciado por webhook. O status recebido NAO e usado como prova: ele so
	 * identifica a tentativa, e o desfecho e relido no servidor do provedor. Webhook duplicado ou
	 * fora de ordem nao muda nada, porque o estado alvo sai do provedor, nao do evento.
	 */
	@Transactional
	public IdentityVerification applyProviderOutcome(String attemptId) {
		IdentityVerification verification = repository.findByAttemptId(attemptId)
			.orElseThrow(() -> new ApiException(404, "IDENTITY_ATTEMPT_NOT_FOUND",
				"Verificacao nao encontrada."));
		if (verification.state() != IdentityVerificationStatus.PENDING) {
			// Ja concluida: releitura nao pode reprocessar nem reverter.
			return verification;
		}
		IdentityVerificationProvider provider = providers.active().orElse(null);
		if (provider == null) {
			return verification;
		}
		var outcome = provider.fetchOutcome(attemptId);
		if (outcome.isEmpty()) {
			return verification;
		}
		IdentityVerification updated = verification
			.transitionTo(outcome.get().status(), null, null, clock.instant())
			.withReason(outcome.get().reasonCode(), clock.instant());
		repository.save(updated);
		return updated;
	}
}
