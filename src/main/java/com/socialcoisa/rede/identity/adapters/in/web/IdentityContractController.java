package com.socialcoisa.rede.identity.adapters.in.web;

import java.util.LinkedHashMap;
import java.util.Map;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.util.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.identity.application.IdentityReleaseService;
import com.socialcoisa.rede.identity.application.IdentityVerificationService;
import com.socialcoisa.rede.identity.domain.IdentityVerification;
import com.socialcoisa.rede.jobs.application.JobPublishingPolicy;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Verificacao de identidade civil e elegibilidade para publicar vagas. O estado so muda por retorno
 * do provedor; nao existe rota que o front use para se declarar VERIFIED.
 */
@RestController
@RequestMapping("/identity")
class IdentityContractController {

	private final IdentityVerificationService identityService;
	private final JobPublishingPolicy jobPublishingPolicy;
	private final CurrentUserResolver currentUserResolver;
	private final IdempotencyApplicationService idempotency;
	private final IdentityReleaseService release;

	IdentityContractController(IdentityVerificationService identityService, JobPublishingPolicy jobPublishingPolicy,
			CurrentUserResolver currentUserResolver, IdempotencyApplicationService idempotency,
			IdentityReleaseService release) {
		this.identityService = identityService;
		this.jobPublishingPolicy = jobPublishingPolicy;
		this.currentUserResolver = currentUserResolver;
		this.idempotency = idempotency;
		this.release = release;
	}

	@GetMapping
	IdentityStatusView status(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return view(identityService.current(user.id().value()));
	}

	@PostMapping("/verification")
	ResponseEntity<String> start(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody(required = false) StartVerificationRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String consent = request == null ? null : request.consentVersion();
		Map<String, Object> payload = Map.of("consent", consent == null ? "" : consent);
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "POST", "/api/identity/verification", idempotencyKey, hash, () -> {
			var started = identityService.start(user.id().value(), consent);
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("attemptId", started.attemptId());
			body.put("redirectUrl", started.redirectUrl());
			return new CommandResponse(200, body);
		});
	}

	@PostMapping("/verification/refresh")
	IdentityStatusView refresh(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return view(identityService.refresh(user.id().value()));
	}

	@DeleteMapping("/verification")
	ResponseEntity<String> revoke(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("op", "revoke-identity"));
		return idempotency.execute(user.id(), "DELETE", "/api/identity/verification", idempotencyKey, hash,
			() -> new CommandResponse(200, view(identityService.revoke(user.id().value()))));
	}

	/** Elegibilidade para publicar vaga, com o que falta — a decisao final e sempre do backend. */
	@GetMapping("/eligibility")
	JobPublishingPolicy.Decision eligibility(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return jobPublishingPolicy.evaluate(user.id().value());
	}

	private IdentityStatusView view(IdentityVerification verification) {
		// A liberacao progressiva e do provedor pago; sem convite, o app mostra a mensagem de
		// "esta chegando" e nunca um erro. Nao expoe nota de credibilidade nem posicao.
		IdentityReleaseService.Eligibility eligibility = release.eligibility(verification.userId());
		return new IdentityStatusView(verification.state().name(), verification.state().isVerified(),
			verification.state().isVerified() || identityService.providerAvailable(), verification.provider(),
			verification.reasonCode(),
			verification.completedAt() != null ? verification.completedAt().toString() : null,
			eligibility.released(), eligibility.status());
	}

	/**
	 * Retorno do provedor (gov.br) — PUBLICO de proposito: o navegador chega aqui redirecionado,
	 * sem a nossa sessao. A tentativa e identificada pelo `state` de uso unico (nao pelo cliente).
	 */
	@GetMapping("/govbr/callback")
	ResponseEntity<Void> govbrCallback(@RequestParam(required = false) String code,
			@RequestParam(required = false) String state,
			@Value("${rede.frontend.url:}") String frontendUrl) {
		String outcome = "ok";
		try {
			identityService.completeCallback(state, code);
		}
		catch (RuntimeException failure) {
			outcome = "erro";
		}
		if (StringUtils.hasText(frontendUrl)) {
			String base = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
			return ResponseEntity.status(302).location(URI.create(base + "/seguranca?identidade=" + outcome)).build();
		}
		return ResponseEntity.ok().build();
	}

	record IdentityStatusView(String state, boolean verified, boolean providerAvailable, String provider,
			String reasonCode, String completedAt, boolean released, String releaseStatus) {
	}

	record StartVerificationRequest(String consentVersion) {
	}
}
