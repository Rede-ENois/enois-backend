package com.socialcoisa.rede.doacoes.adapters.in.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.doacoes.application.DoacaoService;
import com.socialcoisa.rede.doacoes.domain.Doacao;
import com.socialcoisa.rede.doacoes.domain.DoacaoStatus;
import com.socialcoisa.rede.shared.security.ActionThrottle;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Doação via Pix pessoal e o selo de apoiador.
 *
 * <p>Os endpoints de doador leem a sessão e devolvem o copia e cola da conta. Pedir o código e
 * mandar comprovante têm teto por conta: o comprovante é texto livre e a conferência custa CPU.</p>
 */
@RestController
class DoacaoController {

	/** Teto de um comprovante colado (extrato inteiro de um mês fica em poucos milhares). */
	private static final int MAX_COMPROVANTE = 20_000;

	private static final java.time.Duration JANELA_PROVA = java.time.Duration.ofHours(1);

	private final DoacaoService doacoes;
	private final CurrentUserResolver currentUserResolver;
	private final com.socialcoisa.rede.shared.web.AdminGuard adminGuard;
	private final ActionThrottle throttle;

	DoacaoController(DoacaoService doacoes, CurrentUserResolver currentUserResolver,
			com.socialcoisa.rede.shared.web.AdminGuard adminGuard, ActionThrottle throttle) {
		this.doacoes = doacoes;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
		this.throttle = throttle;
	}

	/** O código vigente da conta logada: devolve 204 quando não há doação aberta. */
	@GetMapping("/doacoes/pix")
	org.springframework.http.ResponseEntity<DoacaoService.CopiaECola> current(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return doacoes.currentQr(user.id().value()).map(org.springframework.http.ResponseEntity::ok)
			.orElseGet(() -> org.springframework.http.ResponseEntity.noContent().build());
	}

	@PostMapping("/doacoes/pix")
	DoacaoService.CopiaECola open(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody OpenRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		throttle.exigePermitido("doacao-qr", user.id().value(), 10, JANELA_PROVA);
		if (request.amountCents() == null) {
			throw new ApiException(400, "DONATION_AMOUNT_REQUIRED", "Informe o valor da doação em centavos.");
		}
		return doacoes.openFor(user.id().value(), request.amountCents());
	}

	/**
	 * O doador cola o comprovante. Devolve a doação e a situação: CONFIRMADA quando o comprovante
	 * trouxe valor e E2E; um caso pendente volta com a doação ainda ABERTA e aparece na fila de
	 * conferência.
	 */
	@PostMapping("/doacoes/{id}/comprovante")
	Map<String, Object> proof(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id, @RequestBody ProofRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		throttle.exigePermitido("doacao-comprovante", user.id().value(), 20, JANELA_PROVA);
		if (request.comprovante() == null || request.comprovante().length() > MAX_COMPROVANTE) {
			throw new ApiException(400, "DONATION_PROOF_REQUIRED",
				"Cole o comprovante do Pix (até " + MAX_COMPROVANTE + " caracteres).");
		}
		var resultado = doacoes.confirmWithProof(user.id().value(), id, request.comprovante());
		return Map.of("doacao", view(resultado.doacao()), "situacao", resultado.situacao());
	}

	/** O que a tela da pessoa mostra: o selo e o histórico. */
	@GetMapping("/doacoes/minhas")
	Map<String, Object> mine(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return Map.of(
			"selo", doacoes.sealState(user.id().value()),
			"doacoes", doacoes.byUser(user.id().value()).stream().map(DoacaoController::view).toList());
	}

	// ---- conferência: quem recebe (admin) ----

	/**
	 * A fila do que precisa de conferência no extrato da conta. O resumo é público e sempre foi
	 * (contador total de doações); o nome de quem doou fica só para a administração.
	 */
	@GetMapping("/doacoes/resumo")
	Map<String, Object> summary() {
		return Map.of("confirmed", doacoes.countByStatus(DoacaoStatus.CONFIRMADA));
	}

	@GetMapping("/admin/doacoes")
	Map<String, Object> adminList(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String status,
			@RequestParam(defaultValue = "0") int offset,
			@RequestParam(defaultValue = "50") int limit) {
		requireAdmin(sessionToken);
		DoacaoStatus filtro = status == null || status.isBlank() ? DoacaoStatus.ABERTA
			: DoacaoStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
		int size = Math.max(1, Math.min(limit, 100));
		return Map.of(
			"total", doacoes.countByStatus(filtro),
			"items", doacoes.byStatus(filtro, offset, size).stream().map(DoacaoController::adminView).toList());
	}

	/** Confere o extrato da conta; {@code dryRun} mostra o que faria sem gravar. */
	@PostMapping("/admin/doacoes/conferir")
	DoacaoService.Reconcile reconcile(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody ReconcileRequest request) {
		CurrentUser user = requireAdmin(sessionToken);
		throttle.exigePermitido("doacao-conferir", user.id().value(), 20, JANELA_PROVA);
		if (request.texto() != null && request.texto().length() > MAX_COMPROVANTE) {
			throw new ApiException(400, "DONATION_STATEMENT_TOO_LONG", "Extrato grande demais para conferir de uma vez.");
		}
		return doacoes.reconcile(user.id().value(), request.texto(), Boolean.TRUE.equals(request.dryRun()));
	}

	@PostMapping("/admin/doacoes/{id}/decisao")
	Map<String, Object> decide(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id, @RequestBody DecisionRequest request) {
		CurrentUser user = requireAdmin(sessionToken);
		return Map.of("doacao", adminView(doacoes.decide(request.aprovar(), user.id().value(), id)));
	}

	private CurrentUser requireAdmin(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return user;
	}

	// ---- visões ----

	/**
	 * O que o dono vê da própria doação. O txid vai: é o que a tela mostra e o que a pessoa havia
	 * recebido; o E2E não, porque é o segredo da confirmação.
	 */
	static Map<String, Object> view(Doacao d) {
		java.util.Map<String, Object> m = new java.util.HashMap<>();
		m.put("id", d.id());
		m.put("txid", d.txid());
		m.put("amountCents", d.amountCents());
		m.put("status", d.status().name());
		m.put("createdAt", d.createdAt().toString());
		m.put("confirmedAt", d.confirmedAt() == null ? "" : d.confirmedAt().toString());
		return m;
	}

	static Map<String, Object> adminView(Doacao d) {
		java.util.Map<String, Object> m = new java.util.HashMap<>(view(d));
		m.put("userId", d.userId());
		m.put("pixIds", d.e2eIds());
		m.put("origem", d.origem() == null ? "" : d.origem().name());
		return m;
	}

	record OpenRequest(Long amountCents) {
	}

	record ProofRequest(String comprovante) {
	}

	record ReconcileRequest(String texto, Boolean dryRun) {
	}

	record DecisionRequest(Boolean aprovar) {
	}
}
