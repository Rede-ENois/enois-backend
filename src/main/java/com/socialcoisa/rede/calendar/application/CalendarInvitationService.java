package com.socialcoisa.rede.calendar.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.application.port.out.CalendarParticipantRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarParticipant;
import com.socialcoisa.rede.calendar.domain.CalendarParticipantState;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Convites do compromisso. Só o organizador convida, reenvia e remove; a resposta é de quem foi
 * convidado, e só dela.
 *
 * <p>Convite para entrada cancelada é recusado: a entrada cancelada existe para publicar o
 * cancelamento, não para receber gente nova.
 */
@Service
public class CalendarInvitationService {

	private final CalendarEntryRepository entries;
	private final CalendarParticipantRepository participants;
	private final UserProfileRepository profiles;
	private final Clock clock;

	public CalendarInvitationService(CalendarEntryRepository entries, CalendarParticipantRepository participants,
			UserProfileRepository profiles, Clock clock) {
		this.entries = entries;
		this.participants = participants;
		this.profiles = profiles;
		this.clock = clock;
	}

	public CalendarParticipant invite(String ownerId, String entryId, String username) {
		CalendarEntry entry = ownedActive(ownerId, entryId);
		String handle = username == null ? "" : username.trim().toLowerCase();
		if (handle.isEmpty()) {
			throw new ApiException(400, "INVITE_USERNAME_REQUIRED", "Informe o @ de quem convidar.");
		}
		UserProfile profile = profiles.findByUsername(handle)
			.orElseThrow(() -> new ApiException(404, "INVITE_USER_NOT_FOUND", "Nao encontramos este perfil."));
		if (profile.id().equals(ownerId)) {
			throw new ApiException(400, "INVITE_SELF", "O organizador nao precisa de convite.");
		}
		CalendarParticipant existing = participants.findByEntryAndUser(entryId, profile.id()).orElse(null);
		if (existing != null) {
			throw new ApiException(409, "INVITE_ALREADY_EXISTS",
				"Esta pessoa ja foi convidada para este compromisso.");
		}
		CalendarParticipant participant = CalendarParticipant.invite(
			"calp_" + UUID.randomUUID().toString().replace("-", ""), entry.id(), entry.ownerId(), profile.id(),
			clock.instant());
		participants.save(participant);
		return participant;
	}

	public List<CalendarParticipant> participants(String ownerId, String entryId) {
		owned(ownerId, entryId);
		return participants.findByEntry(entryId);
	}

	/**
	 * Convites que a pessoa ainda não respondeu, de todas as agendas, do mais antigo ao mais novo.
	 * Entrada cancelada fica de fora: ela existe na linha, mas não é mais convite a responder. O
	 * banco cruza convite e compromisso numa consulta só; quem respondeu (ou o compromisso que
	 * cancelou) não volta, então não há segundo passo filtrando em memória.
	 */
	public List<CalendarEntry> pendingInvitations(String userId) {
		return entries.findPendingInvitations(userId);
	}

	/**
	 * Resposta de quem foi convidado. O convite é achado pelo compromisso e pela pessoa que está
	 * respondendo: convite de outra pessoa responde 404, porque para quem pergunta ele não existe.
	 */
	public CalendarParticipant respond(String userId, String entryId, String state) {
		CalendarEntry entry = entries.findById(entryId)
			.filter(found -> !found.ownerId().equals(userId))
			.orElseThrow(() -> new ApiException(404, "CALENDAR_INVITE_NOT_FOUND",
				"Convite nao encontrado."));
		CalendarParticipant participant = participants.findByEntryAndUser(entry.id(), userId)
			.orElseThrow(() -> new ApiException(404, "CALENDAR_INVITE_NOT_FOUND",
				"Convite nao encontrado."));
		if (!entry.active()) {
			throw new ApiException(409, "CALENDAR_ENTRY_CANCELLED",
				"Este compromisso foi cancelado.");
		}
		CalendarParticipantState response = state(state);
		try {
			participant.respond(response, clock.instant());
		}
		catch (IllegalArgumentException | IllegalStateException exception) {
			throw new ApiException(400, "INVALID_INVITE_RESPONSE", exception.getMessage());
		}
		participants.save(participant);
		return participant;
	}

	/** O organizador tira um convite. Apaga a linha: convite removido não é resposta a guardar. */
	public void remove(String ownerId, String entryId, String participantId) {
		owned(ownerId, entryId);
		CalendarParticipant participant = participants.findById(participantId)
			.filter(found -> found.entryId().equals(entryId))
			.orElseThrow(() -> new ApiException(404, "CALENDAR_INVITE_NOT_FOUND",
				"Convite nao encontrado."));
		participants.delete(participant);
	}

	private CalendarEntry owned(String ownerId, String entryId) {
		return entries.findById(entryId)
			.filter(entry -> entry.ownerId().equals(ownerId))
			.orElseThrow(() -> new ApiException(404, "CALENDAR_ENTRY_NOT_FOUND", "Compromisso nao encontrado."));
	}

	private CalendarEntry ownedActive(String ownerId, String entryId) {
		CalendarEntry entry = owned(ownerId, entryId);
		if (!entry.active()) {
			throw new ApiException(409, "CALENDAR_ENTRY_CANCELLED", "Compromisso cancelado nao recebe convite.");
		}
		return entry;
	}

	private static CalendarParticipantState state(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new ApiException(400, "INVALID_INVITE_RESPONSE",
				"Informe a resposta: ACCEPTED, DECLINED ou TENTATIVE.");
		}
		try {
			CalendarParticipantState parsed = CalendarParticipantState.parse(raw.trim());
			if (!parsed.answered()) {
				throw new ApiException(400, "INVALID_INVITE_RESPONSE",
					"A resposta precisa ser ACCEPTED, DECLINED ou TENTATIVE.");
			}
			return parsed;
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(400, "INVALID_INVITE_RESPONSE",
				"A resposta precisa ser ACCEPTED, DECLINED ou TENTATIVE.");
		}
	}
}
