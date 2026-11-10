package com.socialcoisa.rede.calendar.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Um compromisso da agenda. Aggregate root: todo o resto (convite, lembrete, ocorrência de
 * recorrência) aponta para cá e só existe enquanto a entrada existe.
 *
 * <p>O tempo é guardado como instante mais o fuso do organizador. O instante responde "quando",
 * e o fuso preserva "às 19h" quando a regra de horário do lugar mudar.
 *
 * <p>A entrada não sabe o que é uma entrevista ou uma mentoria; ela sabe quando. A validação de cada
 * tipo fica no módulo dono, que grava a entrada com a origem preenchida.
 */
public class CalendarEntry {

	/** Teto de duração de um compromisso: agenda com semana de duração é erro de digitação, não evento. */
	public static final Duration MAX_DURATION = Duration.ofDays(7);
	public static final int MAX_TITLE = 200;
	public static final int MAX_DESCRIPTION = 2000;

	private final String id;
	private final String ownerId;
	private final CalendarScope scope;
	private final CalendarKind kind;
	private final CalendarEntrySource source;
	private final Instant createdAt;
	private String title;
	private String description;
	private Instant startsAt;
	private Instant endsAt;
	private ZoneId zone;
	private boolean allDay;
	private CalendarEntryState state;
	private Instant updatedAt;
	private Instant cancelledAt;

	public CalendarEntry(String id, String ownerId, CalendarScope scope, CalendarKind kind,
			CalendarEntrySource source, String title, String description, Instant startsAt, Instant endsAt,
			ZoneId zone, boolean allDay, CalendarEntryState state, Instant createdAt, Instant updatedAt,
			Instant cancelledAt) {
		this.id = Objects.requireNonNull(id, "id");
		this.ownerId = requireText(ownerId, "dono do compromisso");
		this.scope = Objects.requireNonNull(scope, "agenda");
		this.kind = Objects.requireNonNull(kind, "tipo");
		this.source = Objects.requireNonNull(source, "origem");
		this.createdAt = Objects.requireNonNull(createdAt, "criação");
		this.updatedAt = Objects.requireNonNull(updatedAt, "atualização");
		this.cancelledAt = cancelledAt;
		this.state = Objects.requireNonNull(state, "situação");
		this.title = requireText(title, "título");
		this.description = description;
		this.zone = Objects.requireNonNull(zone, "fuso");
		this.allDay = allDay;
		applyInterval(startsAt, endsAt);
		checkLengths();
	}

	public static CalendarEntry create(String id, String ownerId, CalendarScope scope, CalendarKind kind,
			CalendarEntrySource source, String title, String description, Instant startsAt, Instant endsAt,
			ZoneId zone, boolean allDay, Instant now) {
		return new CalendarEntry(id, ownerId, scope, kind, source, title, description, startsAt, endsAt, zone,
			allDay, CalendarEntryState.ACTIVE, now, now, null);
	}

	/**
	 * Reagendamento: mantém identidade e origem, muda o intervalo e o fuso de quem organizou.
	 * Reagendar para o mesmo horário não mexe em `updatedAt` nem no `SEQUENCE` do arquivo .ics.
	 */
	public void reschedule(Instant newStartsAt, Instant newEndsAt, ZoneId newZone, Instant now) {
		requireActive("reagendar");
		Instant start = Objects.requireNonNull(newStartsAt, "início");
		Instant end = Objects.requireNonNull(newEndsAt, "fim");
		ZoneId newZoneId = Objects.requireNonNull(newZone, "fuso");
		checkInterval(start, end);
		if (start.equals(startsAt) && end.equals(endsAt) && newZoneId.equals(zone)) {
			return;
		}
		startsAt = start;
		endsAt = end;
		zone = newZoneId;
		updatedAt = Objects.requireNonNull(now, "atualização");
	}

	public void rename(String newTitle, Instant now) {
		requireActive("renomear");
		String checked = requireText(newTitle, "título");
		if (checked.length() > MAX_TITLE) {
			throw new IllegalArgumentException("título do compromisso passa de " + MAX_TITLE + " caracteres");
		}
		if (checked.equals(title)) {
			return;
		}
		title = checked;
		updatedAt = Objects.requireNonNull(now, "atualização");
	}

	public void describe(String newDescription, Instant now) {
		requireActive("editar");
		if (newDescription != null && newDescription.length() > MAX_DESCRIPTION) {
			throw new IllegalArgumentException(
				"descrição do compromisso passa de " + MAX_DESCRIPTION + " caracteres");
		}
		if (Objects.equals(newDescription, description)) {
			return;
		}
		description = newDescription;
		updatedAt = Objects.requireNonNull(now, "atualização");
	}

	public void cancel(Instant now) {
		requireActive("cancelar");
		Instant at = Objects.requireNonNull(now, "cancelamento");
		state = CalendarEntryState.CANCELLED;
		cancelledAt = at;
		updatedAt = at;
	}

	/** Meia-aberto como toda consulta de intervalo: fim igual a início do outro ainda colide. */
	public boolean overlaps(Instant windowStart, Instant windowEnd) {
		return startsAt.isBefore(windowEnd) && endsAt.isAfter(windowStart);
	}

	public Duration duration() {
		return Duration.between(startsAt, endsAt);
	}

	public boolean active() {
		return state == CalendarEntryState.ACTIVE;
	}

	private void applyInterval(Instant newStartsAt, Instant newEndsAt) {
		Instant start = Objects.requireNonNull(newStartsAt, "início");
		Instant end = Objects.requireNonNull(newEndsAt, "fim");
		checkInterval(start, end);
		startsAt = start;
		endsAt = end;
	}

	private static void checkInterval(Instant start, Instant end) {
		if (!end.isAfter(start)) {
			throw new IllegalArgumentException("fim do compromisso tem de ser depois do início");
		}
		if (Duration.between(start, end).compareTo(MAX_DURATION) > 0) {
			throw new IllegalArgumentException(
				"compromisso não pode durar mais de " + MAX_DURATION.toDays() + " dias");
		}
	}

	private void checkLengths() {
		if (title.length() > MAX_TITLE) {
			throw new IllegalArgumentException("título do compromisso passa de " + MAX_TITLE + " caracteres");
		}
		if (description != null && description.length() > MAX_DESCRIPTION) {
			throw new IllegalArgumentException(
				"descrição do compromisso passa de " + MAX_DESCRIPTION + " caracteres");
		}
	}

	private void requireActive(String action) {
		if (state == CalendarEntryState.CANCELLED) {
			throw new IllegalStateException("não dá para " + action + " um compromisso cancelado");
		}
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " é obrigatório");
		}
		return value;
	}

	/**
	 * Fuso pelo identificador IANA (`America/Sao_Paulo`). Só ids que o sistema conhece passam: um
	 * fuso inventado seria aceito como deslocamento fixo e o compromisso cairia na hora errada no
	 * dia da mudança de horário.
	 */
	public static ZoneId zoneOf(String id) {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("fuso do compromisso é obrigatório");
		}
		if (!ZoneId.getAvailableZoneIds().contains(id)) {
			throw new IllegalArgumentException("fuso desconhecido: " + id);
		}
		return ZoneId.of(id);
	}

	public String id() { return id; }

	public String ownerId() { return ownerId; }

	public CalendarScope scope() { return scope; }

	public CalendarKind kind() { return kind; }

	public CalendarEntrySource source() { return source; }

	public String title() { return title; }

	public String description() { return description; }

	public Instant startsAt() { return startsAt; }

	public Instant endsAt() { return endsAt; }

	public ZoneId zone() { return zone; }

	public boolean allDay() { return allDay; }

	public CalendarEntryState state() { return state; }

	public Instant createdAt() { return createdAt; }

	public Instant updatedAt() { return updatedAt; }

	public Instant cancelledAt() { return cancelledAt; }

}
