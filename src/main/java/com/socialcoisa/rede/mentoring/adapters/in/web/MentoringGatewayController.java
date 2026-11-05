package com.socialcoisa.rede.mentoring.adapters.in.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.mentoring.application.MentoringRoomAuthorization;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Pergunta que o servidor de reunião (gateway do Epapo) faz antes de assinar o token de uma sessão
 * de mentoria.
 *
 * <p>Mesmo contrato das chamadas: rota de servidor, sem sessão de navegador, autenticada por um
 * segredo compartilhado obrigatório. Sem o segredo configurado a rota recusa tudo (fail-closed) em
 * vez de abrir a autorização para quem chegar. Quem responde "esta pessoa entra nesta sala" é
 * {@link MentoringRoomAuthorization}, que lê o banco a cada pergunta.
 */
@RestController
class MentoringGatewayController {

	private static final Logger LOG = LoggerFactory.getLogger(MentoringGatewayController.class);

	/** O mesmo cabeçalho das chamadas: é o mesmo gateway perguntando. */
	static final String AUTHORIZE_HEADER = "X-Enois-Authorize";

	private final MentoringRoomAuthorization rooms;
	private final String secret;

	MentoringGatewayController(MentoringRoomAuthorization rooms,
			@Value("${rede.calls.gateway.authorize-token:}") String secret) {
		this.rooms = rooms;
		this.secret = secret == null ? "" : secret.trim();
	}

	/**
	 * Autoriza a entrada de uma pessoa numa sessão de mentoria e devolve a sala, o nome e quem
	 * modera. Negar vem como erro explícito; o gateway repassa à tela em vez de assinar um token
	 * sem autorização.
	 */
	@PostMapping("/mentoring/{sessionId}/gateway/entry/{userId}")
	EntryView entry(@RequestHeader(value = AUTHORIZE_HEADER, required = false) String presented,
			@PathVariable String sessionId, @PathVariable String userId) {
		requireServer(presented);
		MentoringRoomAuthorization.Entry entry = rooms.entry(sessionId, userId);
		return new EntryView(entry.room(), entry.state(), entry.displayName(), entry.moderator());
	}

	private void requireServer(String presented) {
		if (secret.isEmpty()) {
			// Sem segredo combinado não existe chamador legítimo: recusa em vez de confiar.
			throw new ApiException(503, "GATEWAY_NOT_CONFIGURED",
				"A ponte com o servidor de reuniao nao esta configurada.");
		}
		if (presented == null || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
				presented.trim().getBytes(StandardCharsets.UTF_8))) {
			LOG.warn("mentoring-gateway-authorize rejected: shared secret missing or wrong");
			throw new ApiException(401, "GATEWAY_UNAUTHORIZED", "Credencial de servidor invalida.");
		}
	}

	/**
	 * Resposta só para o gateway: sala, nome e moderação vêm do servidor daqui, e ele repassa ao
	 * gateway que assina o token. Nunca vai para o navegador.
	 */
	record EntryView(String room, String state, String displayName, boolean moderator) {
	}
}
