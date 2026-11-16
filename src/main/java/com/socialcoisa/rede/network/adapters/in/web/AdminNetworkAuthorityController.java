package com.socialcoisa.rede.network.adapters.in.web;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.NetworkAuthorityService;
import com.socialcoisa.rede.network.application.port.out.NetworkAuthorityRepository.AuthorityRecord;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Autoridade do Control Plane no painel: eleitorado declarado e termo vigente.
 *
 * <p>Fica ao lado do modo operacional, mas é outra decisão: o modo diz <b>como</b> a rede opera, o
 * termo diz <b>quem</b> é o Control Plane. Promover aqui não substitui declarar modo — uma promoção
 * não conserta um incidente, e um incidente não demite uma autoridade.</p>
 *
 * <p>Como toda rota de {@code /admin}, passa pela trava central e ainda pelo {@link AdminGuard}; a
 * promoção exige motivo, que vai para a auditoria com o termo anterior e o novo.</p>
 */
@RestController
@RequestMapping("/admin/network/authority")
class AdminNetworkAuthorityController {

	private final NetworkAuthorityService authorities;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;

	AdminNetworkAuthorityController(NetworkAuthorityService authorities, CurrentUserResolver currentUserResolver,
			AdminGuard adminGuard) {
		this.authorities = authorities;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
	}

	record AuthorityView(int term, String authorityId, Instant promotedAt, List<String> declared) {

		static AuthorityView of(AuthorityRecord record, List<String> declared) {
			return new AuthorityView(record.term(), record.authorityId(), record.promotedAt(), declared);
		}
	}

	/** Declaração humana: precisa da instância (que deve ser identificável no painel). */
	record DeclareRequest(String instanceId) {
	}

	/** Promoção humana: precisa do motivo, que vai para a auditoria. */
	record PromoteRequest(String instanceId, String reason) {
	}

	@GetMapping
	AuthorityView current(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		admin(sessionToken);
		return AuthorityView.of(authorities.current(), authorities.declared());
	}

	@PostMapping("/declared")
	AuthorityView declare(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody(required = false) DeclareRequest request) {
		CurrentUser admin = admin(sessionToken);
		if (request == null) {
			throw new ApiException(400, "NETWORK_AUTHORITY_INSTANCE_REQUIRED", "Informe a instância a declarar.");
		}
		authorities.declare(admin.id().value(), request.instanceId());
		return AuthorityView.of(authorities.current(), authorities.declared());
	}

	@DeleteMapping("/declared/{instanceId}")
	AuthorityView revoke(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String instanceId) {
		CurrentUser admin = admin(sessionToken);
		if (!authorities.revoke(admin.id().value(), instanceId)) {
			throw new ApiException(404, "NETWORK_AUTHORITY_NOT_DECLARED", "Esta instância não está no eleitorado.");
		}
		return AuthorityView.of(authorities.current(), authorities.declared());
	}

	@PostMapping("/promote")
	AuthorityView promote(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody(required = false) PromoteRequest request) {
		CurrentUser admin = admin(sessionToken);
		if (request == null || request.instanceId() == null || request.instanceId().isBlank()) {
			throw new ApiException(400, "NETWORK_AUTHORITY_INSTANCE_REQUIRED", "Informe a instância a promover.");
		}
		AuthorityRecord promoted = authorities.promote(admin.id().value(), request.instanceId(), request.reason());
		return AuthorityView.of(promoted, authorities.declared());
	}

	private CurrentUser admin(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return user;
	}
}