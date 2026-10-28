package com.socialcoisa.rede.cosmetics.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.cosmetics.application.port.out.CosmeticEventEntryRepository;
import com.socialcoisa.rede.cosmetics.application.port.out.CosmeticGrantRepository;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Os eventos grátis da coleção do Saci: o duelo Saci x Halloween (escolha um time, ganhe o emblema
 * do time; o time vencedor ganha o emblema de vitória) e a caça ao Saci (7 esconderijos; quem acha
 * todos ganha o emblema). As janelas vêm da configuração para a QA poder mover.
 */
@Service
public class CosmeticEventService {

	public static final String DUEL = "saci-halloween";
	public static final String HUNT = "caca-saci";
	static final List<String> TEAMS = List.of("saci", "halloween");
	static final List<String> SPOTS = List.of("feed", "vagas", "comunidades", "blog", "carteira", "mensagens", "perfil");

	public record DuelView(boolean active, Instant startsAt, Instant endsAt, Map<String, Long> counts, String myChoice,
			String winner) {
	}

	public record HuntView(boolean active, Instant startsAt, Instant endsAt, int spots, List<String> found,
			boolean completed) {
	}

	/** Inclusive window: active from the first to the last instant. */
	record Window(Instant startsAt, Instant endsAt) {
		static Window of(String start, String end) {
			Window window = new Window(LocalDateTime.parse(start).atZone(CosmeticCatalog.ZONE).toInstant(),
				LocalDateTime.parse(end).atZone(CosmeticCatalog.ZONE).toInstant());
			if (window.endsAt.isBefore(window.startsAt)) {
				throw new IllegalStateException("Event window ends before it starts: " + start + " - " + end);
			}
			return window;
		}

		boolean active(Instant now) {
			return !now.isBefore(startsAt) && !now.isAfter(endsAt);
		}
	}

	private final Window duel;
	private final Window hunt;
	private final CosmeticEventEntryRepository entries;
	private final CosmeticGrantRepository grants;
	private final TransactionTemplate transaction;
	private final Clock clock;

	public CosmeticEventService(
			@Value("${rede.cosmetics.events.duel.starts-at:2026-10-31T00:00:00}") String duelStartsAt,
			@Value("${rede.cosmetics.events.duel.ends-at:2026-10-31T23:59:59}") String duelEndsAt,
			@Value("${rede.cosmetics.events.hunt.starts-at:2026-10-31T00:00:00}") String huntStartsAt,
			@Value("${rede.cosmetics.events.hunt.ends-at:2026-10-31T23:59:59}") String huntEndsAt,
			CosmeticEventEntryRepository entries, CosmeticGrantRepository grants,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.duel = Window.of(duelStartsAt, duelEndsAt);
		this.hunt = Window.of(huntStartsAt, huntEndsAt);
		this.entries = entries;
		this.grants = grants;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/**
	 * The duel state. After the end, the first reads grant the victory badge to the winning team (a
	 * tie makes both teams win); the grant is idempotent, so concurrent readers are harmless.
	 */
	public DuelView duel(String viewerId) {
		Instant now = clock.instant();
		Map<String, Long> counts = new LinkedHashMap<>();
		Map<String, Long> stored = entries.countByChoice(DUEL);
		TEAMS.forEach(team -> counts.put(team, stored.getOrDefault(team, 0L)));
		String winner = null;
		if (now.isAfter(duel.endsAt())) {
			long saci = counts.get("saci");
			long halloween = counts.get("halloween");
			// ponytail: one idempotent insert-select per read after the end; a "granted" flag if reads get hot.
			for (String team : TEAMS) {
				if (counts.get(team) == Math.max(saci, halloween)) {
					grants.grantToChoosers(DUEL, team, "emblema-vitoria-" + team, "evento:" + DUEL, now);
				}
			}
			// A tie has no single winner to report; both teams got the victory badge.
			winner = saci == halloween ? null : saci > halloween ? "saci" : "halloween";
		}
		String myChoice = viewerId == null ? null : entries.choiceOf(DUEL, viewerId).orElse(null);
		return new DuelView(duel.active(now), duel.startsAt(), duel.endsAt(), counts, myChoice, winner);
	}

	/** One choice per person, only while the duel is on; the team badge comes with the choice. */
	public DuelView choose(String userId, String choice) {
		if (choice == null || !TEAMS.contains(choice)) {
			throw new ApiException(400, "INVALID_CHOICE", "Escolha saci ou halloween.");
		}
		Instant now = clock.instant();
		if (!duel.active(now)) {
			throw new ApiException(409, "EVENT_CLOSED", "O duelo não está aberto.");
		}
		if (entries.choiceOf(DUEL, userId).isPresent()) {
			throw new ApiException(409, "ALREADY_CHOSEN", "Você já escolheu um time.");
		}
		try {
			transaction.executeWithoutResult(status -> {
				entries.insert(DUEL, userId, choice, now);
				grants.grant(userId, "emblema-time-" + choice, "evento:" + DUEL, now);
			});
		}
		catch (DuplicateKeyException concurrentChoice) {
			// The primary key settled a concurrent double click: the first choice is the stored one.
			throw new ApiException(409, "ALREADY_CHOSEN", "Você já escolheu um time.");
		}
		return duel(userId);
	}

	public HuntView hunt(String viewerId) {
		List<String> found = viewerId == null ? List.of() : found(viewerId);
		return new HuntView(hunt.active(clock.instant()), hunt.startsAt(), hunt.endsAt(), SPOTS.size(), found,
			found.size() == SPOTS.size());
	}

	/** Marks a spot as found (a repeat is a no-op); the seventh grants the hunt badge. */
	public HuntView find(String userId, String spot) {
		if (!SPOTS.contains(spot)) {
			throw new ApiException(404, "SPOT_NOT_FOUND", "Esconderijo não encontrado.");
		}
		Instant now = clock.instant();
		if (!hunt.active(now)) {
			throw new ApiException(409, "EVENT_CLOSED", "A caça ao Saci não está aberta.");
		}
		try {
			entries.insert(spotEvent(spot), userId, spot, now);
		}
		catch (DuplicateKeyException alreadyFound) {
			// Finding the same spot again changes nothing.
		}
		HuntView view = hunt(userId);
		if (view.completed()) {
			// Checked after every find (not only the 7th insert), so two spots found at once still grant.
			grants.grant(userId, "emblema-achei-saci", "evento:" + HUNT, now);
		}
		return view;
	}

	private List<String> found(String userId) {
		Set<String> eventIds = Set.copyOf(entries.eventIdsOf(userId, SPOTS.stream().map(this::spotEvent).toList()));
		return SPOTS.stream().filter(spot -> eventIds.contains(spotEvent(spot))).toList();
	}

	private String spotEvent(String spot) {
		return HUNT + ":" + spot;
	}
}
