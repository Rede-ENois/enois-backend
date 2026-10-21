package com.socialcoisa.rede.events.application;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.events.application.port.out.EventRepository;
import com.socialcoisa.rede.events.domain.Event;
import com.socialcoisa.rede.events.domain.EventAttendee;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class EventService {

	/** Folga antes de apagar as presencas: da tempo de quem clicou no evento no fim do dia. */
	private static final java.time.Duration SANITIZE_GRACE = java.time.Duration.ofHours(24);

	/** Depois de sanitizado, o evento ainda fica visivel por este tempo antes do expurgo final. */
	private static final java.time.Duration RETENTION = java.time.Duration.ofDays(365);

	private final EventRepository repository;
	private final com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate;
	private final com.socialcoisa.rede.shared.security.SuspiciousText suspiciousText;
	private final Clock clock;

	public EventService(EventRepository repository,
			com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate,
			com.socialcoisa.rede.shared.security.SuspiciousText suspiciousText, Clock clock) {
		this.repository = repository;
		this.sanctionGate = sanctionGate;
		this.suspiciousText = suspiciousText;
		this.clock = clock;
	}

	@Transactional
	public Event create(String title, String date, String time, String format, String host, String cover,
			String streamUrl, String location, String userId) {
		String id = "event_" + UUID.randomUUID().toString().replace("-", "");
		Event event = Event.create(id, title, date, time, format, host, cover, httpsOrNull(streamUrl), location,
			userId, clock.instant());
		requireRealisticSchedule(event);
		repository.save(event);
		return event;
	}

	/**
	 * A data precisa ser legivel e cair numa janela que existe na vida real.
	 *
	 * Sem isto o banco aceitava "220122-12-12": o evento entrava como valido, ficava no topo da
	 * lista para sempre (a ordem e por data) e a faxina nunca o alcancava, porque ele "ainda nao
	 * aconteceu". A janela e generosa de proposito — dois anos para tras (registro do que passou) e
	 * cinco para frente (planejamento de verdade) —, e o que ela barra e o que nao e data.
	 */
	private void requireRealisticSchedule(Event event) {
		if (event.expiresAt().isEmpty()) {
			throw new ApiException(400, "INVALID_EVENT_DATE",
				"Data do evento invalida. Use AAAA-MM-DD ou DD/MM/AAAA.");
		}
		if (event.hasAbsurdSchedule(clock.instant())) {
			throw new ApiException(400, "INVALID_EVENT_DATE",
				"Data fora do periodo aceito: no maximo 5 anos para frente e 2 anos para tras.");
		}
	}

	/** Apaga o evento. So o dono ou um administrador — e a faxina de presencas vai junto. */
	@Transactional
	public void delete(String eventId, String userId, boolean admin) {
		Event event = repository.findById(eventId)
			.orElseThrow(() -> new ApiException(404, "EVENT_NOT_FOUND", "Evento nao encontrado."));
		if (!admin && !event.createdBy().equals(userId)) {
			throw new ApiException(403, "NOT_EVENT_OWNER", "Este evento nao e seu.");
		}
		repository.deleteAttendees(eventId);
		repository.deleteById(eventId);
	}

	/**
	 * Link de transmissao so entra por https.
	 *
	 * O campo e texto livre e volta no evento como link: sem esta checagem, um `javascript:` gravado
	 * aqui sairia como link clicavel para quem abre a lista.
	 */
	private String httpsOrNull(String url) {
		if (!StringUtils.hasText(url)) {
			return null;
		}
		String trimmed = url.trim();
		if (!trimmed.toLowerCase(Locale.ROOT).startsWith("https://")) {
			throw new ApiException(400, "INVALID_STREAM_URL", "O link da transmissao precisa comecar com https://.");
		}
		return trimmed;
	}

	@Transactional
	public void attend(String eventId, String userId) {
		Event event = repository.findById(eventId)
			.orElseThrow(() -> new ApiException(404, "EVENT_NOT_FOUND", "Evento nao encontrado."));
		if (hasEnded(event)) {
			throw new ApiException(400, "EVENT_FINISHED", "Este evento ja aconteceu.");
		}
		if (repository.isAttending(eventId, userId)) {
			throw new ApiException(409, "ALREADY_ATTENDING", "Voce ja confirmou presenca.");
		}
		repository.saveAttendee(EventAttendee.attend(eventId, userId, clock.instant()));
	}

	@Transactional
	public void cancelAttendance(String eventId, String userId) {
		if (!repository.isAttending(eventId, userId)) {
			throw new ApiException(404, "NOT_ATTENDING", "Voce nao esta inscrito neste evento.");
		}
		repository.removeAttendee(eventId, userId);
	}

	/**
	 * Lista eventos. Por padrao so os que ainda vao acontecer (ou estao acontecendo): evento vencido
	 * sai da vitrine. `includePast` existe para historico/administracao.
	 *
	 * O cursor sai da pagina que veio do banco: filtrar depois de paginar sem isso faria a lista
	 * achar que acabou.
	 */
	public EventPage list(String format, java.time.Instant before, int limit, boolean includePast) {
		List<Event> fetched = repository.findAll(format, before, limit);
		List<Event> visible = includePast ? fetched : fetched.stream().filter(event -> !hasEnded(event)).toList();
		// Evento de conta banida sai da vitrine (o conteúdo dela já está sendo limpo).
		visible = visible.stream()
			.filter(event -> !sanctionGate.banned(event.createdBy()))
			.filter(event -> !suspiciousText.matches(event.title(), event.host()))
			.toList();
		return new EventPage(visible, fetched);
	}

	/** O evento ja terminou (usado na listagem e no selo "encerrado"). */
	public boolean hasEnded(Event event) {
		return event.isExpired(clock.instant(), java.time.Duration.ZERO);
	}

	/** Pode apagar as presencas: terminou e ja passou a folga. */
	public boolean isReadyForSanitization(Event event) {
		return event.isExpired(clock.instant(), SANITIZE_GRACE);
	}

	/**
	 * Sanitiza os eventos vencidos: guarda o total de presencas, apaga o vinculo com cada pessoa e
	 * marca o evento. Idempotente — rodar de novo nao muda nada.
	 *
	 * Apos a retencao, o evento sanitizado e apagado por inteiro (o historico ja foi agregado).
	 *
	 * @return quantos eventos foram sanitizados e quantos foram expurgados
	 */
	@Transactional
	public SanitizationResult sanitizeExpired() {
		java.time.Instant now = clock.instant();
		int sanitized = 0;
		for (Event event : repository.findNotSanitized()) {
			if (!isReadyForSanitization(event)) {
				continue;
			}
			int attendees = repository.countAttendees(event.id());
			// A contribuição de quem foi não pode evaporar junto com a PII: antes de apagar o
			// vínculo com cada pessoa, a CONTAGEM por usuário é preservada (sem dado pessoal).
			for (String userId : repository.findAttendeeIds(event.id())) {
				repository.addAttendanceToTotal(userId);
			}
			repository.deleteAttendees(event.id());
			event.markSanitized(attendees, now);
			repository.save(event);
			sanitized++;
		}

		int purged = 0;
		for (Event event : repository.findSanitizedBefore(now.minus(RETENTION))) {
			repository.deleteAttendees(event.id());
			repository.deleteById(event.id());
			purged++;
		}
		return new SanitizationResult(sanitized, purged);
	}

	public record SanitizationResult(int sanitized, int purged) {
	}

	/** Itens visiveis + a pagina como veio do banco (o cursor sai dela, nao do filtro). */
	public record EventPage(List<Event> items, List<Event> fetched) {
	}

	public Event getById(String id) {
		return repository.findById(id)
			.orElseThrow(() -> new ApiException(404, "EVENT_NOT_FOUND", "Evento nao encontrado."));
	}

	public int countAttendees(String eventId) {
		return repository.countAttendees(eventId);
	}

	/** Events among {@code eventIds} where the user confirmed attendance (one query). */
	public java.util.Set<String> attendingEventIds(String userId, java.util.List<String> eventIds) {
		return repository.findAttendingEventIds(userId, eventIds);
	}

}
