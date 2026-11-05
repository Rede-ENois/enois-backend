package com.socialcoisa.rede.search.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.search.application.port.out.SearchRepository;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class SearchService {

	private final SearchRepository repository;
	private final BlockedUsersReader blockedUsersReader;

	private static final List<String> VALID_TYPES = List.of("users", "communities", "jobs", "events", "mentors");

	public SearchService(SearchRepository repository, BlockedUsersReader blockedUsersReader) {
		this.repository = repository;
		this.blockedUsersReader = blockedUsersReader;
	}

	public List<SearchResult> search(String query, List<String> types, int limit) {
		return search(query, types, limit, null);
	}

	/** @param viewerId quem esta buscando (nulo sem sessao); usado para esconder bloqueados. */
	public List<SearchResult> search(String query, List<String> types, int limit, String viewerId) {
		if (query == null || query.isBlank()) {
			return List.of();
		}
		// O limite era repassado as cinco consultas sem teto; ?limit=1000 virava 1000 linhas de
		// cada tipo. A busca e uma vitrine: 50 resultados por tipo ja e mais do que a tela usa.
		limit = Math.clamp(limit, 1, 50);
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
		String q = query.trim();
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
		Set<String> hidden = blockedUsersReader.hiddenIdsFor(viewerId);
		return results.stream()
			.filter(result -> !hidden.contains(result.id()))
			.limit(limit)
			.toList();
	}

	public record SearchResult(String id, String type, String title, String subtitle, String image, String href) {
	}

}
