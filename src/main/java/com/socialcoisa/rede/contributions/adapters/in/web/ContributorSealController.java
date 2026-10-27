package com.socialcoisa.rede.contributions.adapters.in.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.contributions.application.ContributorSealService;

/** Projeção pública dos selos: só evidências validadas, sem notas internas. */
@RestController
class ContributorSealController {

	private final ContributorSealService seals;

	ContributorSealController(ContributorSealService seals) {
		this.seals = seals;
	}

	@GetMapping("/users/{username}/seals")
	List<ContributorSealService.PublicSealView> byUsername(@PathVariable String username) {
		return seals.publicSeals(username);
	}

	@GetMapping("/seals/criteria")
	ContributorSealService.CriteriaView criteria() {
		return seals.criteria();
	}
}
