package com.socialcoisa.rede.studypairs.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.preferences.application.PersonalizationService;
import com.socialcoisa.rede.preferences.domain.ContentTaxonomy;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.age.AgeGate;
import com.socialcoisa.rede.shared.age.AgePolicyReader;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.sanctions.SanctionGate;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.shared.text.PlainText;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.TextFieldLimit;
import com.socialcoisa.rede.studypairs.application.port.out.StudyPairRepository;
import com.socialcoisa.rede.studypairs.domain.PopularGoal;
import com.socialcoisa.rede.studypairs.domain.StudyCheckin;
import com.socialcoisa.rede.studypairs.domain.StudyGoal;
import com.socialcoisa.rede.studypairs.domain.StudyPair;
import com.socialcoisa.rede.studypairs.domain.StudyPairRequest;

/**
 * Study pairs: two people with the same goal, one check-in a week.
 *
 * Adults only (ECA Digital): a pair is a private channel between strangers, and minors are only
 * reachable through an accepted connection (same line as messaging and the onboarding people
 * suggestions). Minors and accounts without a declared birth date cannot open a request, so no
 * minor ever sits in the queue; the candidate filter re-checks it anyway.
 */
@Service
public class StudyPairService {

	/** Key in /me/preferences disabledModules that turns this module off for a person. */
	public static final String MODULE = "STUDY_PAIRS";

	static final int MAX_ACTIVE_PAIRS = 2;
	static final int NOTE_MAX_LENGTH = 500;
	static final int HISTORY_WEEKS = 4;
	private static final int CANDIDATE_SCAN = 50;
	private static final int POPULAR_LIMIT = 10;
	private static final String HREF = "/par-de-estudo";
	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	private final StudyPairRepository repository;
	private final UserProfileRepository profiles;
	private final BlockedUsersReader blocks;
	private final AgeGate ageGate;
	private final AgePolicyReader agePolicy;
	private final SanctionGate sanctionGate;
	private final SanctionGateReader sanctions;
	private final NotificationService notifications;
	private final PersonalizationService personalization;
	private final Clock clock;

	public StudyPairService(StudyPairRepository repository, UserProfileRepository profiles, BlockedUsersReader blocks,
			AgeGate ageGate, AgePolicyReader agePolicy, SanctionGate sanctionGate, SanctionGateReader sanctions,
			NotificationService notifications, PersonalizationService personalization, Clock clock) {
		this.repository = repository;
		this.profiles = profiles;
		this.blocks = blocks;
		this.ageGate = ageGate;
		this.agePolicy = agePolicy;
		this.sanctionGate = sanctionGate;
		this.sanctions = sanctions;
		this.notifications = notifications;
		this.personalization = personalization;
		this.clock = clock;
	}

	/** Opens a request and, when someone already waits on the same goal, pairs with the oldest one. */
	@Transactional
	public RequestResult request(String userId, String rawGoal, String rawTopic) {
		UserProfile me = profiles.findById(userId)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuário não encontrado."));
		ageGate.requireDeclared(userId);
		if (agePolicy.isMinor(userId)) {
			throw new ApiException(403, "STUDY_PAIRS_ADULTS_ONLY",
				"O par de estudo é só para maiores de 18 anos, por segurança.");
		}
		if (!me.emailVerified()) {
			throw new ApiException(403, "EMAIL_NOT_VERIFIED", "Confirme seu e-mail para procurar um par de estudo.");
		}
		sanctionGate.requireNotRestricted(userId);
		if (moduleDisabled(userId)) {
			throw new ApiException(409, "STUDY_PAIRS_DISABLED",
				"O par de estudo está desativado nas suas preferências.");
		}

		String goal = rawGoal == null ? "" : PlainText.defuse(rawGoal.trim());
		TextFieldLimit.check("STUDY_GOAL_TOO_LONG", "objetivo", goal, StudyGoal.MAX_LENGTH);
		String goalKey = StudyGoal.key(goal);
		if (goalKey.isEmpty()) {
			throw new ApiException(400, "STUDY_GOAL_REQUIRED", "Conte qual é o seu objetivo de estudo.");
		}
		String topic = StringUtils.hasText(rawTopic) ? rawTopic.trim().toUpperCase(java.util.Locale.ROOT) : null;
		if (topic != null && !ContentTaxonomy.TOPIC_SET.contains(topic)) {
			throw new ApiException(400, "INVALID_TOPIC", "Assunto inválido.");
		}
		if (repository.findOpenRequest(userId).isPresent()) {
			throw new ApiException(409, "STUDY_REQUEST_ALREADY_OPEN",
				"Você já está procurando um par. Cancele o pedido atual para trocar de objetivo.");
		}
		List<StudyPair> myPairs = repository.findActivePairs(userId);
		if (myPairs.size() >= MAX_ACTIVE_PAIRS) {
			throw new ApiException(409, "STUDY_PAIR_LIMIT",
				"Você já tem " + MAX_ACTIVE_PAIRS + " pares de estudo ativos. Encerre um para procurar outro.");
		}

		var now = clock.instant();
		String requestId = "spr_" + UUID.randomUUID().toString().replace("-", "");
		Set<String> currentPartners = myPairs.stream().map(p -> p.partnerOf(userId)).collect(Collectors.toSet());
		for (StudyPairRequest candidate : eligibleCandidates(userId, goalKey, currentPartners)) {
			// Atomic claim: if another request grabbed this candidate first, try the next one.
			if (!repository.closeOpenRequest(candidate.id(), StudyPairRequest.Status.MATCHED)) {
				continue;
			}
			repository.saveRequest(new StudyPairRequest(requestId, userId, goalKey, goal, topic,
				StudyPairRequest.Status.MATCHED, now));
			StudyPair pair = new StudyPair("sp_" + UUID.randomUUID().toString().replace("-", ""),
				candidate.userId(), userId, goalKey, candidate.goalText(),
				candidate.topic() != null ? candidate.topic() : topic, StudyPair.Status.ACTIVE, now, null);
			repository.savePair(pair);
			UserProfile partner = profiles.findById(candidate.userId()).orElse(null);
			notifyMatched(candidate.userId(), me, pair.goalText());
			if (partner != null) {
				notifyMatched(userId, partner, pair.goalText());
			}
			return new RequestResult(requestId, StudyPairRequest.Status.MATCHED.name(), pair.id());
		}
		repository.saveRequest(new StudyPairRequest(requestId, userId, goalKey, goal, topic,
			StudyPairRequest.Status.OPEN, now));
		return new RequestResult(requestId, StudyPairRequest.Status.OPEN.name(), null);
	}

	/**
	 * Busca de pedidos abertos por objetivo/assunto, com a mesma régua do pareamento.
	 *
	 * O resultado é ANÔNIMO: sai o objetivo e o assunto, não quem pediu. Quem quer o par clica e
	 * entra na fila do mesmo objetivo — o pareamento é que revela as duas pessoas.
	 */
	@Transactional(readOnly = true)
	public List<SearchResult> search(String userId, String rawTerm, int limit) {
		String term = rawTerm == null ? "" : rawTerm.trim().replace("%", "").replace("_", "");
		if (term.length() < 2) {
			return List.of();
		}
		TextFieldLimit.check("STUDY_SEARCH_TOO_LONG", "busca", term, 80);
		Set<String> currentPartners = repository.findActivePairs(userId).stream()
			.map(p -> p.partnerOf(userId))
			.collect(Collectors.toSet());
		return visibleOpen(repository.searchOpenRequests(term, Math.min(50, Math.max(1, limit))), userId,
			currentPartners)
			.stream()
			.map(r -> new SearchResult(r.goalText(), r.topic(), r.createdAt().toString()))
			.toList();
	}

	/** Open requests anyone in {@code userId}'s situation may be paired with, oldest first. */
	private List<StudyPairRequest> eligibleCandidates(String userId, String goalKey, Set<String> currentPartners) {
		return visibleOpen(repository.findOpenByGoal(goalKey, CANDIDATE_SCAN), userId, currentPartners);
	}

	/** Pedidos abertos que a pessoa pode de fato parear: some quem ela bloqueou/mutou e vice-versa. */
	private List<StudyPairRequest> visibleOpen(List<StudyPairRequest> fetched, String userId,
			Set<String> currentPartners) {
		Set<String> hidden = blocks.hiddenIdsFor(userId);
		List<StudyPairRequest> open = fetched.stream()
			.filter(r -> !r.userId().equals(userId))
			.filter(r -> !hidden.contains(r.userId()))
			.filter(r -> !currentPartners.contains(r.userId()))
			.toList();
		if (open.isEmpty()) {
			return open;
		}
		Set<String> ids = open.stream().map(StudyPairRequest::userId).collect(Collectors.toSet());
		Set<String> verified = profiles.findAllByIds(ids).stream()
			.filter(UserProfile::emailVerified)
			.map(UserProfile::id)
			.collect(Collectors.toSet());
		Set<String> minors = agePolicy.minorIds(ids);
		return open.stream()
			.filter(r -> verified.contains(r.userId()))
			.filter(r -> !minors.contains(r.userId()))
			.filter(r -> !sanctions.banned(r.userId()))
			// hiddenIdsFor covers blocks both ways but mutes only from the muter's side: ask the
			// candidate too, so nobody is paired with someone they muted.
			.filter(r -> !blocks.hiddenIdsFor(r.userId()).contains(userId))
			// Turned the module off: the request is paused, not matched, until it is turned back on.
			.filter(r -> !moduleDisabled(r.userId()))
			.filter(r -> repository.findActivePairs(r.userId()).size() < MAX_ACTIVE_PAIRS)
			.toList();
	}

	@Transactional
	public String cancelCurrentRequest(String userId) {
		StudyPairRequest open = repository.findOpenRequest(userId)
			.orElseThrow(() -> new ApiException(404, "STUDY_REQUEST_NOT_FOUND", "Você não tem pedido aberto."));
		if (!repository.closeOpenRequest(open.id(), StudyPairRequest.Status.CANCELLED)) {
			throw new ApiException(409, "STUDY_REQUEST_ALREADY_MATCHED", "Seu pedido acabou de encontrar um par.");
		}
		return open.id();
	}

	@Transactional
	public MyStudyPairs mine(String userId) {
		LocalDate today = today();
		String thisWeek = StudyGoal.weekKey(today);
		List<String> weeks = IntStream.range(0, HISTORY_WEEKS)
			.mapToObj(i -> StudyGoal.weekKey(today.minusWeeks(i)))
			.toList();
		List<StudyPair> pairs = repository.findActivePairs(userId).stream().filter(p -> !closedByBlock(p)).toList();
		Map<String, UserProfile> partners = profiles.findAllByIds(pairs.stream().map(p -> p.partnerOf(userId)).toList())
			.stream().collect(Collectors.toMap(UserProfile::id, Function.identity()));
		List<PairView> views = pairs.stream().map(pair -> {
			String partnerId = pair.partnerOf(userId);
			UserProfile partner = partners.get(partnerId);
			List<StudyCheckin> checkins = repository.findCheckins(pair.id(), weeks);
			return new PairView(pair.id(), pair.goalText(), pair.goalKey(), pair.topic(), pair.startedAt().toString(),
				thisWeek,
				partner == null ? new PartnerView(partnerId, null, null, null)
					: new PartnerView(partner.id(), partner.username(), partner.name(), partner.avatar()),
				checkins.stream().anyMatch(c -> c.userId().equals(userId) && c.weekKey().equals(thisWeek)),
				checkins.stream().anyMatch(c -> c.userId().equals(partnerId) && c.weekKey().equals(thisWeek)),
				checkins.stream().map(c -> new CheckinView(c.userId(), c.weekKey(), c.note(), c.createdAt().toString(),
					c.updatedAt().toString())).toList());
		}).toList();
		OpenRequestView open = repository.findOpenRequest(userId)
			.map(r -> new OpenRequestView(r.id(), r.goalText(), r.goalKey(), r.topic(), r.createdAt().toString()))
			.orElse(null);
		return new MyStudyPairs(open, views);
	}

	/** One check-in per member per ISO week; posting again in the same week edits the note. */
	@Transactional
	public CheckinView checkin(String userId, String pairId, String rawNote) {
		StudyPair pair = memberPair(userId, pairId);
		if (pair.status() != StudyPair.Status.ACTIVE || closedByBlock(pair)) {
			throw new ApiException(409, "STUDY_PAIR_ENDED", "Este par de estudo foi encerrado.");
		}
		sanctionGate.requireNotRestricted(userId);
		String note = rawNote == null ? "" : PlainText.defuse(rawNote.trim());
		TextFieldLimit.check("STUDY_NOTE_TOO_LONG", "nota", note, NOTE_MAX_LENGTH);
		String week = StudyGoal.weekKey(today());
		var now = clock.instant();
		Optional<StudyCheckin> existing = repository.findCheckin(pairId, userId, week);
		if (existing.isPresent()) {
			repository.updateCheckinNote(pairId, userId, week, note, now);
			return new CheckinView(userId, week, note, existing.get().createdAt().toString(), now.toString());
		}
		repository.insertCheckin(new StudyCheckin(pairId, userId, week, note, now, now));
		// Only the first check-in of the week notifies; editing the note stays quiet.
		profiles.findById(userId).ifPresent(me -> notifications.create(pair.partnerOf(userId), "STUDY_PAIR_CHECKIN", userId,
			"Seu par fez o check-in da semana",
			"@" + me.username() + " contou como foi a semana em \"" + pair.goalText() + "\".", HREF,
			"Ver check-in"));
		return new CheckinView(userId, week, note, now.toString(), now.toString());
	}

	@Transactional
	public void end(String userId, String pairId) {
		StudyPair pair = memberPair(userId, pairId);
		if (!repository.endPair(pairId, clock.instant())) {
			throw new ApiException(409, "STUDY_PAIR_ENDED", "Este par de estudo já foi encerrado.");
		}
		profiles.findById(userId).ifPresent(me -> notifications.create(pair.partnerOf(userId), "STUDY_PAIR_ENDED", userId,
			"Um par de estudo foi encerrado",
			"@" + me.username() + " encerrou o par em \"" + pair.goalText() + "\". Obrigado pela companhia nessas "
				+ "semanas. Quando quiser, você pode procurar um novo par.",
			HREF, "Procurar par"));
	}

	/**
	 * Weekly nudge: one notification per member of an ACTIVE pair who has not checked in this week,
	 * at most once per week however many times (or on however many replicas) it runs. Returns how
	 * many were sent.
	 */
	@Transactional
	public int sendWeeklyReminders() {
		String week = StudyGoal.weekKey(today());
		int sent = 0;
		for (StudyPair pair : repository.findAllActivePairs()) {
			if (closedByBlock(pair)) {
				continue;
			}
			Set<String> done = repository.findCheckins(pair.id(), List.of(week)).stream()
				.map(StudyCheckin::userId)
				.collect(Collectors.toSet());
			for (String member : List.of(pair.userA(), pair.userB())) {
				// The claim is the idempotency key: every replica runs the cron, only one row wins.
				if (done.contains(member) || moduleDisabled(member) || !repository.claimReminder(pair.id(), member, week, clock.instant())) {
					continue;
				}
				notifications.create(member, "STUDY_PAIR_REMINDER", "sistema", "Como foi sua semana de estudo?",
					"Um check-in curto já ajuda seu par em \"" + pair.goalText() + "\". Sem pressa.", HREF,
					"Fazer check-in");
				sent++;
			}
		}
		return sent;
	}

	@Transactional(readOnly = true)
	public List<PopularGoal> popularGoals() {
		return repository.popularOpenGoals(POPULAR_LIMIT);
	}

	private StudyPair memberPair(String userId, String pairId) {
		StudyPair pair = repository.findPair(pairId)
			.orElseThrow(() -> new ApiException(404, "STUDY_PAIR_NOT_FOUND", "Par de estudo não encontrado."));
		if (!pair.isMember(userId)) {
			throw new ApiException(403, "FORBIDDEN", "Você não faz parte deste par de estudo.");
		}
		return pair;
	}

	/**
	 * A block or a mute (either direction) after the match ends the pair quietly: no notification,
	 * no more check-ins or reminders. Done here, lazily, so the network module does not depend on
	 * this one.
	 */
	private boolean closedByBlock(StudyPair pair) {
		if (!blocks.hiddenIdsFor(pair.userA()).contains(pair.userB())
				&& !blocks.hiddenIdsFor(pair.userB()).contains(pair.userA())) {
			return false;
		}
		repository.endPair(pair.id(), clock.instant());
		return true;
	}

	private boolean moduleDisabled(String userId) {
		return personalization.of(userId).disabledModules().contains(MODULE);
	}

	private void notifyMatched(String recipientId, UserProfile partner, String goal) {
		notifications.create(recipientId, "STUDY_PAIR_MATCHED", partner.id(), "Você tem um par de estudo",
			"Você e @" + partner.username() + " querem \"" + goal + "\". Um check-in por semana, no ritmo de vocês.",
			HREF, "Ver par");
	}

	private LocalDate today() {
		return LocalDate.now(clock.withZone(ZONE));
	}

	/** Entrada da busca: o objetivo, sem revelar quem pediu. */
	public record SearchResult(String goal, String topic, String createdAt) {
	}

	public record RequestResult(String requestId, String status, String pairId) {
	}

	public record OpenRequestView(String id, String goal, String goalKey, String topic, String createdAt) {
	}

	public record PartnerView(String id, String username, String name, String avatar) {
	}

	public record CheckinView(String userId, String weekKey, String note, String createdAt, String updatedAt) {
	}

	public record PairView(String id, String goal, String goalKey, String topic, String startedAt, String weekKey,
			PartnerView partner, boolean myCheckinThisWeek, boolean partnerCheckinThisWeek,
			List<CheckinView> checkins) {
	}

	public record MyStudyPairs(OpenRequestView openRequest, List<PairView> pairs) {
	}
}
