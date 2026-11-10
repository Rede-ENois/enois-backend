package com.socialcoisa.rede.releasenotes.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.releasenotes.application.port.out.DeployPublicationRepository;
import com.socialcoisa.rede.releasenotes.domain.ReleaseNote;
import com.socialcoisa.rede.releasenotes.domain.ReleaseNoteItem;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Publica as "Novidades" a partir do deploy de produção, sem ninguém colar texto no painel.
 *
 * O CI chama isto depois do health check com o SHA que subiu e com os itens montados do changelog
 * (commits feat/fix desde o deploy anterior). A publicação em si é a mesma do painel — a validação
 * (1 a 8 itens, 80/240 caracteres, PlainText) é a do {@link ReleaseNoteService}, então um item
 * gerado por engano é recusado do mesmo jeito que um digitado.
 *
 * Duas garantias que o disparo automático precisa e o painel não precisava:
 * - autenticação de serviço (o CI não tem sessão): token próprio, conferência em tempo constante e
 *   fail-closed quando não configurado;
 * - idempotência: o mesmo deploy não publica duas vezes (reexecução do job, retry de rede). O SHA
 *   fica gravado e um repetido responde 409 sem tocar no que está no ar.
 */
@Service
public class ReleaseNoteDeployService {

	/** Configurado no deploy por `REDE_RELEASE_NOTES_DEPLOY_TOKEN`; vazio desliga a rota. */
	private final String deployToken;
	private final ReleaseNoteService releaseNotes;
	private final DeployPublicationRepository deployments;
	private final Clock clock;

	public ReleaseNoteDeployService(
			@Value("${rede.release-notes.deploy-token:}") String deployToken, ReleaseNoteService releaseNotes,
			DeployPublicationRepository deployments, Clock clock) {
		this.deployToken = deployToken == null ? "" : deployToken.trim();
		this.releaseNotes = releaseNotes;
		this.deployments = deployments;
		this.clock = clock;
	}

	/**
	 * Fail-closed: sem token configurado a rota não existe para o CI (503). A comparação é em tempo
	 * constante para o segredo não vazar pelo tempo de resposta de tentativas erradas.
	 */
	public void requireToken(String received) {
		if (!StringUtils.hasText(deployToken)) {
			throw new ApiException(503, "DEPLOY_PUBLISH_NOT_CONFIGURED",
				"A publicação automática de novidades não está configurada.");
		}
		boolean ok = received != null && MessageDigest.isEqual(
			deployToken.getBytes(StandardCharsets.UTF_8), received.trim().getBytes(StandardCharsets.UTF_8));
		if (!ok) {
			throw new ApiException(401, "INVALID_DEPLOY_TOKEN", "Token de deploy inválido.");
		}
	}

	/** O SHA do último deploy que publicou — o CI usa como base do changelog quando começa do zero. */
	public String lastPublishedCommitSha() {
		return deployments.lastPublishedCommitSha().orElse(null);
	}

	/**
	 * Publica os itens deste deploy. SHA já publicado responde 409 e NÃO republica: o popup já foi
	 * reaberto por aquele deploy, e mexer no `updatedAt` de novo o reabriria para todo mundo.
	 */
	@Transactional
	public ReleaseNote publishFromDeploy(String commitSha, List<ReleaseNoteItem> items) {
		String sha = commitSha == null ? "" : commitSha.trim();
		if (sha.isEmpty()) {
			throw new ApiException(400, "INVALID_COMMIT_SHA", "O SHA do deploy é obrigatório.");
		}
		if (deployments.existsByCommitSha(sha)) {
			throw new ApiException(409, "DEPLOY_ALREADY_PUBLISHED",
				"Este deploy já publicou as novidades.");
		}
		// A validação/limpeza é a mesma do painel: nada de um caminho aceitar o que o outro recusa.
		var published = releaseNotes.publish(items);
		deployments.save(sha, published.items(), clock.instant());
		return published;
	}
}