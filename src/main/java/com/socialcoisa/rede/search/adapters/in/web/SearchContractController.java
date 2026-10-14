package com.socialcoisa.rede.search.adapters.in.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.search.application.SearchService;
import com.socialcoisa.rede.search.application.SearchService.SearchResult;
import com.socialcoisa.rede.shared.web.PageResponse;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

@RestController
@RequestMapping("/search")
class SearchContractController {

	private final SearchService searchService;

	SearchContractController(SearchService searchService) {
		this.searchService = searchService;
	}

	@GetMapping
	@RateLimiter(name = "search")
	PageResponse<SearchResultView> search(@RequestParam(required = false) String q,
			@RequestParam(required = false) List<String> types, @RequestParam(defaultValue = "10") int limit) {
		List<SearchResult> results = searchService.search(q, types, limit);
		var items = results.stream()
			.map(r -> new SearchResultView(r.id(), r.type(), r.title(), r.subtitle(), r.image(), r.href()))
			.toList();
		return new PageResponse<>(items, null);
	}

	record SearchResultView(String id, String type, String title, String subtitle, String image, String href) {
	}

}
