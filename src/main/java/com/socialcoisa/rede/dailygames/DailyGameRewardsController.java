package com.socialcoisa.rede.dailygames;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/daily-games/rewards")
public class DailyGameRewardsController {

	public record PrizeTable(List<DailyGameRewardsService.PrizeTier> tiers) {
	}

	@GetMapping("/prizes")
	public PrizeTable prizes() {
		return new PrizeTable(DailyGameRewardsService.tiers());
	}
}
