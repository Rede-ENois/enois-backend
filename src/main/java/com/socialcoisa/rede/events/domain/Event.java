package com.socialcoisa.rede.events.domain;

import java.time.Instant;

import com.socialcoisa.rede.shared.geo.CityState;

public class Event {

	/**
	 * O fuso que a rede usa para o dia (streaks, onboarding): a data e a hora do evento são o que o
	 * relógio de quem participa mostra, e no Brasil isso é -03:00 (com horário de verão, a regra
	 * regional cuida).
	 */
	public static final java.time.ZoneId NETWORK_ZONE = java.time.ZoneId.of("America/Sao_Paulo");

	private final String id;
	private String title;
	private String date;
	private String time;
	private String format;
	private String host;
	private String cover;
	private String streamUrl;
	private String location;
	/** Cidade + UF estruturados do encontro presencial/hibrido; nulo = sem cidade (ou online). */
	private CityState place;
	private final String createdBy;
	private final Instant createdAt;
	private Instant updatedAt;
	private Instant sanitizedAt;
	private Integer finalAttendeesCount;
	private EventRecurrence recurrence = EventRecurrence.NONE;
	private String seriesId;
	/** Quem criou quer a sala "Tamo junto" no card deste evento. */
	private boolean tamoJunto;

	private Event(String id, String title, String date, String time, String format, String host, String cover,
			String streamUrl, String location, String createdBy, Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.title = title;
		this.date = date;
		this.time = time;
		this.format = format;
		this.host = host;
		this.cover = cover;
		this.streamUrl = streamUrl;
		this.location = location;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static Event create(String id, String title, String date, String time, String format, String host,
			String cover, String streamUrl, String location, String createdBy, Instant now) {
		return new Event(id, title, date, time, format, host, cover, streamUrl, location, createdBy, now, now);
	}

	public static Event restore(String id, String title, String date, String time, String format, String host,
			String cover, String streamUrl, String location, String createdBy, Instant createdAt, Instant updatedAt) {
		return new Event(id, title, date, time, format, host, cover, streamUrl, location, createdBy, createdAt, updatedAt);
	}

	/**
	 * Comeco do evento no fuso da rede. Sem hora legivel, o dia inteiro comeca a meia-noite: o
	 * evento sem horario ocupa o dia, e nao "nao existe" — a agenda precisa do comeco para desenhar.
	 */
	public java.util.Optional<Instant> startsAt() {
		java.time.LocalDate parsed = parseDate(date);
		if (parsed == null) {
			return java.util.Optional.empty();
		}
		java.time.LocalTime start = parseTime(time).orElse(java.time.LocalTime.MIDNIGHT);
		return java.util.Optional.of(parsed.atTime(start).atZone(NETWORK_ZONE).toInstant());
	}

	/**
	 * Fim do evento (a hora informada, no fuso da rede).
	 *
	 * A data e texto livre no schema, entao o parser aceita o formato ISO (o que o app envia) e o
	 * brasileiro dd/MM/yyyy. Quando nenhum dos dois casa, devolve vazio: sem data confiavel o evento
	 * NAO e considerado vencido — apagar dado pessoal por causa de um palpite seria pior.
	 *
	 * A hora que a pessoa digitou e a hora local dela: "20:00" tem que valer as 20h no relógio de
	 * quem vai, nao as 20h de UTC (que no Brasil chegam as 17h e encerravam o evento tres horas
	 * antes). O fuso e o mesmo que o resto do app usa para o dia (streaks, onboarding).
	 */
	public java.util.Optional<Instant> expiresAt() {
		java.time.LocalDate parsed = parseDate(date);
		if (parsed == null) {
			return java.util.Optional.empty();
		}
		java.time.LocalTime limit = parseTime(time).orElse(java.time.LocalTime.of(23, 59, 59));
		return java.util.Optional.of(parsed.atTime(limit).atZone(NETWORK_ZONE).toInstant());
	}

	/** ISO (what the app sends) or dd/MM/yyyy; null when the free-text date cannot be read. */
	public static java.time.LocalDate parseDate(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String value = raw.trim();
		try {
			return java.time.LocalDate.parse(value);
		}
		catch (java.time.format.DateTimeParseException ignored) {
			// segue para o formato brasileiro
		}
		try {
			// STRICT + `uuuu`: o padrao SMART aceita 31/02 e "conserta" para 28/02 sem avisar — data
			// que a pessoa nao escreveu nao pode virar evento.
			return java.time.LocalDate.parse(value, java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu")
				.withResolverStyle(java.time.format.ResolverStyle.STRICT));
		}
		catch (java.time.format.DateTimeParseException ignored) {
			return null;
		}
	}

	private java.util.Optional<java.time.LocalTime> parseTime(String raw) {
		if (raw == null || raw.isBlank()) {
			return java.util.Optional.empty();
		}
		String value = raw.trim();
		try {
			return java.util.Optional.of(java.time.LocalTime.parse(value.length() == 5 ? value + ":00" : value));
		}
		catch (java.time.format.DateTimeParseException ignored) {
			return java.util.Optional.empty();
		}
	}

	/**
	 * O evento ja passou (com a folga informada).
	 *
	 * Sem data confiavel, nunca vence — apagar presenca por causa de um palpite seria pior. A
	 * EXCECAO e a data absurda (ano 220122, que apareceu de verdade): essa nao e "sem data", e uma
	 * data que nunca vai chegar, e um evento assim nunca sai da vitrine nem entra na faxina. Ela
	 * conta como vencida para o sistema poder limpar sozinho.
	 */
	public boolean isExpired(Instant now, java.time.Duration grace) {
		if (hasAbsurdSchedule(now)) {
			return true;
		}
		return expiresAt().map(expiry -> now.isAfter(expiry.plus(grace))).orElse(false);
	}

	/** Quanto no futuro ainda e agendamento, e nao engano (ou brincadeira). */
	public static final java.time.Duration MAX_AHEAD = java.time.Duration.ofDays(365L * 5);

	/** Quanto no passado ainda faz sentido cadastrar (evento que acabou de acontecer, registro). */
	public static final java.time.Duration MAX_BEHIND = java.time.Duration.ofDays(365L * 2);

	/**
	 * A data esta fora da janela do que e agendavel?
	 *
	 * Existe porque o campo e texto livre e o banco aceitava qualquer coisa: um "220122-12-12"
	 * entrava como evento valido, ficava para sempre no topo da lista (ordenado por data) e nunca
	 * era sanitizado. Data que nao da para ler NAO entra aqui — essa e tratada como "sem data".
	 */
	public boolean hasAbsurdSchedule(Instant now) {
		java.time.LocalDate parsed = parseDate(date);
		if (parsed == null) {
			// Nao e data legivel. Se o "ano" que a pessoa escreveu tem mais de quatro digitos, nao e
			// texto estranho: e a data impossivel que apareceu de verdade ("220122-12-12"), que sem
			// isto nunca venceria e ficaria no topo da lista para sempre.
			java.util.regex.Matcher digits = java.util.regex.Pattern.compile("(\\d{5,})").matcher(date == null ? "" : date);
			return digits.find();
		}
		Instant when = parsed.atStartOfDay(NETWORK_ZONE).toInstant();
		return when.isAfter(now.plus(MAX_AHEAD)) || when.isBefore(now.minus(MAX_BEHIND));
	}

	/** The parsed date, when it is readable (ISO or dd/MM/yyyy). */
	public java.util.Optional<java.time.LocalDate> scheduledDate() {
		return java.util.Optional.ofNullable(parseDate(date));
	}

	/** The time is readable (HH:mm or HH:mm:ss): a ritual needs a fixed time, not only a day. */
	public boolean hasScheduledTime() {
		return parseTime(time).isPresent();
	}

	/** How the series repeats; NONE for a one-off event. */
	public EventRecurrence recurrence() {
		return recurrence;
	}

	/** Id of the series' first occurrence; null for an event that was never recurring. */
	public String seriesId() {
		return seriesId;
	}

	public void changeRecurrence(EventRecurrence recurrence, String seriesId) {
		this.recurrence = recurrence == null ? EventRecurrence.NONE : recurrence;
		this.seriesId = seriesId;
	}

	/** Liga/desliga a sala "Tamo junto" deste evento (escolha de quem cria ou edita). */
	public void changeTamoJunto(boolean tamoJunto) {
		this.tamoJunto = tamoJunto;
	}

	/** O card deste evento tem a sala "Tamo junto"? Sem isso, a sala nao existe nem pode ser aberta. */
	public boolean tamoJunto() {
		return tamoJunto;
	}

	public boolean isSanitized() {
		return sanitizedAt != null;
	}

	public Instant sanitizedAt() {
		return sanitizedAt;
	}

	/** Presencas confirmadas no momento da sanitizacao (o numero sobrevive sem as identidades). */
	public Integer finalAttendeesCount() {
		return finalAttendeesCount;
	}

	/**
	 * Edição do ritual pelo dono: troca os campos de conteúdo e a data/hora. A recorrencia fica de
	 * fora (tem endpoint próprio, porque ligar/desligar mexe na série inteira).
	 */
	public void applyUpdate(String title, String date, String time, String format, String host, String cover,
			String streamUrl, String location, Instant now) {
		this.title = title;
		this.date = date;
		this.time = time;
		this.format = format;
		this.host = host;
		this.cover = cover;
		this.streamUrl = streamUrl;
		this.location = location;
		this.updatedAt = now;
	}

	/** Cidade/UF: o servico valida (formato presencial/hibrido); o restore hidrata do banco. */
	public void placeAt(CityState place) {
		this.place = place;
	}

	public CityState place() { return place; }

	public void markSanitized(int attendeesCount, Instant now) {
		this.finalAttendeesCount = attendeesCount;
		this.sanitizedAt = now;
	}

	public String id() { return id; }

	public String title() { return title; }

	public String date() { return date; }

	public String time() { return time; }

	public String format() { return format; }

	public String host() { return host; }

	public String cover() { return cover; }

	/** Link da transmissao ao vivo (online/hibrido). */
	public String streamUrl() { return streamUrl; }

	/** Onde o evento acontece (presencial/hibrido). */
	public String location() { return location; }

	public String createdBy() { return createdBy; }

	public Instant createdAt() { return createdAt; }

	public Instant updatedAt() { return updatedAt; }

}
