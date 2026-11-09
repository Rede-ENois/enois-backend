package com.socialcoisa.rede.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.identity.adapters.out.provider.IdentityVerificationProviderRegistry;
import com.socialcoisa.rede.identity.application.port.out.IdentityReleaseGate;
import com.socialcoisa.rede.identity.application.port.out.IdentityVerificationProvider;
import com.socialcoisa.rede.identity.application.port.out.IdentityVerificationRepository;
import com.socialcoisa.rede.identity.domain.IdentityVerification;
import com.socialcoisa.rede.identity.domain.IdentityVerificationStatus;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Maquina de estados e fail-safe da verificacao de identidade, sem Spring: repositorio em memoria e
 * um provedor falso (usado SO nos testes — nao existe em producao).
 */
class IdentityVerificationServiceTests {

	private static final String USER = "user_ident_test";
	private final Clock clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);

	private final InMemoryRepo repo = new InMemoryRepo();

	private IdentityVerificationService serviceWith(IdentityVerificationProvider... providers) {
		return new IdentityVerificationService(repo, new IdentityVerificationProviderRegistry(List.of(providers)),
			ALWAYS_RELEASED, clock);
	}

	/** Provedor de teste nao exige liberacao; a porta nunca e consultada. */
	private static final IdentityReleaseGate ALWAYS_RELEASED = new IdentityReleaseGate() {
		@Override public boolean granted(String userId) { return true; }
		@Override public void consume(String userId) { }
		@Override public void requireCapacity() { }
	};

	@Test
	void providerUnavailableFailsSafeAndDoesNotVerify() {
		IdentityVerificationService service = serviceWith(); // producao hoje: nenhum provedor

		assertThat(service.providerAvailable()).isFalse();
		assertThat(service.isVerified(USER)).isFalse();
		assertThat(service.statusOf(USER)).isEqualTo(IdentityVerificationStatus.NOT_VERIFIED);

		assertThatThrownBy(() -> service.start(USER, "v1"))
			.isInstanceOf(ApiException.class)
			.satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(503));
	}

	@Test
	void startGoesPendingAndOnlyProviderOutcomeVerifies() {
		FakeProvider provider = new FakeProvider();
		IdentityVerificationService service = serviceWith(provider);

		provider.startResult = new IdentityVerificationProvider.Start("attempt-1", "https://provedor/exemplo");
		var started = service.start(USER, "v1");
		assertThat(started.attemptId()).isEqualTo("attempt-1");
		assertThat(service.statusOf(USER)).isEqualTo(IdentityVerificationStatus.PENDING);
		// Pendente nao verifica.
		assertThat(service.isVerified(USER)).isFalse();

		// Sem desfecho ainda, continua pendente.
		provider.outcome = Optional.empty();
		assertThat(service.refresh(USER).state()).isEqualTo(IdentityVerificationStatus.PENDING);

		// Desfecho confirmado pelo provedor -> VERIFIED.
		provider.outcome = Optional.of(new IdentityVerificationProvider.Outcome(
			IdentityVerificationStatus.VERIFIED, null));
		assertThat(service.refresh(USER).state()).isEqualTo(IdentityVerificationStatus.VERIFIED);
		assertThat(service.isVerified(USER)).isTrue();
	}

	@Test
	void providerRejectionDoesNotVerify() {
		FakeProvider provider = new FakeProvider();
		IdentityVerificationService service = serviceWith(provider);
		service.start(USER, "v1");
		provider.outcome = Optional.of(new IdentityVerificationProvider.Outcome(
			IdentityVerificationStatus.REJECTED, "DOCUMENT_MISMATCH"));

		IdentityVerification result = service.refresh(USER);
		assertThat(result.state()).isEqualTo(IdentityVerificationStatus.REJECTED);
		assertThat(service.isVerified(USER)).isFalse();
	}

	@Test
	void revokedIdentityStopsBeingVerified() {
		FakeProvider provider = new FakeProvider();
		IdentityVerificationService service = serviceWith(provider);
		service.start(USER, "v1");
		provider.outcome = Optional.of(new IdentityVerificationProvider.Outcome(
			IdentityVerificationStatus.VERIFIED, null));
		service.refresh(USER);
		assertThat(service.isVerified(USER)).isTrue();

		service.revoke(USER);
		assertThat(service.isVerified(USER)).isFalse();
		assertThat(service.statusOf(USER)).isEqualTo(IdentityVerificationStatus.REVOKED);
	}

	@Test
	void callbackVerifiesAndCannotBeReplayed() {
		FakeProvider provider = new FakeProvider();
		IdentityVerificationService service = serviceWith(provider);
		var started = service.start(USER, "v1");
		assertThat(service.statusOf(USER)).isEqualTo(IdentityVerificationStatus.PENDING);

		provider.callbackOutcome = Optional.of(new IdentityVerificationProvider.Outcome(
			IdentityVerificationStatus.VERIFIED, null));
		assertThat(service.completeCallback(started.attemptId(), "codigo").state())
			.isEqualTo(IdentityVerificationStatus.VERIFIED);
		assertThat(service.isVerified(USER)).isTrue();

		// Uso unico: repetir o mesmo state nao pode reconcluir.
		assertThatThrownBy(() -> service.completeCallback(started.attemptId(), "codigo"))
			.isInstanceOf(ApiException.class)
			.satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("IDENTITY_ATTEMPT_USED"));
	}

	@Test
	void callbackWithUnknownStateIsRejected() {
		FakeProvider provider = new FakeProvider();
		IdentityVerificationService service = serviceWith(provider);
		assertThatThrownBy(() -> service.completeCallback("state-desconhecido", "codigo"))
			.isInstanceOf(ApiException.class)
			.satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("IDENTITY_ATTEMPT_NOT_FOUND"));
	}

	@Test
	void providerFailureDoesNotVerify() {
		FakeProvider provider = new FakeProvider();
		IdentityVerificationService service = serviceWith(provider);
		var started = service.start(USER, "v1");
		provider.callbackOutcome = Optional.of(new IdentityVerificationProvider.Outcome(
			IdentityVerificationStatus.ERROR, "PROVIDER_UNAVAILABLE"));
		assertThat(service.completeCallback(started.attemptId(), "codigo").state())
			.isEqualTo(IdentityVerificationStatus.ERROR);
		assertThat(service.isVerified(USER)).isFalse();
	}

	@Test
	void invalidTransitionIsRejected() {
		IdentityVerification notVerified = IdentityVerification.notVerified("id", USER, clock.instant());
		assertThatThrownBy(() -> notVerified.transitionTo(IdentityVerificationStatus.VERIFIED, "p", "a",
			clock.instant()))
			.isInstanceOf(ApiException.class)
			.satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_IDENTITY_TRANSITION"));
	}

	// --- dublês ---

	static class InMemoryRepo implements IdentityVerificationRepository {
		private IdentityVerification stored;
		@Override public Optional<IdentityVerification> findByUserId(String userId) {
			return stored != null && stored.userId().equals(userId) ? Optional.of(stored) : Optional.empty();
		}
		@Override public Optional<IdentityVerification> findByAttemptId(String attemptId) {
			return stored != null && attemptId != null && attemptId.equals(stored.attemptId())
				? Optional.of(stored) : Optional.empty();
		}
		@Override public void save(IdentityVerification verification) {
			this.stored = verification;
		}
		@Override public int countStartedSince(String provider, Instant since) {
			return stored != null && provider != null && provider.equals(stored.provider()) ? 1 : 0;
		}
		@Override public boolean isVerified(String userId) {
			return stored != null && stored.userId().equals(userId)
				&& stored.state().name().equals("VERIFIED");
		}
		@Override public java.util.Set<String> verifiedAmong(java.util.Collection<String> userIds) {
			return java.util.Set.of();
		}
	}

	static class FakeProvider implements IdentityVerificationProvider {
		IdentityVerificationProvider.Start startResult =
			new IdentityVerificationProvider.Start("attempt-x", "https://provedor/exemplo");
		Optional<IdentityVerificationProvider.Outcome> outcome = Optional.empty();
		Optional<IdentityVerificationProvider.Outcome> callbackOutcome = Optional.empty();
		@Override public String name() { return "fake-test-provider"; }
		@Override public Start start(String userId, String consentVersion) { return startResult; }
		@Override public Optional<Outcome> fetchOutcome(String attemptId) { return outcome; }
		@Override public Optional<Outcome> handleCallback(String attemptId, String code) { return callbackOutcome; }
	}
}
