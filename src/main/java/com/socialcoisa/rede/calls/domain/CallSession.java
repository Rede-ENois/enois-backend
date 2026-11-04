package com.socialcoisa.rede.calls.domain;

import java.time.Duration;
import java.time.Instant;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Uma chamada de áudio/vídeo. Aggregate root: toda transição passa por aqui e o serviço só
 * persiste o que o domínio já aceitou.
 *
 * <p>O identificador da sala é gerado pelo servidor e nunca contém o @ nem o nome de ninguém:
 * adivinhar o apelido de uma pessoa não pode dar acesso à conversa dela. Quem entra recebe um
 * token assinado para ESTA sala, e o servidor confere o vínculo (conversa/grupo) a cada entrada.
 */
public class CallSession {

	/** Quanto tempo o 1:1 toca antes de virar "não atendida". */
	public static final Duration RING_TIMEOUT = Duration.ofSeconds(40);
	/** Chamada em grupo sem ninguém dentro por esse tempo é fechada ("ao vivo" acaba sozinho). */
	public static final Duration EMPTY_GROUP_TIMEOUT = Duration.ofMinutes(5);
	/** Tempo sem nenhum sinal de vida depois do qual a sessão é dada como abandonada. */
	public static final Duration ABANDON_TIMEOUT = Duration.ofMinutes(30);

	private final String id;
	private final CallKind kind;
	private final CallModality modality;
	private final String conversationId;
	private final String groupId;
	private final String initiatorId;
	private final String roomId;
	private final String displayName;
	private CallState state;
	private final Instant createdAt;
	private Instant answeredAt;
	private Instant endedAt;
	private String endedBy;
	private String endReason;
	private Instant expiresAt;
	private Instant lastActivityAt;

	public CallSession(String id, CallKind kind, CallModality modality, String conversationId, String groupId,
			String initiatorId, String roomId, String displayName, CallState state, Instant createdAt,
			Instant answeredAt, Instant endedAt, String endedBy, String endReason, Instant expiresAt,
			Instant lastActivityAt) {
		this.id = id;
		this.kind = kind;
		this.modality = modality;
		this.conversationId = conversationId;
		this.groupId = groupId;
		this.initiatorId = initiatorId;
		this.roomId = roomId;
		this.displayName = displayName;
		this.state = state;
		this.createdAt = createdAt;
		this.answeredAt = answeredAt;
		this.endedAt = endedAt;
		this.endedBy = endedBy;
		this.endReason = endReason;
		this.expiresAt = expiresAt;
		this.lastActivityAt = lastActivityAt;
	}

	public static CallSession start(String id, CallKind kind, CallModality modality, String conversationId,
			String groupId, String initiatorId, String roomId, String displayName, Instant now) {
		CallState initial = modality == CallModality.DIRECT ? CallState.RINGING : CallState.CONNECTING;
		Instant expiresAt = modality == CallModality.DIRECT ? now.plus(RING_TIMEOUT)
			: now.plus(EMPTY_GROUP_TIMEOUT);
		return new CallSession(id, kind, modality, conversationId, groupId, initiatorId, roomId, displayName,
			initial, now, null, null, null, null, expiresAt, now);
	}

	/** Muda o estado só se a máquina permitir; senão responde com o conflito em vez de fingir. */
	public void moveTo(CallState next, Instant now, String reason) {
		if (!state.canMoveTo(next)) {
			throw new ApiException(409, "CALL_STATE_CONFLICT",
				"A chamada já está em outro estado e não pode ser " + describe(next) + ".");
		}
		if (next == CallState.DECLINED || next == CallState.MISSED || next == CallState.CANCELLED) {
			this.endedAt = now;
			this.endReason = reason == null ? next.name() : reason;
		}
		if (next == CallState.CONNECTING && this.answeredAt == null) {
			this.answeredAt = now;
		}
		if (next == CallState.ENDED || next == CallState.FAILED) {
			this.endedAt = now;
			this.endReason = reason;
		}
		this.state = next;
		this.lastActivityAt = now;
	}

	/** Fecha a chamada por decisão de alguém (encerrar, cancelar ou recusar). */
	public void closeBy(String actorId, CallState next, Instant now, String reason) {
		moveTo(next, now, reason);
		this.endedBy = actorId;
	}

	/**
	 * Expiração: só age quando o prazo venceu e a chamada ainda está aberta. Idempotente — chamar
	 * de novo depois de vencida devolve falso e não muda nada (o worker pode repetir à vontade).
	 */
	public boolean expire(Instant now, String reason) {
		if (!state.isOpen() || expiresAt == null || now.isBefore(expiresAt)) {
			return false;
		}
		CallState next = switch (state) {
			case INITIATING, RINGING -> CallState.MISSED;
			case CONNECTING -> CallState.FAILED;
			case ACTIVE -> CallState.ENDED;
			default -> null;
		};
		if (next == null || !state.canMoveTo(next)) {
			return false;
		}
		this.state = next;
		this.endedAt = now;
		this.endReason = reason;
		this.lastActivityAt = now;
		return true;
	}

	/**
	 * Adia o prazo da chamada (ao atender, existe um novo motivo para esperar: o outro lado entrar).
	 */
	public void extendTo(Instant now, Duration window) {
		this.expiresAt = now.plus(window);
		this.lastActivityAt = now;
	}

	/** Marca movimento (entrada, saída, sinal de vida) e empurra o prazo de abandono. */
	public void touch(Instant now, Duration window) {
		this.lastActivityAt = now;
		this.expiresAt = now.plus(window);
	}

	public boolean isAbandoned(Instant now) {
		return state.isOpen() && lastActivityAt.plus(ABANDON_TIMEOUT).isBefore(now);
	}

	public String id() {
		return id;
	}

	public CallKind kind() {
		return kind;
	}

	public CallModality modality() {
		return modality;
	}

	public String conversationId() {
		return conversationId;
	}

	public String groupId() {
		return groupId;
	}

	public String initiatorId() {
		return initiatorId;
	}

	public String roomId() {
		return roomId;
	}

	public String displayName() {
		return displayName;
	}

	public CallState state() {
		return state;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant answeredAt() {
		return answeredAt;
	}

	public Instant endedAt() {
		return endedAt;
	}

	public String endedBy() {
		return endedBy;
	}

	public String endReason() {
		return endReason;
	}

	public Instant expiresAt() {
		return expiresAt;
	}

	public Instant lastActivityAt() {
		return lastActivityAt;
	}

	private static String describe(CallState state) {
		return switch (state) {
			case CONNECTING -> "atendida";
			case ACTIVE -> "colocada em andamento";
			case DECLINED -> "recusada";
			case MISSED -> "marcada como não atendida";
			case CANCELLED -> "cancelada";
			case ENDED -> "encerrada";
			case FAILED -> "marcada como falha";
			case RINGING -> "colocada para tocar";
			case INITIATING -> "iniciada";
		};
	}
}
