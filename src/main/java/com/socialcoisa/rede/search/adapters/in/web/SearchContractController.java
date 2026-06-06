package com.socialcoisa.rede.search.adapters.in.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.shared.web.PageResponse;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

@RestController
@RequestMapping("/api/search")
class SearchContractController {

	@GetMapping
	@RateLimiter(name = "search")
	PageResponse<SearchResultView> search(@RequestParam(required = false) String q,
			@RequestParam(required = false) List<String> types, @RequestParam(defaultValue = "10") int limit) {
		return new PageResponse<>(List.of(), null);
	}

	record SearchResultView(String id, String type, String title, String subtitle, String image, String href) {
	}
}
