package com.socialcoisa.rede.dailygames;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.notifications.application.NotificationService;

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

	private final CoinService coins;
	private final NotificationService notifications;
	private final Clock clock;
	private final List<Game> games;

	public DailyGameRewardsService(CoinService coins, NotificationService notifications, Clock clock,
			DailyWordService word, DailyMazeService maze, DailyBlocksService blocks) {
		this.coins = coins;
		this.notifications = notifications;
		this.clock = clock;
		this.games = List.of(
			new Game("palavras", "Palavras", word::payoutOrder),
			new Game("labirinto", "Labirinto", maze::payoutOrder),
			new Game("blocos", "Blocos", blocks::payoutOrder));
	}

	public LocalDate today() {
		return LocalDate.now(clock.withZone(ZONE));
	}

	/**
	 * Fecha e paga os dias encerrados até `lookbackDays` atrás. O recuo existe para o job pegar
	 * os dias que venceram com a aplicação fora do ar; a idempotência torna a repetição inofensiva.
	 * Transacional aqui porque o `settle` é chamado por auto-invocação e não passaria pelo proxy.
	 */
	@Transactional
	public int settleClosedDays(int lookbackDays) {
		LocalDate today = today();
		int paid = 0;
		for (int ago = 2; ago <= lookbackDays; ago++) {
			paid += settle(today.minusDays(ago));
		}
		return paid;
	}

	/** Paga o placar de um dia nos três jogos. Devolve quantos créditos novos entraram. */
	@Transactional
	public int settle(LocalDate day) {
		int paid = 0;
		for (Game game : games) {
			List<String> ranked = game.order().apply(day);
			for (int i = 0; i < ranked.size(); i++) {
				if (grant(game, day, ranked.get(i), i + 1)) {
					paid++;
				}
			}
		}
		return paid;
	}

	private boolean grant(Game game, LocalDate day, String userId, int rank) {
		String ref = "jogos:" + game.key() + ":" + day + ":" + userId;
		if (coins.hasReward(ref)) {
			return false;
		}
		long amount = prizeFor(rank);
		coins.reward(userId, amount, ref, "Jogo diário " + game.label() + " de " + day + " (" + rank + "º lugar)");
		notifications.create(userId, "JOGO_DIARIO", "", "Faíscas do jogo diário",
			"Você ficou em " + rank + "º lugar em " + game.label() + " de " + day + " e ganhou "
				+ (amount / CoinService.SCALE) + " faíscas.",
			"/carteira", "Ver carteira");
		return true;
	}
}
