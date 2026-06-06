package com.socialcoisa.rede.search.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.search.application.port.out.SearchRepository;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class SearchService {

	private final SearchRepository repository;

	private static final List<String> VALID_TYPES = List.of("users", "communities", "jobs", "events", "mentors");

	public SearchService(SearchRepository repository) {
		this.repository = repository;
	}

	public List<SearchResult> search(String query, List<String> types, int limit) {
		if (query == null || query.isBlank()) {
			return List.of();
		}
		List<String> effectiveTypes = types;
		if (effectiveTypes == null || effectiveTypes.isEmpty()) {
			effectiveTypes = VALID_TYPES;
		}
		for (String type : effectiveTypes) {
			if (!VALID_TYPES.contains(type)) {
				throw new ApiException(400, "INVALID_SEARCH_TYPE", "Tipo de busca invalido: " + type);
			}
		}
		List<SearchResult> results = new ArrayList<>();
		String q = "%" + query.toLowerCase() + "%";
		if (effectiveTypes.contains("users")) {
			results.addAll(repository.searchUsers(q, limit));
		}
		if (effectiveTypes.contains("communities")) {
			results.addAll(repository.searchCommunities(q, limit));
		}
		if (effectiveTypes.contains("jobs")) {
			results.addAll(repository.searchJobs(q, limit));
		}
		if (effectiveTypes.contains("events")) {
			results.addAll(repository.searchEvents(q, limit));
		}
		if (effectiveTypes.contains("mentors")) {
			results.addAll(repository.searchMentors(q, limit));
		}
		return results.stream().limit(limit).toList();
	}

	public record SearchResult(String id, String type, String title, String subtitle, String image, String href) {
	}

}
