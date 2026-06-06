package com.socialcoisa.rede.search.application.port.out;

import java.util.List;

import com.socialcoisa.rede.search.application.SearchService.SearchResult;

public interface SearchRepository {

	List<SearchResult> searchUsers(String query, int limit);

	List<SearchResult> searchCommunities(String query, int limit);

	List<SearchResult> searchJobs(String query, int limit);

	List<SearchResult> searchEvents(String query, int limit);

	List<SearchResult> searchMentors(String query, int limit);

}
