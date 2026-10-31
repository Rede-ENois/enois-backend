package com.socialcoisa.rede.mentoring.application;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.mentoring.domain.Mentor;
import com.socialcoisa.rede.mentoring.domain.RoomMode;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * De onde vem a sala de uma sessão de mentoria.
 *
 * <p>O Jitsi não tem "criar sala": qualquer nome vira uma sala quando a primeira pessoa entra
 * (documentação em jitsi.org/api — "no API calls are necessary"). Por isso a sala é um link que
 * nós mesmos montamos, com nome derivado da sessão ou fixo por mentor. A base é configurável
 * (`rede.mentoring.jitsi.base-url`) para a plataforma poder apontar para uma instância própria
 * ou para o JaaS quando sair do meet.jit.si público.
 */
@Component
public class MentoringRooms {

	static final String DEFAULT_BASE_URL = "https://meet.jit.si";

	private final String baseUrl;
	private final String conferenceHost;

	public MentoringRooms(
			@Value("${rede.mentoring.jitsi.base-url:" + MentoringRooms.DEFAULT_BASE_URL + "}") String baseUrl) {
		String trimmed = baseUrl == null ? "" : baseUrl.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		this.baseUrl = trimmed.isEmpty() ? DEFAULT_BASE_URL : trimmed;
		String host = URI.create(this.baseUrl).getHost();
		this.conferenceHost = host == null || host.isBlank() ? "meet.jit.si" : host;
	}

	/** Link da sala de uma sessão, já resolvido a partir do modo escolhido pelo mentor. */
	public String sessionRoom(Mentor mentor, String sessionId) {
		if (mentor.roomMode() == RoomMode.CUSTOM) {
			String url = mentor.meetingUrl();
			if (url == null || url.isBlank()) {
				throw new ApiException(400, "MENTOR_ROOM_REQUIRED",
					"O mentor nao tem sala configurada; escolha outra modalidade antes de receber inscricoes.");
			}
			return url.trim();
		}
		if (mentor.roomMode() == RoomMode.JITSI_PERMANENT) {
			return jitsiUrl(permanentRoom(mentor));
		}
		return jitsiUrl(ephemeralRoom(sessionId));
	}

	/** Sala permanente do mentor: a que ele já criou, ou uma derivada do id (estável para sempre). */
	public String permanentRoom(Mentor mentor) {
		String existing = sanitizeRoom(mentor.jitsiRoom());
		return existing != null ? existing : "rede-mentor-" + compact(mentor.id());
	}

	/** Sala de uma sessão: nome derivado do próprio id, único por sessão. */
	public String ephemeralRoom(String sessionId) {
		return "rede-mentoria-" + compact(sessionId);
	}

	public String jitsiUrl(String room) {
		return baseUrl + "/" + room;
	}

	/** true quando a sala roda no Jitsi (tem dial-in pela API pública); false para sala própria. */
	public boolean isJitsi(RoomMode mode) {
		return mode == RoomMode.JITSI_SESSION || mode == RoomMode.JITSI_PERMANENT;
	}

	/** Nome da conferência para a API pública do Jitsi: sala@conference.<host>. */
	public String conferenceName(String roomUrl) {
		String room = roomUrl;
		int slash = roomUrl.lastIndexOf('/');
		if (slash >= 0 && slash < roomUrl.length() - 1) {
			room = roomUrl.substring(slash + 1);
		}
		return room + "@conference." + conferenceHost;
	}

	/** Sala vinda de fora (o mentor pode digitar o próprio nome): só letras, números e traço. */
	public String sanitizeRoom(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String room = raw.trim().toLowerCase().replaceAll("[^a-z0-9-]", "-").replaceAll("-{2,}", "-");
		while (room.startsWith("-")) {
			room = room.substring(1);
		}
		while (room.endsWith("-")) {
			room = room.substring(0, room.length() - 1);
		}
		if (room.length() < 3) {
			throw new ApiException(400, "INVALID_ROOM_NAME",
				"O nome da sala precisa ter pelo menos 3 caracteres (letras, numeros ou traco).");
		}
		if (room.length() > 100) {
			throw new ApiException(400, "INVALID_ROOM_NAME", "O nome da sala aceita ate 100 caracteres.");
		}
		return room;
	}

	/** Nome de sala derivado de um id interno: só caracteres seguros, em minusculas. */
	private String compact(String id) {
		if (id == null) {
			return "";
		}
		String cleaned = id.toLowerCase().replaceAll("[^a-z0-9]", "");
		return cleaned.substring(0, Math.min(64, cleaned.length()));
	}

}
