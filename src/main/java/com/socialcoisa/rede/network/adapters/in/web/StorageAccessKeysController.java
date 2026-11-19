package com.socialcoisa.rede.network.adapters.in.web;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.StorageAccessKeyService;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Access Keys do gateway S3 pelo painel — o dono cria e revoga as próprias credenciais sem passar
 * pelo banco.
 *
 * <p>Não é rota de admin: a credencial é do <b>dono logado</b>, e um admin criando credencial de
 * outra conta seria emprestar a própria sessão para agir em nome de terceiro. A fronteira administrativa
 * fica onde já está — em política de bucket, retenção e expiração —, não em quem pode usar os buckets
 * que são seus.</p>
 */
@RestController
@RequestMapping("/network/storage/access-keys")
class StorageAccessKeysController {

	private final StorageAccessKeyService keys;
	private final CurrentUserResolver currentUserResolver;

	StorageAccessKeysController(StorageAccessKeyService keys, CurrentUserResolver currentUserResolver) {
		this.keys = keys;
		this.currentUserResolver = currentUserResolver;
	}

	/** A criação devolve o segredo <b>uma única vez</b>; ele não é recuperável depois. */
	@PostMapping
	StorageAccessKeyService.Created create(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody(required = false) CreateRequest request) {
		CurrentUser dono = currentUserResolver.resolve(sessionToken);
		CreateRequest pedido = request == null ? new CreateRequest(null, null) : request;
		return keys.create(dono.id().value(), pedido.bucket(), pedido.operations());
	}

	@GetMapping
	List<StorageAccessKeyService.View> list(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		return keys.list(currentUserResolver.resolve(sessionToken).id().value());
	}

	@DeleteMapping("/{id}")
	StorageAccessKeyService.View revoke(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String id) {
		return keys.revoke(currentUserResolver.resolve(sessionToken).id().value(), id);
	}

	record CreateRequest(String bucket, String operations) {
	}
}