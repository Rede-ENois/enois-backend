package com.socialcoisa.rede.events.application;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.events.application.port.out.EventRepository;
import com.socialcoisa.rede.events.domain.Event;
import com.socialcoisa.rede.events.domain.EventAttendee;
import com.socialcoisa.rede.events.domain.EventRecurrence;
import com.socialcoisa.rede.shared.application.CacheConfig;
import com.socialcoisa.rede.shared.application.CacheInvalidator;
import com.socialcoisa.rede.shared.geo.CityState;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.TextFieldLimit;

@Service
public class EventService {

	private static final Logger LOG = LoggerFactory.getLogger(EventService.class);

	/** Folga antes de apagar as presencas: da tempo de quem clicou no evento no fim do dia. */
	private static final java.time.Duration SANITIZE_GRACE = java.time.Duration.ofHours(24);

	/** Depois de sanitizado, o evento ainda fica visivel por este tempo antes do expurgo final. */
	private static final java.time.Duration RETENTION = java.time.Duration.ofDays(365);

	private final EventRepository repository;
	private final com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate;
	private final com.socialcoisa.rede.shared.security.SuspiciousText suspiciousText;
	private final Clock clock;
	private final CacheInvalidator cacheInvalidator;

	public EventService(EventRepository repository,
			com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate,
			com.socialcoisa.rede.shared.security.SuspiciousText suspiciousText, Clock clock,
			CacheInvalidator cacheInvalidator) {
		this.repository = repository;
		this.sanctionGate = sanctionGate;
		this.suspiciousText = suspiciousText;
		this.clock = clock;
		this.cacheInvalidator = cacheInvalidator;
	}

	@Transactional
	public Event update(String eventId, String title, String date, String time, String format, String host,
			String cover, String streamUrl, String location, String city, String state, String userId) {
		Event event = getById(eventId);
		if (!event.createdBy().equals(userId)) {
			throw new ApiException(403, "NOT_EVENT_OWNER", "Este evento nao e seu.");
		}
		TextFieldLimit.check("EVENT_FIELD_TOO_LONG", "titulo", title, 255);
		TextFieldLimit.check("EVENT_FIELD_TOO_LONG", "local", location, 255);
		TextFieldLimit.check("EVENT_FIELD_TOO_LONG", "host", host, 255);
		TextFieldLimit.check("EVENT_FIELD_TOO_LONG", "formato", format, 32);
		TextFieldLimit.check("EVENT_FIELD_TOO_LONG", "transmissao", streamUrl, 500);
		event.applyUpdate(title, date, time, format, host, cover, httpsOrNull(streamUrl), location, clock.instant());
		requireRealisticSchedule(event);
		CityState place = CityState.of(city, state);
		if (place != null && "ONLINE".equalsIgnoreCase(format == null ? "" : format.trim())) {
			throw new ApiException(400, "EVENT_CITY_NOT_IN_PERSON",
				"Cidade e estado so valem para evento presencial ou hibrido.");
		}
		event.placeAt(place);
		// Serie continua precisa de horario fixo mesmo depois da edicao de data.
		if (event.recurrence() != EventRecurrence.NONE) {
			requireScheduledTime(event);
		}
		repository.save(event);
		cacheInvalidator.afterCommit(CacheConfig.EVENTS);
		return event;
	}

	@Transactional
	public Event create(String title, String date, String time, String format, String host, String cover,
			String streamUrl, String location, String recurrence, String city, String state, String userId) {
		// Os mesmos tetos das colunas (`events.title varchar(255)`...): sem esta checagem o banco
		// recusava a gravacao com "Data too long" e a pessoa recebia 409 "Operacao conflita com o
		// estado atual dos dados" — que nao diz qual campo passou do limite.
		TextFieldLimit.check("EVENT_FIELD_TOO_LONG", "titulo", title, 255);
		TextFieldLimit.check("EVENT_FIELD_TOO_LONG", "local", location, 255);
		TextFieldLimit.check("EVENT_FIELD_TOO_LONG", "host", host, 255);
		TextFieldLimit.check("EVENT_FIELD_TOO_LONG", "formato", format, 32);
		TextFieldLimit.check("EVENT_FIELD_TOO_LONG", "transmissao", streamUrl, 500);
		String id = "event_" + UUID.randomUUID().toString().replace("-", "");
		Event event = Event.create(id, title, date, time, format, host, cover, httpsOrNull(streamUrl), location,
			userId, clock.instant());
		requireRealisticSchedule(event);
		CityState place = CityState.of(city, state);
		if (place != null && "ONLINE".equalsIgnoreCase(format == null ? "" : format.trim())) {
			throw new ApiException(400, "EVENT_CITY_NOT_IN_PERSON",
				"Cidade e estado so valem para evento presencial ou hibrido.");
		}
		event.placeAt(place);
		EventRecurrence parsed = parseRecurrence(recurrence);
		if (parsed != EventRecurrence.NONE) {
			requireScheduledTime(event);
			event.changeRecurrence(parsed, id);
		}
		repository.save(event);
		cacheInvalidator.afterCommit(CacheConfig.EVENTS);
		return event;
	}

	/**
	 * Liga, troca ou desliga a recorrencia. So quem criou o evento decide: o ritual e dele.
	 *
	 * A recorrencia vale para a serie inteira — desligar em qualquer ocorrencia para a serie, e o
	 * agendador nao cria a proxima.
	 */
	@Transactional
	public Event changeRecurrence(String eventId, String recurrence, String userId) {
		Event event = getById(eventId);
		if (!event.createdBy().equals(userId)) {
			throw new ApiException(403, "NOT_EVENT_OWNER", "Este evento nao e seu.");
		}
		EventRecurrence parsed = parseRecurrence(recurrence);
		if (parsed != EventRecurrence.NONE) {
			requireScheduledTime(event);
		}
		String seriesId = event.seriesId() != null ? event.seriesId() : (parsed == EventRecurrence.NONE ? null : event.id());
		event.changeRecurrence(parsed, seriesId);
		repository.save(event);
		if (seriesId != null) {
			repository.updateSeriesRecurrence(seriesId, parsed);
		}
		cacheInvalidator.afterCommit(CacheConfig.EVENTS);
		return event;
	}

	private static EventRecurrence parseRecurrence(String raw) {
		if (!StringUtils.hasText(raw)) {
			return EventRecurrence.NONE;
		}
		try {
			return EventRecurrence.valueOf(raw.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException invalid) {
			throw new ApiException(400, "INVALID_RECURRENCE",
				"Recorrencia invalida. Use NONE, WEEKLY, BIWEEKLY ou MONTHLY.");
		}
	}

	/** Um ritual repete num horario fixo: sem hora legivel nao da para marcar a proxima. */
	private static void requireScheduledTime(Event event) {
		if (!event.hasScheduledTime()) {
			throw new ApiException(400, "INVALID_EVENT_TIME",
				"Evento recorrente precisa de horario no formato HH:MM.");
		}
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
		// Ocorrencia removida (pelo dono ou pela moderacao) encerra a serie: sem isto a ocorrencia
		// anterior voltaria a ser a mais recente e recriaria a data que acabou de sair.
		if (event.seriesId() != null) {
			repository.updateSeriesRecurrence(event.seriesId(), EventRecurrence.NONE);
		}
		repository.deleteAttendees(eventId);
		repository.deleteById(eventId);
		cacheInvalidator.afterCommit(CacheConfig.EVENTS);
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
		// O total de presencas aparece em cada card da lista.
		cacheInvalidator.afterCommit(CacheConfig.EVENTS);
		// userId ja vem do MDC (CurrentUserResolver); nao repetir em addKeyValue — structured log rejeita.
		LOG.atInfo()
			.addKeyValue("eventId", eventId)
			.addKeyValue("action", "ATTENDING")
			.log("event_attendance eventId={} userId={} action=ATTENDING", eventId, userId);
	}

	@Transactional
	public void cancelAttendance(String eventId, String userId) {
		if (!repository.isAttending(eventId, userId)) {
			throw new ApiException(404, "NOT_ATTENDING", "Voce nao esta inscrito neste evento.");
		}
		repository.removeAttendee(eventId, userId);
		cacheInvalidator.afterCommit(CacheConfig.EVENTS);
		LOG.atInfo()
			.addKeyValue("eventId", eventId)
			.addKeyValue("action", "NOT_ATTENDING")
			.log("event_attendance eventId={} userId={} action=NOT_ATTENDING", eventId, userId);
	}

	/**
	 * Lista eventos. Por padrao so os que ainda vao acontecer (ou estao acontecendo): evento vencido
	 * sai da vitrine. `includePast` existe para historico/administracao.
	 *
	 * O cursor sai da pagina que veio do banco: filtrar depois de paginar sem isso faria a lista
	 * achar que acabou.
	 *
	 * A vitrine e a mesma para todo mundo: entra no cache e sai dele quando um evento e criado,
	 * editado, apagado ou quando alguem confirma presenca.
	 */
	@Cacheable(cacheNames = CacheConfig.EVENTS,
		key = "#format + '|' + #city + '|' + #state + '|' + #before + '|' + #limit + '|' + #includePast + '|' + #recurringOnly",
		sync = true)
	public EventPage list(String format, String city, String state, java.time.Instant before, int limit,
			boolean includePast, boolean recurringOnly) {
		// Filtro por lugar (so encontros presenciais/hibridos): `state` sozinho filtra a UF; `city`
		// exige `state`. A cidade compara sem acento e sem caixa.
		String uf = null;
		String cityKey = null;
		if (StringUtils.hasText(city)) {
			CityState place = CityState.of(city, state);
			uf = place.state();
			cityKey = place.cityKey();
		} else if (StringUtils.hasText(state)) {
			uf = CityState.requireState(state);
		}
		List<Event> fetched = repository.findAll(format, uf, cityKey, before, recurringOnly, limit);
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

	/** Presencas da pagina inteira, numa consulta. */
	public java.util.Map<String, Integer> countAttendeesByEventIds(java.util.Collection<String> eventIds) {
		return repository.countAttendeesByEventIds(eventIds);
	}

	/** Events among {@code eventIds} where the user confirmed attendance (one query). */
	public java.util.Set<String> attendingEventIds(String userId, java.util.List<String> eventIds) {
		return repository.findAttendingEventIds(userId, eventIds);
	}

}
