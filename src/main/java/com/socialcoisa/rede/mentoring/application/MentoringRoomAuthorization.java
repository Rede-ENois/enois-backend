package com.socialcoisa.rede.mentoring.application;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.mentoring.application.port.out.MentoringTokenGateway;
import com.socialcoisa.rede.mentoring.domain.MentorSession;
import com.socialcoisa.rede.mentoring.domain.SessionStatus;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Quem entra na sala de uma sessão de mentoria, e com que token.
 *
 * <p>É o único ponto que responde isso — tanto para a tela (que pede o token ao abrir a sala)
 * quanto para o gateway do Epapo, que pergunta antes de assinar. As regras: só as duas pessoas da
 * sessão (mentor e mentorado), e só enquanto a sessão não estiver cancelada nem concluída. Terminar
 * o horário não tira ninguém da sala: o próprio agendamento conclui a sessão depois do fim, e
 * derrubar quem está lá seria o oposto do que a mentoria promete.
 */
@Component
public class MentoringRoomAuthorization {

	private final MentorService mentoring;
	private final MentoringRooms rooms;
	private final MentoringTokenGateway tokens;
	private final UserProfileRepository profiles;

	public MentoringRoomAuthorization(MentorService mentoring, MentoringRooms rooms,
			MentoringTokenGateway tokens, UserProfileRepository profiles) {
		this.mentoring = mentoring;
		this.rooms = rooms;
		this.tokens = tokens;
		this.profiles = profiles;
	}

	/** Sala da sessão: o nome no Epapo, ou o link próprio do mentor. Nunca nulo. */
	public String roomOf(MentorSession session) {
		String stored = mentoring.roomUrlOf(session, mentoring.mentorOrNull(session.mentorId()));
		if (stored == null) {
			throw new ApiException(404, "MENTOR_ROOM_NOT_FOUND", "Sala da sessao nao encontrada.");
		}
		return stored;
	}

	/**
	 * Concede a entrada de quem pediu pelo painel: confere o vínculo e devolve o token. Sala própria
	 * do mentor é um link, não passa pelo Epapo — a resposta não leva token.
	 */
	public MentoringAccess join(String sessionId, String userId) {
		MentorSession session = mentoring.sessionForParticipant(sessionId, userId);
		requireOpen(session);
		String stored = roomOf(session);
		String room = rooms.epapoRoom(stored).orElse(null);
		if (room == null || !rooms.isEpapo(session.roomMode())) {
			// Sala própria do mentor: o link externo já é a entrada, não há token a emitir.
			return new MentoringAccess(session.id(), userId, stored, 0, null);
		}
		String token = tokens.tokenFor(session.id(), userId);
		return new MentoringAccess(session.id(), userId, rooms.roomUrl(room), tokens.tokenTtlSeconds(), token);
	}

	/**
	 * Entrada pedida pelo gateway do Epapo, que só assina o token depois desta resposta. As mesmas
	 * regras do {@link #join}, mas a negação vem como erro explícito em vez de link.
	 */
	public Entry entry(String sessionId, String userId) {
		MentorSession session = mentoring.sessionForParticipant(sessionId, userId);
		requireOpen(session);
		String stored = roomOf(session);
		String room = rooms.epapoRoom(stored)
			.orElseThrow(() -> new ApiException(409, "MENTORING_ROOM_EXTERNAL",
				"Esta sessao usa uma sala externa; o servidor de reuniao nao autoriza esta entrada."));
		boolean mentor = isMentor(session, userId);
		return new Entry(room, session.status().name(), displayName(userId), mentor);
	}

	private void requireOpen(MentorSession session) {
		if (session.status() == SessionStatus.CANCELLED) {
			throw new ApiException(409, "SESSION_CANCELLED", "Esta sessao foi cancelada.");
		}
		if (session.status() == SessionStatus.COMPLETED) {
			throw new ApiException(409, "SESSION_COMPLETED", "Esta sessao ja foi concluida.");
		}
	}

	private boolean isMentor(MentorSession session, String userId) {
		var mentor = mentoring.mentorOrNull(session.mentorId());
		return mentor != null && mentor.userId().equals(userId);
	}

	private String displayName(String userId) {
		return profiles.findById(userId).map(profile -> profile.displayName()).orElse("Alguem");
	}

	/**
	 * Entrada autorizada pela tela. {@code roomUrl} é o que abre a sala; {@code jwt} é nulo em sala
	 * própria do mentor (o link externo já é a entrada).
	 */
	public record MentoringAccess(String sessionId, String userId, String roomUrl, long tokenTtlSeconds,
			String jwt) {
	}

	/**
	 * Entrada autorizada para o gateway assinar o token: sala, nome e quem modera saem daqui, nunca
	 * de campos que o navegador mandou.
	 */
	public record Entry(String room, String state, String displayName, boolean moderator) {
	}
}
