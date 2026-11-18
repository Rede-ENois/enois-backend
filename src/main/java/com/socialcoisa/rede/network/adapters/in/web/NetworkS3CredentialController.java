package com.socialcoisa.rede.network.adapters.in.web;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.S3CredentialService;
import com.socialcoisa.rede.network.domain.S3Credential;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * As Access Keys do gateway S3 pelo painel (brief §11): criar, listar e revogar, só as do próprio dono.
 *
 * <p>Diferente do ciclo de vida do Storage, esta rota é do <b>dono</b>, não do admin: a credencial
 * autoriza o dono a usar os buckets dele, e quem decide isso é ele. Ninguém administra a chave de
 * outra conta — não há parâmetro de dono em nenhuma rota.</p>
 *
 * <p>A criação responde {@code Cache-Control: no-store}: o segredo aparece uma vez, no corpo, e não
 * pode ficar em cache de proxy nem no histórico do navegador.</p>
 */
@RestController
@RequestMapping("/network/storage/s3/credentials")
class NetworkS3CredentialController {

	private final S3CredentialService service;
	private final CurrentUserResolver currentUserResolver;

	NetworkS3CredentialController(S3CredentialService service, CurrentUserResolver currentUserResolver) {
		this.service = service;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping
	List<CredentialView> list(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser owner = currentUserResolver.resolve(sessionToken);
		return service.listar(owner.id().value()).stream().map(CredentialView::of).toList();
	}

	@PostMapping
	ResponseEntity<CreatedView> create(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody(required = false) CreateRequest request) {
		CurrentUser owner = currentUserResolver.resolve(sessionToken);
		var criada = service.criar(owner.id().value(), request == null ? null : request.allowedBucket(),
			request == null ? null : request.allowedOperations());
		return ResponseEntity.ok()
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.body(CreatedView.of(criada));
	}

	@DeleteMapping("/{credentialId}")
	ResponseEntity<Void> revoke(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String credentialId) {
		CurrentUser owner = currentUserResolver.resolve(sessionToken);
		service.revogar(owner.id().value(), credentialId);
		return ResponseEntity.noContent().build();
	}

	record CreateRequest(String allowedBucket, String allowedOperations) {
	}

	/** Nunca tem o segredo: é a forma da credencial depois da criação. */
	record CredentialView(String id, String accessKey, String allowedBucket, String allowedOperations,
			Instant revokedAt, Instant createdAt) {

		static CredentialView of(S3Credential credential) {
			return new CredentialView(credential.id(), credential.accessKey(), credential.allowedBucket(),
				credential.allowedOperations(), credential.revokedAt(), credential.createdAt());
		}
	}

	/** A credencial e o segredo em claro — a única vez que ele aparece. */
	record CreatedView(CredentialView credential, String secretAccessKey) {

		static CreatedView of(S3CredentialService.Criada criada) {
			return new CreatedView(CredentialView.of(criada.credential()), criada.secret());
		}
	}
}