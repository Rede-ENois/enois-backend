package com.socialcoisa.rede.mentoring.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.mentoring.application.port.out.JitsiDialInGateway;
import com.socialcoisa.rede.mentoring.application.port.out.MentorRepository;
import com.socialcoisa.rede.mentoring.domain.Mentor;
import com.socialcoisa.rede.mentoring.domain.MentorAvailability;
import com.socialcoisa.rede.mentoring.domain.MentorSession;
import com.socialcoisa.rede.mentoring.domain.MentorSlot;
import com.socialcoisa.rede.mentoring.domain.RoomMode;
import com.socialcoisa.rede.mentoring.domain.SessionStatus;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class MentorService {

	/** Os horários da agenda são escolhidos em São Paulo, que é como a tela mostra e como o mentor pensa. */
	static final ZoneId AGENDA_ZONE = ZoneId.of("America/Sao_Paulo");
	/** Janela em que os horários são gerados a partir das regras semanais. */
	static final int GENERATION_DAYS = 28;
	/** Piso e teto do preço de uma sessão em faíscas (o teto evita anúncio absurdo no catálogo). */
	public static final long MIN_SPARKS_PRICE = CoinService.FAIS(50L);
	public static final long MAX_SPARKS_PRICE = CoinService.FAIS(10_000L);
	/**
	 * Depois do fim do horário, o mentorado tem esta janela para reclamar. Passada ela, o escrow
	 * libera sozinho: dinheiro retido para sempre é dinheiro sumido.
	 */
	static final Duration SETTLEMENT_GRACE = Duration.ofDays(7);
	/** Sessão paga cujo horário sumiu da agenda libera depois disto, contado da inscrição. */
	static final Duration ORPHAN_SETTLEMENT = Duration.ofDays(21);
	private static final String ESCROW_REF_PREFIX = "mentoria:";

	public record AvailabilityRule(int dayOfWeek, List<String> times) {
	}

	private final MentorRepository repository;
	private final Clock clock;
	private final CoinService coins;
	private final MentoringRooms rooms;
	private final JitsiDialInGateway dialInGateway;
	private final TransactionTemplate settlement;

	public MentorService(MentorRepository repository, Clock clock, CoinService coins, MentoringRooms rooms,
			JitsiDialInGateway dialInGateway, PlatformTransactionManager transactionManager) {
		this.repository = repository;
		this.clock = clock;
		this.coins = coins;
		this.rooms = rooms;
		this.dialInGateway = dialInGateway;
		this.settlement = new TransactionTemplate(transactionManager);
		this.settlement.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** Ref do escrow de uma sessão: o mesmo nos dois caminhos, para liberar e reembolsar não conviverem. */
	static String escrowRef(MentorSession session) {
		return ESCROW_REF_PREFIX + session.id();
	}

	@Transactional
	public Mentor create(String userId, List<String> expertise, String price, String banner) {
		return create(userId, expertise, price, banner, null);
	}

	@Transactional
	public Mentor create(String userId, List<String> expertise, String price, String banner, String meetingUrl) {
		return create(userId, expertise, price, banner, meetingUrl, null);
	}

	@Transactional
	public Mentor create(String userId, List<String> expertise, String price, String banner, String meetingUrl,
			Long sparksPrice) {
		return create(userId, expertise, price, banner, meetingUrl, sparksPrice, RoomMode.JITSI_SESSION, null);
	}

	@Transactional
	public Mentor create(String userId, List<String> expertise, String price, String banner, String meetingUrl,
			Long sparksPrice, RoomMode roomMode, String jitsiRoom) {
		if (repository.findByUserId(userId).isPresent()) {
			throw new ApiException(409, "ALREADY_MENTOR", "Voce ja esta cadastrado como mentor.");
		}
		String cleanUrl = sanitizeMeetingUrl(meetingUrl);
		String id = "mentor_" + UUID.randomUUID().toString().replace("-", "");
		Mentor mentor = Mentor.create(id, userId, expertise, price, banner, cleanUrl,
			validarPrecoEmFaiscas(sparksPrice), validatedRoomMode(roomMode, cleanUrl),
			validatedJitsiRoom(jitsiRoom), clock.instant());
		repository.save(mentor);
		return mentor;
	}

	@Transactional
	public Mentor update(String mentorId, List<String> expertise, String price, String userId) {
		return update(mentorId, expertise, price, null, userId);
	}

	@Transactional
	public Mentor update(String mentorId, List<String> expertise, String price, String banner, String userId) {
		return update(mentorId, expertise, price, banner, null, userId);
	}

	@Transactional
	public Mentor update(String mentorId, List<String> expertise, String price, String banner, String meetingUrl,
			String userId) {
		return update(mentorId, expertise, price, banner, meetingUrl, null, userId);
	}

	@Transactional
	public Mentor update(String mentorId, List<String> expertise, String price, String banner, String meetingUrl,
			Long sparksPrice, String userId) {
		return update(mentorId, expertise, price, banner, meetingUrl, sparksPrice, null, null, userId);
	}

	@Transactional
	public Mentor update(String mentorId, List<String> expertise, String price, String banner, String meetingUrl,
			Long sparksPrice, RoomMode roomMode, String jitsiRoom, String userId) {
		Mentor mentor = requireOwner(mentorId, userId);
		String cleanUrl = sanitizeMeetingUrl(meetingUrl == null ? mentor.meetingUrl() : meetingUrl);
		RoomMode mode = validatedRoomMode(roomMode == null ? mentor.roomMode() : roomMode, cleanUrl);
		// Sala permanente: mantém a que existe; nome novo só quando o mentor pediu um agora.
		String room = roomMode == null || roomMode != RoomMode.JITSI_PERMANENT
			? mentor.jitsiRoom()
			: validatedJitsiRoom(jitsiRoom == null ? mentor.jitsiRoom() : jitsiRoom);
		Mentor updated = mentor.update(expertise, price, banner, cleanUrl, validarPrecoEmFaiscas(sparksPrice), mode,
			room, clock.instant());
		repository.save(updated);
		return updated;
	}

	/**
	 * Modo de sala coerente com os dados: sala própria exige link; Jitsi funciona sem nada. Sem
	 * modo informado, quem tem link configurado continua com sala própria (mesma regra do backfill
	 * da migração) — e quem não tem, ganha a sala Jitsi por sessão.
	 */
	private RoomMode validatedRoomMode(RoomMode requested, String meetingUrl) {
		RoomMode mode = requested != null ? requested
			: (meetingUrl != null && !meetingUrl.isBlank() ? RoomMode.CUSTOM : RoomMode.JITSI_SESSION);
		if (mode == RoomMode.CUSTOM && (meetingUrl == null || meetingUrl.isBlank())) {
			throw new ApiException(400, "MEETING_URL_REQUIRED",
				"Para usar uma sala propria, informe o link da sala.");
		}
		return mode;
	}

	private String validatedJitsiRoom(String jitsiRoom) {
		return jitsiRoom == null ? null : rooms.sanitizeRoom(jitsiRoom);
	}

	/**
	 * Preço da sessão em faíscas: null é "não aceito" (mentoria de graça, como sempre foi). O teto
	 * existe para o catálogo não virar piada; o piso, para não passar valor abaixo do custo de um
	 * lançamento no ledger.
	 */
	private Long validarPrecoEmFaiscas(Long sparksPrice) {
		if (sparksPrice == null) {
			return null;
		}
		if (sparksPrice < MIN_SPARKS_PRICE) {
			throw new ApiException(400, "SPARKS_PRICE_TOO_LOW",
				"O preço mínimo de uma sessão é " + (MIN_SPARKS_PRICE / CoinService.SCALE) + " faíscas.");
		}
		if (sparksPrice > MAX_SPARKS_PRICE) {
			throw new ApiException(400, "SPARKS_PRICE_TOO_HIGH",
				"O preço máximo de uma sessão é " + (MAX_SPARKS_PRICE / CoinService.SCALE) + " faíscas.");
		}
		return sparksPrice;
	}

	/** Sala externa (Meet/Jitsi...): opcional; se vier, tem que ser um link https de ate 500. */
	private String sanitizeMeetingUrl(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String url = raw.trim();
		if (!url.startsWith("https://") || url.length() > 500) {
			throw new ApiException(400, "INVALID_MEETING_URL", "Informe um link https valido de ate 500 caracteres.");
		}
		return url;
	}

	@Transactional
	public MentorSlot createSlot(String mentorId, String userId, java.time.Instant startsAt, java.time.Instant endsAt) {
		Mentor mentor = repository.findById(mentorId)
			.orElseThrow(() -> new ApiException(404, "MENTOR_NOT_FOUND", "Mentor nao encontrado."));
		if (!mentor.userId().equals(userId)) {
			throw new ApiException(403, "FORBIDDEN", "Voce nao e o dono deste perfil de mentor.");
		}
		String id = "slot_" + UUID.randomUUID().toString().replace("-", "");
		MentorSlot slot = MentorSlot.create(id, mentorId, startsAt, endsAt);
		repository.saveSlot(slot);
		return slot;
	}

	/** Remove um horario publicado; so o dono. Horario ja reservado nao pode sumir. */
	@Transactional
	public void deleteSlot(String mentorId, String slotId, String userId) {
		Mentor mentor = repository.findById(mentorId)
			.orElseThrow(() -> new ApiException(404, "MENTOR_NOT_FOUND", "Mentor nao encontrado."));
		if (!mentor.userId().equals(userId)) {
			throw new ApiException(403, "FORBIDDEN", "Voce nao e o dono deste perfil de mentor.");
		}
		MentorSlot slot = repository.findSlotById(slotId)
			.orElseThrow(() -> new ApiException(404, "SLOT_NOT_FOUND", "Horario nao encontrado."));
		// O slot tem que pertencer ao mentor da URL; sem isso, o dono de um mentor apagava o
		// horario de outro passando o próprio mentorId (EN-03).
		if (!slot.mentorId().equals(mentor.id())) {
			throw new ApiException(404, "SLOT_NOT_FOUND", "Horario nao encontrado.");
		}
		if (!slot.available()) {
			throw new ApiException(409, "SLOT_BOOKED", "Este horario ja foi reservado.");
		}
		repository.deleteSlot(slotId);
	}

	@Transactional
	public MentorSession scheduleSession(String mentorId, String menteeId, String slotId, String message) {
		Mentor mentor = repository.findById(mentorId)
			.orElseThrow(() -> new ApiException(404, "MENTOR_NOT_FOUND", "Mentor nao encontrado."));
		MentorSlot slot = repository.findSlotById(slotId)
			.orElseThrow(() -> new ApiException(404, "SLOT_NOT_FOUND", "Horario nao encontrado."));
		if (!slot.mentorId().equals(mentor.id())) {
			throw new ApiException(404, "SLOT_NOT_FOUND", "Horario nao encontrado.");
		}
		if (!slot.available()) {
			throw new ApiException(409, "SLOT_UNAVAILABLE", "Horario nao esta mais disponivel.");
		}
		if (mentor.userId().equals(menteeId)) {
			throw new ApiException(400, "CANNOT_BOOK_OWN", "Voce nao pode agendar uma sessao com voce mesmo.");
		}
		repository.saveSlot(slot.book());
		String id = "mentor_session_" + UUID.randomUUID().toString().replace("-", "");
		// A sala é resolvida agora e fica gravada na sessão: mudar a sala do perfil depois não move
		// uma sessão já marcada (quem agendou já recebeu este link).
		String roomUrl = rooms.sessionRoom(mentor, id);
		// Sessão paga: as faíscas do mentorado vão para o escrow AGORA e só saem de lá na conclusão
		// (menos a taxa da plataforma) ou no cancelamento (integral). Se não há saldo, a inscrição
		// inteira falha e o horário continua livre — nada de sessão marcada sem pagamento.
		Long price = mentor.sparksPrice();
		MentorSession session = MentorSession.schedule(id, mentorId, menteeId, slotId, message, price,
			mentor.roomMode(), roomUrl, clock.instant());
		if (price != null && price > 0) {
			coins.ensureGenesisFor(menteeId);
			coins.escrowHold(menteeId, price, escrowRef(session),
				"Mentoria: " + String.join(", ", mentor.expertise()));
		}
		repository.saveSession(session);
		return session;
	}

	@Transactional
	public void delete(String mentorId, String userId) {
		Mentor mentor = repository.findById(mentorId)
			.orElseThrow(() -> new ApiException(404, "MENTOR_NOT_FOUND", "Mentor nao encontrado."));
		if (!mentor.userId().equals(userId)) {
			throw new ApiException(403, "FORBIDDEN", "Voce nao e o dono deste perfil de mentor.");
		}
		repository.delete(mentorId);
	}

	public List<Mentor> list(String expertise, java.time.Instant before, int limit) {
		return repository.findAll(expertise, before, limit);
	}

	public Mentor getById(String id) {
		return repository.findById(id)
			.orElseThrow(() -> new ApiException(404, "MENTOR_NOT_FOUND", "Mentor nao encontrado."));
	}

	public List<MentorSlot> getSlots(String mentorId) {
		return repository.findSlotsByMentor(mentorId);
	}

	/** Horário de uma sessão pelo id, ou null quando ele não existe mais. */
	public MentorSlot slotOf(String slotId) {
		return slotId == null ? null : repository.findSlotById(slotId).orElse(null);
	}

	/** Perfil de mentor sem exceção: sessões antigas sobrevivem ao perfil apagado. */
	public Mentor mentorOrNull(String mentorId) {
		return repository.findById(mentorId).orElse(null);
	}

	/**
	 * Proximo horario livre do mentor (ISO-8601) ou null quando nao ha agenda publicada.
	 *
	 * Antes o campo vinha sempre nulo na resposta, entao a tela nao tinha como dizer quando a pessoa
	 * atende nem por onde comecar o agendamento.
	 */
	public String nextAvailableSlot(String mentorId) {
		java.time.Instant now = clock.instant();
		return repository.findSlotsByMentor(mentorId).stream()
			.filter(MentorSlot::available)
			.filter(slot -> slot.startsAt() != null && slot.startsAt().isAfter(now))
			.sorted(java.util.Comparator.comparing(MentorSlot::startsAt))
			.map(slot -> slot.startsAt().toString())
			.findFirst()
			.orElse(null);
	}

	/** Inscrições recebidas pelo mentor (só o dono), com o horário de cada uma. */
	public List<MentorSession> sessionsOf(String mentorId, String userId) {
		requireOwner(mentorId, userId);
		return repository.findSessionsByMentor(mentorId);
	}

	public List<MentorAvailability> availabilitiesOf(String mentorId, String userId) {
		requireOwner(mentorId, userId);
		return repository.findAvailabilitiesByMentor(mentorId);
	}

	/** Sessões que EU marquei como mentorado (a pessoa também acompanha o que agendou). */
	public List<MentorSession> sessionsByMentee(String menteeId) {
		return repository.findSessionsByMentee(menteeId);
	}

	/**
	 * Link da sala de uma sessão. Sessão antiga (marcada antes deste recurso) não tem o link
	 * gravado, então ele é derivado do perfil do mentor na leitura — sem gravar nada.
	 */
	public String roomUrlOf(MentorSession session, Mentor mentor) {
		if (session.roomUrl() != null && !session.roomUrl().isBlank()) {
			return session.roomUrl();
		}
		return mentor == null ? null : rooms.sessionRoom(mentor, session.id());
	}

	/** Sessão vista por quem participa dela (mentor dono ou mentorado); qualquer outro recebe 403. */
	public MentorSession sessionForParticipant(String sessionId, String userId) {
		MentorSession session = repository.findSessionById(sessionId)
			.orElseThrow(() -> new ApiException(404, "SESSION_NOT_FOUND", "Sessao nao encontrada."));
		if (!session.menteeId().equals(userId)) {
			Mentor mentor = repository.findById(session.mentorId()).orElse(null);
			if (mentor == null || !mentor.userId().equals(userId)) {
				throw new ApiException(403, "FORBIDDEN", "Voce nao participa desta sessao.");
			}
		}
		return session;
	}

	/** Perfil do mentor exigindo que quem pede seja o dono (painel de configuração da sala). */
	public Mentor ownMentor(String mentorId, String userId) {
		return requireOwner(mentorId, userId);
	}

	/**
	 * Sala que a próxima sessão vai usar, para o painel do mentor. Em
	 * {@link RoomMode#JITSI_SESSION} ainda não existe link: a sala nasce no agendamento.
	 */
	public String mentorRoomUrl(Mentor mentor) {
		if (mentor.roomMode() == RoomMode.JITSI_PERMANENT) {
			return rooms.jitsiUrl(rooms.permanentRoom(mentor));
		}
		if (mentor.roomMode() == RoomMode.CUSTOM) {
			return mentor.meetingUrl();
		}
		return null;
	}
	/** Sala no Jitsi (com dial-in pela API pública) ou sala própria do mentor. */
	public boolean jitsiRoom(MentorSession session) {
		return rooms.isJitsi(session.roomMode());
	}

	public String conferenceName(String roomUrl) {
		return rooms.conferenceName(roomUrl);
	}

	/** Como entrar na conferência por telefone (API pública do Jitsi); vazio quando indisponível. */
	public record SessionDialIn(String conference, String pin, List<String> numbers) {
	}

	public SessionDialIn dialIn(String sessionId, String userId) {
		MentorSession session = sessionForParticipant(sessionId, userId);
		if (!rooms.isJitsi(session.roomMode())) {
			throw new ApiException(400, "NOT_A_JITSI_ROOM",
				"Esta sessao usa uma sala externa; pergunte ao mentor como entrar.");
		}
		Mentor mentor = mentorOrNull(session.mentorId());
		String roomUrl = roomUrlOf(session, mentor);
		if (roomUrl == null) {
			throw new ApiException(404, "ROOM_NOT_FOUND", "Sessao nao encontrada.");
		}
		String conference = rooms.conferenceName(roomUrl);
		return new SessionDialIn(conference, dialInGateway.conferencePin(conference).orElse(null),
			dialInGateway.phoneNumbers(conference));
	}

	/**
	 * O mentor confirma que a sessão aconteceu: o escrow libera o preço para ele, menos a taxa da
	 * plataforma (10%, a mesma regra do marketplace e dos serviços).
	 */
	@Transactional
	public MentorSession completeSession(String mentorId, String sessionId, String userId) {
		Mentor mentor = requireOwner(mentorId, userId);
		MentorSession session = requireSession(mentor, sessionId);
		requireScheduled(session);
		if (session.paid()) {
			coins.escrowRelease(mentor.userId(), session.priceSparks(), escrowRef(session), "Mentoria concluida");
		}
		MentorSession completed = session.complete();
		repository.saveSession(completed);
		repository.save(mentor.withSessionCompleted(clock.instant()));
		return completed;
	}

	/**
	 * Cancelamento: o escrow devolve INTEGRAL para quem pagou. O mentor cancela até a conclusão; o
	 * mentorado, só antes de o horário começar — depois disso quem decide é quem atende. Horário
	 * ainda no futuro volta para a agenda.
	 */
	@Transactional
	public MentorSession cancelSession(String mentorId, String sessionId, String userId) {
		Mentor mentor = repository.findById(mentorId)
			.orElseThrow(() -> new ApiException(404, "MENTOR_NOT_FOUND", "Mentor nao encontrado."));
		MentorSession session = requireSession(mentor, sessionId);
		boolean isMentor = mentor.userId().equals(userId);
		if (!isMentor && !session.menteeId().equals(userId)) {
			throw new ApiException(403, "FORBIDDEN", "Voce nao participa desta sessao.");
		}
		requireScheduled(session);
		MentorSlot slot = session.slotId() == null
			? null
			: repository.findSlotById(session.slotId()).orElse(null);
		if (!isMentor && slot != null && slot.startsAt() != null && !slot.startsAt().isAfter(clock.instant())) {
			throw new ApiException(409, "SESSION_ALREADY_STARTED",
				"A sessao ja comecou; quem cancela depois disso e o mentor.");
		}
		if (session.paid()) {
			coins.escrowRefund(session.menteeId(), session.priceSparks(), escrowRef(session), "Mentoria cancelada");
		}
		MentorSession cancelled = session.cancel();
		repository.saveSession(cancelled);
		if (slot != null && !slot.available() && slot.startsAt() != null
				&& slot.startsAt().isAfter(clock.instant())) {
			repository.saveSlot(slot.release());
		}
		return cancelled;
	}

	/**
	 * O escrow não pode ficar preso: passada a janela de contestação do mentorado, o valor cai para
	 * o mentor com a taxa, mesmo sem a confirmação dele — a sessão aconteceu. Roda de hora em hora,
	 * cada sessão na própria transação para uma falha não travar as outras.
	 */
	@Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
	public void settleExpiredSessions() {
		Instant now = clock.instant();
		for (MentorSession candidate : repository.findScheduledPaidSessions()) {
			try {
				if (!settleable(candidate, now)) {
					continue;
				}
				settlement.executeWithoutResult(status -> {
					MentorSession session = repository.findSessionById(candidate.id()).orElse(null);
					if (session == null || session.status() != SessionStatus.SCHEDULED || !settleable(session, now)) {
						return;
					}
					Mentor mentor = repository.findById(session.mentorId()).orElse(null);
					if (mentor == null) {
						// Perfil de mentor apagado no meio do caminho: ninguém para receber, então o
						// mentorado recebe de volta.
						coins.escrowRefund(session.menteeId(), session.priceSparks(), escrowRef(session),
							"Mentoria encerrada");
					}
					else {
						coins.escrowRelease(mentor.userId(), session.priceSparks(), escrowRef(session),
							"Mentoria concluida (liberacao automatica)");
					}
					repository.saveSession(session.complete());
					if (mentor != null) {
						repository.save(mentor.withSessionCompleted(now));
					}
				});
			}
			catch (RuntimeException ignored) {
				// A próxima rodada tenta de novo; nada aqui pode derrubar o worker.
			}
		}
	}

	private boolean settleable(MentorSession session, Instant now) {
		if (!session.paid() || session.slotId() == null) {
			return false;
		}
		return repository.findSlotById(session.slotId())
			.map(slot -> slot.endsAt() != null && slot.endsAt().plus(SETTLEMENT_GRACE).isBefore(now))
			// Horário sumiu da agenda (dado antigo): libera pelo prazo da inscrição.
			.orElseGet(() -> session.createdAt().plus(ORPHAN_SETTLEMENT).isBefore(now));
	}

	private MentorSession requireSession(Mentor mentor, String sessionId) {
		MentorSession session = repository.findSessionById(sessionId)
			.orElseThrow(() -> new ApiException(404, "SESSION_NOT_FOUND", "Sessao nao encontrada."));
		if (!session.mentorId().equals(mentor.id())) {
			throw new ApiException(404, "SESSION_NOT_FOUND", "Sessao nao encontrada.");
		}
		return session;
	}

	private void requireScheduled(MentorSession session) {
		if (session.status() != SessionStatus.SCHEDULED) {
			throw new ApiException(409, "SESSION_NOT_SCHEDULED", "Esta sessao ja foi encerrada.");
		}
	}

	/**
	 * Substitui a disponibilidade semanal do mentor e reconcilia os horários publicados: gera os
	 * que faltam para os próximos {@link #GENERATION_DAYS} dias e retira os horários livres que não
	 * correspondem mais a nenhuma regra. Horário já reservado nunca é tocado.
	 */
	@Transactional
	public List<MentorAvailability> saveAvailability(String mentorId, String userId, List<AvailabilityRule> rules) {
		Mentor mentor = requireOwner(mentorId, userId);
		List<MentorAvailability> cleaned = validateRules(mentor.id(), rules);

		repository.deleteAvailabilitiesByMentor(mentor.id());
		cleaned.forEach(repository::saveAvailability);

		Instant now = clock.instant();
		Set<Instant> wanted = new HashSet<>();
		LocalDate today = LocalDate.ofInstant(now, AGENDA_ZONE);
		for (int day = 0; day < GENERATION_DAYS; day++) {
			LocalDate date = today.plusDays(day);
			int weekday = date.getDayOfWeek().getValue();
			for (MentorAvailability rule : cleaned) {
				if (rule.dayOfWeek() != weekday) {
					continue;
				}
				Instant startsAt = date.atTime(LocalTime.parse(rule.timeOfDay())).atZone(AGENDA_ZONE).toInstant();
				if (!startsAt.isAfter(now)) {
					continue;
				}
				wanted.add(startsAt);
			}
		}

		Set<Instant> existing = new HashSet<>();
		for (MentorSlot slot : repository.findSlotsByMentor(mentor.id())) {
			if (slot.startsAt() == null) {
				continue;
			}
			existing.add(slot.startsAt());
			boolean stale = slot.available() && slot.startsAt().isAfter(now) && !wanted.contains(slot.startsAt());
			if (stale) {
				repository.deleteSlot(slot.id());
			}
		}
		for (Instant startsAt : wanted) {
			if (existing.contains(startsAt)) {
				continue;
			}
			String id = "slot_" + UUID.randomUUID().toString().replace("-", "");
			repository.saveSlot(MentorSlot.create(id, mentor.id(), startsAt, startsAt.plusSeconds(3600)));
		}
		return repository.findAvailabilitiesByMentor(mentor.id());
	}

	private Mentor requireOwner(String mentorId, String userId) {
		Mentor mentor = repository.findById(mentorId)
			.orElseThrow(() -> new ApiException(404, "MENTOR_NOT_FOUND", "Mentor nao encontrado."));
		if (!mentor.userId().equals(userId)) {
			throw new ApiException(403, "FORBIDDEN", "Voce nao e o dono deste perfil de mentor.");
		}
		return mentor;
	}

	private List<MentorAvailability> validateRules(String mentorId, List<AvailabilityRule> rules) {
		if (rules == null || rules.isEmpty()) {
			return List.of();
		}
		List<MentorAvailability> result = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (AvailabilityRule rule : rules) {
			if (rule == null || rule.dayOfWeek() < 1 || rule.dayOfWeek() > 7) {
				throw new ApiException(400, "INVALID_DAY_OF_WEEK", "Dia da semana deve estar entre 1 (segunda) e 7 (domingo).");
			}
			List<String> times = rule.times() == null ? List.of() : rule.times();
			if (times.size() > 12) {
				throw new ApiException(400, "TOO_MANY_TIMES", "No maximo 12 horarios por dia.");
			}
			for (String raw : times) {
				String time = raw == null ? "" : raw.trim();
				LocalTime parsed;
				try {
					parsed = LocalTime.parse(time.length() == 5 ? time : "");
				} catch (DateTimeParseException e) {
					throw new ApiException(400, "INVALID_TIME", "Horario invalido; use o formato HH:mm.");
				}
				String normalized = String.format("%02d:%02d", parsed.getHour(), parsed.getMinute());
				if (!seen.add(rule.dayOfWeek() + "-" + normalized)) {
					continue;
				}
				String id = "avail_" + UUID.randomUUID().toString().replace("-", "");
				result.add(MentorAvailability.create(id, mentorId, rule.dayOfWeek(), normalized));
			}
		}
		result.sort(Comparator.comparingInt(MentorAvailability::dayOfWeek).thenComparing(MentorAvailability::timeOfDay));
		return result;
	}

}
