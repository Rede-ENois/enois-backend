package com.socialcoisa.rede.dailygames;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.coin.domain.RewardEligibility;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O prêmio em faísca dos jogos diários.
 *
 * Paga quando o dia fecha, nunca na hora da vitória: enquanto alguém pode subir de posição,
 * o placar não é definitivo. Como o desafio de ontem aceita retomada durante hoje, o dia só
 * fecha depois de amanhã — ver {@link #settleClosedDays(int)}.
 *
 * Cada crédito usa a referência `jogos:<jogo>:<dia>:<conta>`: o ledger da faísca é idempotente
 * por referência, então rodar o fechamento quantas vezes forem não duplica prêmio nem notificação.
 */
@Service
public class DailyGameRewardsService {

	private static final Logger LOG = LoggerFactory.getLogger(DailyGameRewardsService.class);

	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	public static final long PRIZE_TOP1 = CoinService.FAIS(10L);
	public static final long PRIZE_PODIUM = CoinService.FAIS(5L);
	public static final long PRIZE_TOP10 = CoinService.FAIS(3L);
	public static final long PRIZE_COMPLETION = CoinService.FAIS(1L);

	public record PrizeTier(String label, long amount) {
	}

	/** Faixa do placar (1º em diante) → faíscas. Quem não completou não entra na fila. */
	public static long prizeFor(int rank) {
		if (rank == 1) {
			return PRIZE_TOP1;
		}
		if (rank <= 3) {
			return PRIZE_PODIUM;
		}
		if (rank <= 10) {
			return PRIZE_TOP10;
		}
		return PRIZE_COMPLETION;
	}

	public static List<PrizeTier> tiers() {
		return List.of(
			new PrizeTier("1º lugar", PRIZE_TOP1),
			new PrizeTier("2º ao 3º", PRIZE_PODIUM),
			new PrizeTier("4º ao 10º", PRIZE_TOP10),
			new PrizeTier("Demais que completaram", PRIZE_COMPLETION));
	}

	private record Game(String key, String label, Function<LocalDate, List<String>> order) {
	}

	/** Attempts per credit when the ledger reports a concurrent movement or a lock timeout. */
	static final int MAX_ATTEMPTS = 3;

	private static final long RETRY_BACKOFF_MS = 50;

	private final CoinService coins;
	private final NotificationService notifications;
	private final UserProfileRepository profiles;
	private final Clock clock;
	private final TransactionTemplate creditTransaction;
	private final TransactionTemplate readTransaction;
	private final List<Game> games;

	public DailyGameRewardsService(CoinService coins, NotificationService notifications, UserProfileRepository profiles,
			Clock clock, PlatformTransactionManager transactionManager, DailyWordService word, DailyMazeService maze,
			DailyBlocksService blocks) {
		this.coins = coins;
		this.notifications = notifications;
		this.profiles = profiles;
		this.clock = clock;
		this.creditTransaction = new TransactionTemplate(transactionManager);
		this.creditTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.readTransaction = new TransactionTemplate(transactionManager);
		this.readTransaction.setReadOnly(true);
		this.games = List.of(
			new Game("palavras", "Palavras", word::payoutOrder),
			new Game("labirinto", "Labirinto", maze::payoutOrder),
			new Game("blocos", "Blocos", blocks::payoutOrder));
	}

	public LocalDate today() {
		return LocalDate.now(clock.withZone(ZONE));
	}

	/**
	 * Closes and pays the closed days up to `lookbackDays` ago. The lookback catches days that
	 * closed while the application was down; idempotency makes the repetition harmless, and a
	 * credit that failed in one run is paid by the next.
	 */
	public int settleClosedDays(int lookbackDays) {
		LocalDate today = today();
		int paid = 0;
		for (int ago = 2; ago <= lookbackDays; ago++) {
			paid += settle(today.minusDays(ago));
		}
		return paid;
	}

	/**
	 * Pays one day's ranking in the three games and returns how many new credits were made.
	 *
	 * Each credit (ledger entries + notification) commits on its own: a failure on one account
	 * leaves everybody else paid. Ranks are counted among the eligible accounts only, so the
	 * prize of an ineligible account goes to the next person instead of disappearing.
	 */
	public int settle(LocalDate day) {
		int paid = 0;
		for (Game game : games) {
			List<String> ranked = eligible(game.order().apply(day), day);
			for (int i = 0; i < ranked.size(); i++) {
				if (grantWithRetry(game, day, ranked.get(i), i + 1)) {
					paid++;
				}
			}
		}
		return paid;
	}

	/**
	 * Same rule as the other farmable rewards: verified identity and an account that existed for
	 * the whole new-account window before the challenge day started, so the answer does not change
	 * between runs and accounts created on the day never qualify.
	 */
	private List<String> eligible(List<String> ranked, LocalDate day) {
		if (ranked.isEmpty()) {
			return ranked;
		}
		Instant dayStart = day.atStartOfDay(ZONE).toInstant();
		Set<String> allowed = readTransaction.execute(status -> profiles.findAllByIds(ranked).stream()
			.filter(profile -> profile.identityVerified()
				&& RewardEligibility.isEstablished(profile.createdAt(), dayStart))
			.map(UserProfile::id)
			.collect(Collectors.toSet()));
		return ranked.stream().filter(allowed::contains).toList();
	}

	private boolean grantWithRetry(Game game, LocalDate day, String userId, int rank) {
		for (int attempt = 1;; attempt++) {
			try {
				return Boolean.TRUE.equals(creditTransaction.execute(status -> grant(game, day, userId, rank)));
			}
			catch (RuntimeException failure) {
				if (!isTransient(failure) || attempt >= MAX_ATTEMPTS) {
					LOG.warn("daily_game_reward_failed game={} day={} rank={} attempts={} error={}", game.key(), day, rank,
						attempt, failure instanceof ApiException api ? api.code() : failure.getClass().getSimpleName());
					return false;
				}
				pause(RETRY_BACKOFF_MS * attempt);
			}
		}
	}

	private static boolean isTransient(RuntimeException failure) {
		return failure instanceof ApiException api ? "COIN_CONCURRENCY".equals(api.code())
			: failure instanceof TransientDataAccessException;
	}

	private static void pause(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private boolean grant(Game game, LocalDate day, String userId, int rank) {
		String ref = "jogos:" + game.key() + ":" + day + ":" + userId;
		if (coins.hasReward(ref)) {
			return false;
		}
		long amount = coins.scaledReward(prizeFor(rank));
		coins.reward(userId, amount, ref, "Jogo diário " + game.label() + " de " + day + " (" + rank + "º lugar)");
		notifications.create(userId, "JOGO_DIARIO", "", "Faíscas do jogo diário",
			"Você ficou em " + rank + "º lugar em " + game.label() + " de " + day + " e ganhou "
				+ formatFaiscas(amount) + " faíscas.",
			"/carteira", "Ver carteira");
		return true;
	}

	/** O prêmio pode vir fracionado (a escala pela cotação); mostra sem casas à toa. */
	private static String formatFaiscas(long mf) {
		return java.math.BigDecimal.valueOf(mf, 3).stripTrailingZeros().toPlainString();
	}
}
