package com.socialcoisa.rede.calendar.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Uma pessoa convidada para o compromisso de outra. O convite é do compromisso, não da agenda:
 * aparece para o participante junto com o item do dono, e o estado é da pessoa.
 *
 * <p>Quem organiza não tem linha aqui. Convite para o próprio dono é erro, não participação.
 */
public class CalendarParticipant {

	private final String id;
	private final String entryId;
	private final String userId;
	private final String invitedBy;
	private final Instant invitedAt;
	private CalendarParticipantState state;
	private Instant respondedAt;
	private Instant updatedAt;

	public CalendarParticipant(String id, String entryId, String userId, String invitedBy,
			CalendarParticipantState state, Instant invitedAt, Instant respondedAt, Instant updatedAt) {
		this.id = Objects.requireNonNull(id, "id");
		this.entryId = requireText(entryId, "compromisso");
		this.userId = requireText(userId, "participante");
		this.invitedBy = requireText(invitedBy, "quem convidou");
		this.invitedAt = Objects.requireNonNull(invitedAt, "convite");
		this.updatedAt = Objects.requireNonNull(updatedAt, "atualização");
		this.state = Objects.requireNonNull(state, "resposta");
		this.respondedAt = respondedAt;
	}

	/** O convite nasce pendente; a resposta vem depois, por outra pessoa. */
	public static CalendarParticipant invite(String id, String entryId, String ownerId, String userId,
			Instant now) {
		if (ownerId.equals(userId)) {
			throw new IllegalArgumentException("o organizador nao precisa de convite");
		}
		return new CalendarParticipant(id, entryId, userId, ownerId, CalendarParticipantState.INVITED, now, null,
			now);
	}

	/**
	 * Resposta do convidado. Responder duas vezes com a mesma resposta não mexe no carimbo: o botão
	 * repetido depois de um timeout não é uma resposta nova.
	 */
	public void respond(CalendarParticipantState newState, Instant now) {
		Objects.requireNonNull(newState, "resposta");
		if (!newState.answered()) {
			throw new IllegalArgumentException("resposta de convite precisa ser ACCEPTED, DECLINED ou TENTATIVE");
		}
		if (newState == state) {
			return;
		}
		state = newState;
		respondedAt = Objects.requireNonNull(now, "resposta");
		updatedAt = now;
	}

	/** O organizador reenvia o convite para quem ainda não respondeu. */
	public void remind(Instant now) {
		if (state.answered()) {
			throw new IllegalStateException("quem ja respondeu nao precisa de lembrete de convite");
		}
		updatedAt = Objects.requireNonNull(now, "atualização");
	}

	/** Quem já respondeu tem a resposta na agenda mesmo que o convite tenha sido reenviado depois. */
	public boolean answered() {
		return state.answered();
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " é obrigatório");
		}
		return value;
	}

	public String id() { return id; }

	public String entryId() { return entryId; }

	public String userId() { return userId; }

	public String invitedBy() { return invitedBy; }

	public CalendarParticipantState state() { return state; }

	public Instant invitedAt() { return invitedAt; }

	public Instant respondedAt() { return respondedAt; }

	public Instant updatedAt() { return updatedAt; }

}
