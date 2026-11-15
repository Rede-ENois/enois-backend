package com.socialcoisa.rede.network.adapters.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.NetworkJoinService;
import com.socialcoisa.rede.network.domain.NetworkJoinRequest;
import com.socialcoisa.rede.shared.security.ClientIpResolver;
import com.socialcoisa.rede.shared.security.IpRateLimiter;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Inscrição para servir um nó: o formulário público, o link de exibição da chave e a decisão
 * administrativa.
 *
 * <p>Três superfícies com regras diferentes, no mesmo fluxo:</p>
 *
 * <ul>
 * <li><b>formulário</b> ({@code POST /network/join/applications}) — anônimo, limitado por IP. É a
 * única porta pela qual uma pessoa sem conta oferece uma máquina;</li>
 * <li><b>link</b> ({@code POST /network/join/reveal}) — anônimo, porque o token é a credencial. O
 * token é de uso único e a chave é emitida aqui, não na aprovação;</li>
 * <li><b>decisão</b> ({@code /admin/network/join-requests}) — só admin. Aprovar envia o link; o
 * corpo da aprovação não devolve chave nenhuma.</li>
 * </ul>
 *
 * <p>O contrato de responsabilidade é servido por {@code GET /network/join/term} para o formulário
 * exibir o texto exato que a inscrição vai aceitar — texto e versão vêm da mesma constante, então
 * não há como o formulário mostrar um contrato e o registro guardar outro.</p>
 */
@RestController
class NetworkJoinController {

	private final NetworkJoinService joins;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;
	private final IpRateLimiter rateLimiter;
	private final ClientIpResolver clientIpResolver;

	NetworkJoinController(NetworkJoinService joins, CurrentUserResolver currentUserResolver, AdminGuard adminGuard,
			IpRateLimiter rateLimiter, ClientIpResolver clientIpResolver) {
		this.joins = joins;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
		this.rateLimiter = rateLimiter;
		this.clientIpResolver = clientIpResolver;
	}

	/** O contrato vigente e a versão que o formulário tem de devolver no aceite. */
	@GetMapping("/network/join/term")
	TermView term() {
		return new TermView(NetworkJoinService.termVersion(), NetworkJoinService.termText());
	}

	@PostMapping("/network/join/applications")
	ResponseEntity<Map<String, Object>> apply(@RequestBody ApplyRequest request, HttpServletRequest http) {
		if (!rateLimiter.tryAcquire(IpRateLimiter.NODE_JOIN, clientIpResolver.resolve(http))) {
			throw new ApiException(429, "RATE_LIMITED", "Muitas tentativas. Tente de novo mais tarde.");
		}
		NetworkJoinRequest created = joins.apply(request.fullName(), request.email(), request.rg(), request.cpf(),
			request.motivation(), request.acceptedTermVersion(), request.profiles());
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
			"id", created.id(),
			"status", created.status().name(),
			"createdAt", created.createdAt().toString(),
			"message", "Inscricao recebida. A administracao decide; a aprovacao chega por e-mail."));
	}

	/**
	 * Abre o link do e-mail: consome o uso único e devolve a chave de provisionamento <b>desta vez</b>.
	 * A segunda aberta do mesmo link responde 409, mesmo em outra aba e mesmo logo depois da primeira.
	 */
	@PostMapping("/network/join/reveal")
	RevealView reveal(@RequestBody(required = false) RevealRequest request, HttpServletRequest http) {
		if (request == null || request.token() == null) {
			throw new ApiException(400, "NODE_REVEAL_INVALID", "Link invalido ou expirado.");
		}
		if (!rateLimiter.tryAcquire(IpRateLimiter.NODE_REVEAL, clientIpResolver.resolve(http))) {
			throw new ApiException(429, "RATE_LIMITED", "Muitas tentativas. Tente de novo mais tarde.");
		}
		// A máquina declara a capacidade ao consumir; quando a tela só abre o link (sem corpo de
		// declaração), vale o que o enroll pediria depois. Um Integer nulo aqui derrubava a
		// requisição com 500 em vez de responder o 409 de link já usado.
		NetworkJoinService.EnrolledForReveal revealed = joins.consumeReveal(request.token(), request.nodeName(),
			request.region(), orDefault(request.cpuCores(), 2), orDefault(request.memoryMb(), 2048),
			orDefault(request.diskMb(), 20480), orDefault(request.bandwidthMbps(), 100));
		return RevealView.of(revealed);
	}

	@GetMapping("/admin/network/join-requests")
	List<AdminJoinView> list(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) NetworkJoinRequest.Status status,
			@RequestParam(defaultValue = "100") int limit) {
		admin(sessionToken);
		return joins.list(status, limit).stream().map(AdminJoinView::listView).toList();
	}

	/**
	 * Detalhe administrativo: aqui RG e CPF aparecem inteiros, porque a decisão de identificar a
	 * pessoa depende deles. A listagem mostra mascarado; nenhuma rota pública os devolve.
	 */
	@GetMapping("/admin/network/join-requests/{id}")
	AdminJoinView detail(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id) {
		admin(sessionToken);
		return AdminJoinView.detailView(joins.byId(id));
	}

	/** Aprovar envia o link; recusar só registra o motivo. Nenhuma das duas devolve chave. */
	@PostMapping("/admin/network/join-requests/{id}/decision")
	DecisionView decide(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id, @RequestBody DecisionRequest request) {
		CurrentUser admin = admin(sessionToken);
		NetworkJoinRequest.Status decision = parseDecision(request.decision());
		var reveal = joins.decide(id, decision, admin.id().value(), request.note(), request.profiles());
		return reveal
			.map(token -> new DecisionView(id, decision.name(), true, token.expiresAt().toString(),
				"Link de exibicao enviado para " + maskEmail(token.email()) + "."))
			.orElseGet(() -> new DecisionView(id, decision.name(), false, null, "Inscricao recusada."));
	}

	/** Renova o link de inscrição aprovada que ainda não exibiu a chave. Depois do uso, 409. */
	@PostMapping("/admin/network/join-requests/{id}/reveal-link")
	DecisionView rotate(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id) {
		CurrentUser admin = admin(sessionToken);
		var token = joins.rotateReveal(id, admin.id().value());
		return new DecisionView(id, "APPROVED", true, token.expiresAt().toString(),
			"Novo link enviado para " + maskEmail(token.email()) + ". O link anterior deixou de valer.");
	}

	// ------------------------------------------------------------------ views

	record TermView(String version, String text) {
	}

	/**
	 * A inscrição como a administração vê. {@code rg}/{@code cpf} só vêm no detalhe; a listagem
	 * devolve os mascarados e o campo inteiro fica {@code null} — assim um cliente que só lista não
	 * tem como guardar o dado sensível de terceiros sem pedir o detalhe.
	 */
	record AdminJoinView(String id, String fullName, String email, String rg, String cpf, String maskedRg,
			String maskedCpf, String motivation, String termVersion, String status, String profiles,
			String decidedBy, Instant decidedAt, String decisionNote, Instant revealExpiresAt,
			Instant revealConsumedAt, boolean revealPending, Instant emailSentAt, Instant createdAt) {

		static AdminJoinView listView(NetworkJoinRequest r) {
			return new AdminJoinView(r.id(), r.fullName(), r.email(), null, null, r.maskedRg(), r.maskedCpf(),
				r.motivation(), r.termVersion(), r.status().name(), r.profiles(), r.decidedBy(), r.decidedAt(),
				r.decisionNote(), r.revealExpiresAt(), r.revealConsumedAt(), revealPending(r), r.emailSentAt(),
				r.createdAt());
		}

		static AdminJoinView detailView(NetworkJoinRequest r) {
			return new AdminJoinView(r.id(), r.fullName(), r.email(), r.rg(), r.cpf(), r.maskedRg(),
				r.maskedCpf(), r.motivation(), r.termVersion(), r.status().name(), r.profiles(), r.decidedBy(),
				r.decidedAt(), r.decisionNote(), r.revealExpiresAt(), r.revealConsumedAt(), revealPending(r),
				r.emailSentAt(), r.createdAt());
		}

		private static boolean revealPending(NetworkJoinRequest r) {
			return r.status() == NetworkJoinRequest.Status.APPROVED && r.revealConsumedAt() == null;
		}
	}

	/** E-mail mascarado na resposta da decisão: o admin sabe para quem foi sem o log carregar o endereço. */
	static String maskEmail(String email) {
		if (email == null || !email.contains("@")) {
			return "***";
		}
		int at = email.indexOf('@');
		String local = email.substring(0, at);
		return (local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2)) + "***" + email.substring(at);
	}

	// ------------------------------------------------------------------ corpo

	record ApplyRequest(String fullName, String email, String rg, String cpf, String motivation,
			String acceptedTermVersion, List<String> profiles) {
	}

	record RevealRequest(String token, String nodeName, String region, Integer cpuCores, Integer memoryMb,
			Integer diskMb, Integer bandwidthMbps) {
	}

	record DecisionRequest(String decision, String note, List<String> profiles) {
	}

	record DecisionView(String requestId, String status, boolean linkSent, String revealExpiresAt,
			String message) {
	}

	/**
	 * A chave exibida uma única vez. {@code provisionToken} é o que o instalador do nó consome
	 * ({@code --set-key} / enroll); a tela avisa que não há segunda exibição.
	 */
	record RevealView(String provisionToken, String nodeName, String profiles, String ownerName, String email,
			boolean hasAccount, String expiresAt, String notice) {

		static RevealView of(NetworkJoinService.EnrolledForReveal r) {
			return new RevealView(r.provisionToken(), r.nodeName(), r.profiles(), r.accountName(), r.email(),
				!r.ownerUserId().startsWith("system"), null,
				"Guarde esta chave agora: ela nao sera exibida de novo. Se perde-la, sera preciso uma nova inscricao.");
		}
	}

	private CurrentUser admin(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return user;
	}

	private static int orDefault(Integer value, int fallback) {
		return value == null ? fallback : value;
	}

	private static NetworkJoinRequest.Status parseDecision(String raw) {
		if ("APPROVE".equalsIgnoreCase(raw)) {
			return NetworkJoinRequest.Status.APPROVED;
		}
		if ("REJECT".equalsIgnoreCase(raw)) {
			return NetworkJoinRequest.Status.REJECTED;
		}
		throw new ApiException(400, "NODE_JOIN_DECISION_INVALID", "Decisao deve ser APPROVE ou REJECT.");
	}

}