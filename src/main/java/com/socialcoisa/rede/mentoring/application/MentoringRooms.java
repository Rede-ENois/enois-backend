package com.socialcoisa.rede.mentoring.application;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.mentoring.domain.Mentor;
import com.socialcoisa.rede.mentoring.domain.RoomMode;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * De onde vem a sala de uma sessão de mentoria.
 *
 * <p>A sala roda no Epapo, o mesmo servidor de reunião das chamadas, e é um nome — não um endereço.
 * O Epapo não tem "criar sala": a primeira pessoa que entra num nome cria a conferência. Por isso a
 * sala é derivada do id (uma por sessão) ou do mentor (sala fixa), e o que impede alguém de entrar
 * numa sala conhecida é o token: o gateway do Epapo só o assina depois de perguntar a esta aplicação
 * quem pode entrar (ver {@link MentoringRoomAuthorization}).
 *
 * <p>Sessão marcada antes desta troca tem gravado o link de uma instância pública do Jitsi. Esse
 * link não tem controle de acesso e não é mais a sala da sessão: na leitura, a sala dessas sessões
 * passa a ser a do Epapo, pelo mesmo nome.
 */
@Component
public class MentoringRooms {

	private final String baseUrl;
	private final String publicHost;

	public MentoringRooms(@Value("${rede.mentoring.room.base-url:}") String baseUrl,
			@Value("${rede.calls.epapo.url:}") String callHost) {
		this.baseUrl = trimTrailingSlashes(baseUrl);
		this.publicHost = trimTrailingSlashes(callHost);
	}

	/** Sala de uma sessão, pelo modo escolhido pelo mentor. */
	public String sessionRoom(Mentor mentor, String sessionId) {
		if (mentor.roomMode() == RoomMode.CUSTOM) {
			String url = mentor.meetingUrl();
			if (url == null || url.isBlank()) {
				throw new ApiException(400, "MENTOR_ROOM_REQUIRED",
					"O mentor nao tem sala configurada; escolha outra modalidade antes de receber inscricoes.");
			}
			return url.trim();
		}
		if (mentor.roomMode() == RoomMode.EPAPO_PERMANENT) {
			return permanentRoom(mentor);
		}
		return ephemeralRoom(sessionId);
	}

	/** Sala permanente do mentor: a que ele nomeou, ou uma derivada do id (estável para sempre). */
	public String permanentRoom(Mentor mentor) {
		String existing = sanitizeRoom(mentor.roomSlug());
		return existing != null ? existing : "rede-mentor-" + compact(mentor.id());
	}

	/** Sala de uma sessão: nome derivado do próprio id, único por sessão. */
	public String ephemeralRoom(String sessionId) {
		return "rede-mentoria-" + compact(sessionId);
	}

	/** true quando a sala é do Epapo (tem token e controle de entrada); false para sala própria. */
	public boolean isEpapo(RoomMode mode) {
		return mode == RoomMode.EPAPO_SESSION || mode == RoomMode.EPAPO_PERMANENT;
	}

	/**
	 * Sala do Epapo correspondente a um nome ou a um link. Sessão antiga tem o link da instância
	 * pública gravado; sessão nova tem o próprio nome. Sala própria do mentor devolve vazio: o link
	 * externo não é nosso e não passa por aqui.
	 */
	public Optional<String> epapoRoom(String stored) {
		if (stored == null || stored.isBlank()) {
			return Optional.empty();
		}
		String value = stored.trim();
		if (!value.contains("://")) {
			return Optional.of(value);
		}
		Optional<String> host = hostOf(value);
		if (host.isEmpty()) {
			return Optional.empty();
		}
		return host.get().equals("meet.jit.si") || host.get().equals(publicHost)
			? Optional.of(lastPathSegment(value))
			: Optional.empty();
	}

	/**
	 * Endereço que o navegador abre para uma sala: o Epapo publicado ou, sem ele, a base própria.
	 * {@code null} quando o ambiente ainda não tem servidor de reunião — a lista de sessões continua
	 * funcionando, só a entrada na sala é que não tem para onde ir.
	 */
	public String roomUrlOrNull(String room) {
		String base = baseUrl != null ? baseUrl : publicHost;
		if (base == null) {
			return null;
		}
		return base.contains("://") ? base + "/" + room : "https://" + base + "/" + room;
	}

	/** Endereço da sala exigindo que o ambiente tenha servidor de reunião configurado. */
	public String roomUrl(String room) {
		String url = roomUrlOrNull(room);
		if (url == null) {
			throw new ApiException(503, "MENTORING_ROOMS_NOT_CONFIGURED",
				"A sala de mentoria ainda nao esta configurada neste ambiente.");
		}
		return url;
	}

	/** Nome da sala vinda de fora (o mentor escolhe): só letras, números e traço. */
	public String sanitizeRoom(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String room = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-").replaceAll("-{2,}", "-");
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
		String cleaned = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
		return cleaned.substring(0, Math.min(64, cleaned.length()));
	}

	private static Optional<String> hostOf(String url) {
		try {
			String host = URI.create(url).getHost();
			return host == null || host.isBlank() ? Optional.empty() : Optional.of(host.toLowerCase(Locale.ROOT));
		}
		catch (IllegalArgumentException malformed) {
			return Optional.empty();
		}
	}

	private static String lastPathSegment(String url) {
		String path = url;
		int query = path.indexOf('?');
		if (query >= 0) {
			path = path.substring(0, query);
		}
		int slash = path.lastIndexOf('/');
		return slash < 0 || slash == path.length() - 1 ? "" : path.substring(slash + 1);
	}

	private static String trimTrailingSlashes(String raw) {
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed.isEmpty() ? null : trimmed;
	}

}
