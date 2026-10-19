package com.socialcoisa.rede.dailygames;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fecha o dia dos jogos diários e paga as faíscas. Uma rodada por dia, no fuso de Brasília;
 * os dias que venceram com a aplicação fora do ar entram na primeira rodada seguinte.
 */
@Component
public class DailyGameRewardsScheduler {

	/** Dias para trás que a rodada varre (o dia de ontem ainda aceita retomada, então começa em -2). */
	private static final int LOOKBACK_DAYS = 7;

	private final DailyGameRewardsService rewards;

	public DailyGameRewardsScheduler(DailyGameRewardsService rewards) {
		this.rewards = rewards;
	}

	@Scheduled(cron = "0 20 0 * * *", zone = "America/Sao_Paulo")
	public void payClosedDays() {
		int paid = rewards.settleClosedDays(LOOKBACK_DAYS);
		if (paid > 0) {
			System.out.println("[JOGOS][PREMIO] creditados " + paid + " prêmios de jogos diários");
		}
	}
}
