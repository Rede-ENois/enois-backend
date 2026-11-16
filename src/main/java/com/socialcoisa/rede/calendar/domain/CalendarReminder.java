package com.socialcoisa.rede.calendar.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Aviso de que um compromisso está chegando, marcado para X minutos antes do início.
 *
 * <p>O lembrete é de um compromisso e de uma pessoa: quem organiza e quem aceitou o convite recebem
 * o próprio, e quem recusou não recebe nenhum. Ele não guarda texto, só o compromisso de avisar —
 * o texto nasce no envio, porque o título pode ter mudado depois que o lembrete foi criado.
 *
 * <p>O ciclo é o mesmo dos posts agendados (decisão D4): o worker procura o que venceu, tenta o
 * envio, marca {@code sentAt} quando deu certo e guarda o motivo quando não deu. Depois do teto de
 * tentativas o lembrete para de ser tentado, em vez de girar para sempre num horário que já passou.
 */
public class CalendarReminder {

	/** As opções que a tela oferece; um lembrete fora da lista não existe (a migration também sabe). */
	public static final int[] ALLOWED_MINUTES = {5, 15, 30, 60, 1440};

	/** Depois de três falhas o lembrete para: o horário já passou, insistir não avisa ninguém. */
	public static final int MAX_ATTEMPTS = 3;

	public static final int MAX_ERROR = 500;

	private final String id;
	private final String entryId;
	private final String userId;
	private final int minutesBefore;
	private Instant dueAt;
	private final Instant createdAt;
	private int attempts;
	private Instant sentAt;
	private String lastError;

	public CalendarReminder(String id, String entryId, String userId, int minutesBefore, Instant dueAt,
			int attempts, Instant sentAt, String lastError, Instant createdAt) {
		this.id = requireText(id, "id do lembrete");
		this.entryId = requireText(entryId, "compromisso do lembrete");
		this.userId = requireText(userId, "pessoa do lembrete");
		this.minutesBefore = requireAllowedMinutes(minutesBefore);
		this.dueAt = Objects.requireNonNull(dueAt, "horário do lembrete");
		this.createdAt = Objects.requireNonNull(createdAt, "criação do lembrete");
		this.attempts = requireAttempts(attempts);
		this.sentAt = sentAt;
		this.lastError = lastError;
	}

	/**
	 * Lembrete de uma pessoa para um compromisso que começa em {@code startsAt}. O horário de envio é
	 * o início menos os minutos: um lembrete de 1 dia para um compromisso de amanhã às 19h toca hoje
	 * às 19h.
	 */
	public static CalendarReminder create(String id, String entryId, String userId, int minutesBefore,
			Instant startsAt, Instant now) {
		Instant start = Objects.requireNonNull(startsAt, "início do compromisso");
		return new CalendarReminder(id, entryId, userId, minutesBefore,
			start.minus(Duration.ofMinutes(minutesBefore)), 0, null, null, now);
	}

	/**
	 * O lembrete que já disparou o horário e ainda pode ser tentado. Um lembrete para o passado
	 * (compromisso criado em cima da hora) vence imediatamente, e é isso que a pessoa quer: o aviso
	 * chega agora, não daqui a cinco minutos.
	 */
	public boolean due(Instant now) {
		return sentAt == null && attempts < MAX_ATTEMPTS && !dueAt.isAfter(now);
	}

	/**
	 * Re-arma o lembrete já resolvido. A linha é a mesma — a chave única é pessoa + compromisso +
	 * antecedência —, mas o aviso volta a valer para o horário atual do compromisso, que pode ter
	 * mudado desde que a linha nasceu.
	 */
	public void rearm(Instant startsAt) {
		Instant start = Objects.requireNonNull(startsAt, "início do compromisso");
		this.dueAt = start.minus(Duration.ofMinutes(minutesBefore));
		this.sentAt = null;
		this.attempts = 0;
		this.lastError = null;
	}

	public void sent(Instant at) {
		this.sentAt = Objects.requireNonNull(at, "envio do lembrete");
		this.lastError = null;
	}

	/** Falha contada: o motivo fica com a linha para não se perder, e a tentativa é consumida. */
	public void failed(String reason, Instant now) {
		if (sentAt != null) {
			return;
		}
		this.attempts++;
		String text = reason == null || reason.isBlank() ? "Não foi possível avisar." : reason;
		this.lastError = text.length() > MAX_ERROR ? text.substring(0, MAX_ERROR) : text;
	}

	public boolean exhausted() {
		return sentAt == null && attempts >= MAX_ATTEMPTS;
	}

	/**
	 * A antecedência que a tela oferece. Público porque a remoção também precisa recusar um valor
	 * fora da lista: apagar "7 minutos antes" não tira lembrete nenhum, é um pedido inválido.
	 */
	public static int requireAllowedMinutes(int value) {
		for (int allowed : ALLOWED_MINUTES) {
			if (allowed == value) {
				return value;
			}
		}
		throw new IllegalArgumentException("lembrete só aceita 5, 15, 30, 60 ou 1440 minutos antes");
	}

	private static int requireAttempts(int value) {
		if (value < 0) {
			throw new IllegalArgumentException("tentativas do lembrete não podem ser negativas");
		}
		return value;
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

	public int minutesBefore() { return minutesBefore; }

	public Instant dueAt() { return dueAt; }

	public int attempts() { return attempts; }

	public Instant sentAt() { return sentAt; }

	public String lastError() { return lastError; }

	public Instant createdAt() { return createdAt; }

}