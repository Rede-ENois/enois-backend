package com.socialcoisa.rede.calls.adapters.in.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.calls.application.CallService;
import com.socialcoisa.rede.calls.domain.CallKind;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Chamadas. Todas as rotas exigem sessão, e cada uma confere o vínculo (conversa/grupo) no
 * servidor: o cliente não decide quem entra nem quem pode encerrar.
 *
 * <p>O token de entrada na sala sai só em {@code POST /calls/{id}/join} e é curto — o nome da sala
 * e o segredo de assinatura nunca aparecem nas outras respostas.
 */
@RestController
class CallController {

	private final CallService calls;
	private final CurrentUserResolver currentUserResolver;

	CallController(CallService calls, CurrentUserResolver currentUserResolver) {
		this.calls = calls;
		this.currentUserResolver = currentUserResolver;
	}

	/** Começa uma chamada 1:1 na conversa (áudio ou vídeo). */
	@PostMapping("/conversations/{conversationId}/calls")
	CallService.CallView startDirect(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String conversationId, @RequestBody(required = false) StartRequest request) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		return calls.startDirect(conversationId, userId, kindOf(request));
	}

	/** Começa (ou entra n)a chamada "Tamo junto" do grupo. */
	@PostMapping("/groups/{groupId}/calls")
	CallService.CallView startGroup(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String groupId, @RequestBody(required = false) StartRequest request) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		return calls.startGroup(groupId, userId, kindOf(request));
	}

	/** Chamada aberta do vínculo — é o que a conversa/grupo consulta para mostrar "ao vivo". */
	@GetMapping("/conversations/{conversationId}/calls/open")
	CallService.CallView openOfConversation(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String conversationId) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		return calls.openOf(conversationId, null, userId).orElse(null);
	}

	@GetMapping("/groups/{groupId}/calls/open")
	CallService.CallView openOfGroup(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String groupId) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		return calls.openOf(null, groupId, userId).orElse(null);
	}

	/** Tudo que está aberto para esta pessoa (voltar de outra tela sem perder a chamada). */
	@GetMapping("/calls/open")
	List<CallService.CallView> openFor(@RequestHeader("X-Session-Token") String sessionToken) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		return calls.openFor(userId);
	}

	@GetMapping("/calls/{callId}")
	CallService.CallView get(@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String callId) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		return calls.get(callId, userId);
	}

	/** Atender (só 1:1). Idempotente: repetir devolve o mesmo estado. */
	@PostMapping("/calls/{callId}/answer")
	CallService.CallView answer(@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String callId) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		return calls.answer(callId, userId);
	}

	@PostMapping("/calls/{callId}/decline")
	CallService.CallView decline(@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String callId) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		return calls.decline(callId, userId);
	}

	@PostMapping("/calls/{callId}/cancel")
	CallService.CallView cancel(@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String callId) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		return calls.cancel(callId, userId);
	}

	/**
	 * Entrar na sala: devolve host, sala, AppID público, token assinado e validade. O token só vale
	 * para esta chamada e expira sozinho; nada aqui pode ser usado para entrar em outra sala.
	 */
	@PostMapping("/calls/{callId}/join")
	CallService.JoinView join(@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String callId) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		return calls.join(callId, userId);
	}

	/** Sair da sala. No grupo, a chamada continua para os outros; no 1:1, encerra. */
	@PostMapping("/calls/{callId}/leave")
	CallService.CallView leave(@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String callId) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		return calls.leave(callId, userId);
	}

	@PostMapping("/calls/{callId}/end")
	CallService.CallView end(@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String callId) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		return calls.end(callId, userId);
	}

	/** Falha de conexão declarada pelo cliente (a tela perdeu a mídia): fecha em vez de pendurar. */
	@PostMapping("/calls/{callId}/fail")
	CallService.CallView fail(@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String callId) {
		String userId = currentUserResolver.resolve(sessionToken).id().value();
		return calls.fail(callId, userId);
	}

	private static CallKind kindOf(StartRequest request) {
		String kind = request == null ? null : request.kind();
		return kind == null || kind.isBlank() ? CallKind.AUDIO : CallKind.parse(kind);
	}

	record StartRequest(String kind) {
	}
}
