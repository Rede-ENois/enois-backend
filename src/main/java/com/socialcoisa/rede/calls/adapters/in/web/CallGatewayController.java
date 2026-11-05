package com.socialcoisa.rede.calls.adapters.in.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.calls.application.CallRoomAuthorization;
import com.socialcoisa.rede.calls.domain.CallSession;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Perguntas que o servidor de reunião (gateway do Epapo) faz antes de assinar um token de entrada.
 *
 * <p>O gateway confia na sessão de quem chama, mas esse não é o critério para entrar numa sala: a
 * pessoa também precisa ter vínculo com aquela chamada. Quem responde isso é
 * {@link CallRoomAuthorization}, que lê o banco a cada pergunta — o gateway não guarda estado de
 * sala e não decide nada sozinho.
 *
 * <p>É uma rota de servidor, sem sessão de navegador e sem {@code Origin}. A autenticação é um
 * segredo compartilhado entre os dois lados, obrigatório: sem ele configurado a rota recusa tudo
 * (fail-closed), em vez de abrir a autorização para quem chegar.
 */
@RestController
class CallGatewayController {

	private static final Logger LOG = LoggerFactory.getLogger(CallGatewayController.class);

	/**
	 * Segredo separado do segredo de assinatura, que fica só no servidor de reunião: quem conhece
	 * esta rota não consegue assinar token. O cabeçalho é lido de novo no {@code HttpRequest} do
	 * adaptador e não pode divergir daqui.
	 */
	static final String AUTHORIZE_HEADER = "X-Enois-Authorize";

	private final CallRoomAuthorization rooms;
	private final String secret;

	CallGatewayController(CallRoomAuthorization rooms,
			@Value("${rede.calls.gateway.authorize-token:}") String secret) {
		this.rooms = rooms;
		this.secret = secret == null ? "" : secret.trim();
	}

	/**
	 * Chamada dona de uma sala. O gateway chama isto para recusar cedo uma sala que não existe
	 * aqui — sem criar sala em nome de ninguém.
	 */
	@GetMapping("/calls/rooms/{room}")
	RoomView room(@RequestHeader(value = AUTHORIZE_HEADER, required = false) String presented,
			@PathVariable String room) {
		requireServer(presented);
		CallSession call = rooms.callOfRoom(room)
			.orElseThrow(() -> new ApiException(404, "CALL_ROOM_NOT_FOUND", "Sala de chamada desconhecida."));
		return new RoomView(call.id(), call.state().name());
	}

	/**
	 * Autoriza a entrada de uma pessoa numa chamada e devolve o estado atual. Negar vem como erro
	 * explícito (403/404/409); o gateway repassa à tela em vez de assinar um token sem autorização.
	 */
	@PostMapping("/calls/{callId}/gateway/entry/{userId}")
	EntryView entry(@RequestHeader(value = AUTHORIZE_HEADER, required = false) String presented,
			@PathVariable String callId, @PathVariable String userId) {
		requireServer(presented);
		CallRoomAuthorization.CallEntry entry = rooms.entry(callId, userId);
		return new EntryView(entry.callId(), entry.room(), entry.userId(), entry.state(), entry.displayName(),
			entry.moderator());
	}

	private void requireServer(String presented) {
		if (secret.isEmpty()) {
			// Sem segredo combinado não existe chamador legítimo: recusa em vez de confiar.
			throw new ApiException(503, "GATEWAY_NOT_CONFIGURED", "A ponte com o servidor de reuniao nao esta configurada.");
		}
		if (presented == null || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
				presented.trim().getBytes(StandardCharsets.UTF_8))) {
			LOG.warn("gateway-authorize rejected: shared secret missing or wrong");
			throw new ApiException(401, "GATEWAY_UNAUTHORIZED", "Credencial de servidor invalida.");
		}
	}

	record RoomView(String callId, String state) {
	}

	/**
	 * Resposta só para o gateway: sala, nome e moderação vêm do servidor daqui, e ele repassa ao
	 * gateway que assina o token. Nunca vai para o navegador.
	 */
	record EntryView(String callId, String room, String userId, String state, String displayName,
			boolean moderator) {
	}
}
