package com.socialcoisa.rede.releasenotes.adapters.in.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.releasenotes.application.ReleaseNoteDeployService;
import com.socialcoisa.rede.releasenotes.domain.ReleaseNote;
import com.socialcoisa.rede.releasenotes.domain.ReleaseNoteItem;

/**
 * Publicação das "Novidades" pelo deploy de produção.
 *
 * O CI não tem sessão de ninguém, então esta rota não usa X-Session-Token: a credencial é o token
 * de serviço do deploy ({@code X-Deploy-Token}), conferido aqui. Fail-closed como o webhook do
 * GitHub: sem token configurado a rota responde 503 e nenhum texto entra.
 *
 * A rota está em `WriteRouteInterceptor.PUBLIC_WRITES` (escrita sem sessão, autenticada pelo
 * próprio token) e é travada por `AutomatedWritePathsTests` como as demais.
 */
@RestController
class ReleaseNoteDeployController {

	private final ReleaseNoteDeployService deploys;

	ReleaseNoteDeployController(ReleaseNoteDeployService deploys) {
		this.deploys = deploys;
	}

	record DeployPublishRequest(String commitSha, List<ReleaseNoteItem> items) {
	}

	/** `updatedAt` é o que reabre o popup — o CI loga para saber o que entrou no ar. */
	record DeployPublishResponse(boolean published, String publishedAt) {
	}

	record LastPublishedResponse(String commitSha) {
	}

	/** Base do changelog do CI: o SHA do último deploy que publicou (null se nenhum). */
	@GetMapping("/internal/novidades-deploy/ultimo")
	LastPublishedResponse lastPublished(@RequestHeader(value = "X-Deploy-Token", required = false) String deployToken) {
		deploys.requireToken(deployToken);
		return new LastPublishedResponse(deploys.lastPublishedCommitSha());
	}

	@PostMapping("/internal/novidades-deploy")
	DeployPublishResponse publish(@RequestHeader(value = "X-Deploy-Token", required = false) String deployToken,
			@RequestBody(required = false) DeployPublishRequest request) {
		deploys.requireToken(deployToken);
		ReleaseNote published = deploys.publishFromDeploy(
			request == null ? null : request.commitSha(), request == null ? null : request.items());
		return new DeployPublishResponse(true, String.valueOf(published.updatedAt()));
	}
}