package com.socialcoisa.rede.search.adapters.out.persistence;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.search.application.SearchService.SearchResult;
import com.socialcoisa.rede.search.application.port.out.SearchRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

@Repository
class SearchPersistenceAdapter implements SearchRepository {

	private final EntityManager em;

	SearchPersistenceAdapter(EntityManager em) {
		this.em = em;
	}

	@Override
	public List<SearchResult> searchUsers(String query, int limit) {
		String raw = query.replace("%", "");
		Query q = em.createNativeQuery("""
				SELECT id, name, headline, avatar, username,
				  (CASE WHEN LOWER(name) = :exact THEN 10
				        WHEN LOWER(name) LIKE :prefix THEN 5
				        WHEN LOWER(name) LIKE :q THEN 1 ELSE 0 END
				   + CASE WHEN LOWER(headline) LIKE :q THEN 1 ELSE 0 END
				   + CASE WHEN LOWER(username) = :exact THEN 8 ELSE 0 END) as relevance
				FROM users
				WHERE LOWER(name) LIKE :q OR LOWER(username) LIKE :q OR LOWER(headline) LIKE :q
				ORDER BY relevance DESC, name
				LIMIT :limit""");
		q.setParameter("q", query);
		q.setParameter("prefix", raw + "%");
		q.setParameter("exact", raw);
		q.setParameter("limit", limit);
		return mapUserResults(q.getResultList());
	}

	@Override
	public List<SearchResult> searchCommunities(String query, int limit) {
		String raw = query.replace("%", "");
		Query q = em.createNativeQuery("""
				SELECT id, name, description, cover,
				  (CASE WHEN LOWER(name) = :exact THEN 10
				        WHEN LOWER(name) LIKE :prefix THEN 5
				        WHEN LOWER(name) LIKE :q THEN 1 ELSE 0 END
				   + CASE WHEN LOWER(topic) LIKE :q THEN 1 ELSE 0 END) as relevance
				FROM communities
				WHERE LOWER(name) LIKE :q OR LOWER(description) LIKE :q OR LOWER(topic) LIKE :q
				ORDER BY relevance DESC, name
				LIMIT :limit""");
		q.setParameter("q", query);
		q.setParameter("prefix", raw + "%");
		q.setParameter("exact", raw);
		q.setParameter("limit", limit);
		return mapResults(q.getResultList(), "communities", "/api/communities/");
	}

	@Override
	public List<SearchResult> searchJobs(String query, int limit) {
		String raw = query.replace("%", "");
		Query q = em.createNativeQuery("""
				SELECT id, title, company, logo,
				  (CASE WHEN LOWER(title) = :exact THEN 10
				        WHEN LOWER(title) LIKE :prefix THEN 5
				        WHEN LOWER(title) LIKE :q THEN 1 ELSE 0 END
				   + CASE WHEN LOWER(company) LIKE :prefix THEN 3
				          WHEN LOWER(company) LIKE :q THEN 1 ELSE 0 END) as relevance
				FROM jobs
				WHERE LOWER(title) LIKE :q OR LOWER(company) LIKE :q
				ORDER BY relevance DESC, title
				LIMIT :limit""");
		q.setParameter("q", query);
		q.setParameter("prefix", raw + "%");
		q.setParameter("exact", raw);
		q.setParameter("limit", limit);
		return mapResults(q.getResultList(), "jobs", "/api/jobs/");
	}

	@Override
	public List<SearchResult> searchEvents(String query, int limit) {
		String raw = query.replace("%", "");
		Query q = em.createNativeQuery("""
				SELECT id, title, host, cover,
				  (CASE WHEN LOWER(title) = :exact THEN 10
				        WHEN LOWER(title) LIKE :prefix THEN 5
				        WHEN LOWER(title) LIKE :q THEN 1 ELSE 0 END
				   + CASE WHEN LOWER(host) LIKE :prefix THEN 3
				          WHEN LOWER(host) LIKE :q THEN 1 ELSE 0 END) as relevance
				FROM events
				WHERE LOWER(title) LIKE :q OR LOWER(host) LIKE :q
				ORDER BY relevance DESC, title
				LIMIT :limit""");
		q.setParameter("q", query);
		q.setParameter("prefix", raw + "%");
		q.setParameter("exact", raw);
		q.setParameter("limit", limit);
		return mapResults(q.getResultList(), "events", "/api/events/");
	}

	@Override
	public List<SearchResult> searchMentors(String query, int limit) {
		String raw = query.replace("%", "");
		Query q = em.createNativeQuery("""
				SELECT m.id, u.name, u.headline, u.avatar,
				  (CASE WHEN LOWER(u.name) = :exact THEN 10
				        WHEN LOWER(u.name) LIKE :prefix THEN 5
				        WHEN LOWER(u.name) LIKE :q THEN 1 ELSE 0 END
				   + CASE WHEN LOWER(m.expertise) LIKE :q THEN 1 ELSE 0 END) as relevance
				FROM mentors m
				JOIN users u ON u.id = m.user_id
				WHERE LOWER(u.name) LIKE :q OR LOWER(m.expertise) LIKE :q
				ORDER BY relevance DESC, u.name
				LIMIT :limit""");
		q.setParameter("q", query);
		q.setParameter("prefix", raw + "%");
		q.setParameter("exact", raw);
		q.setParameter("limit", limit);
		return mapResults(q.getResultList(), "mentors", "/api/mentors/");
	}

	@SuppressWarnings("unchecked")
	private List<SearchResult> mapResults(List<Object[]> rows, String type, String hrefPrefix) {
		return rows.stream()
			.map(row -> new SearchResult(
				(String) row[0],
				type,
				(String) row[1],
				(String) row[2],
				(String) row[3],
				hrefPrefix + row[0]))
			.toList();
	}

	@SuppressWarnings("unchecked")
	private List<SearchResult> mapUserResults(List<Object[]> rows) {
		return rows.stream()
			.map(row -> new SearchResult(
				(String) row[0],
				"users",
				(String) row[1],
				(String) row[2],
				(String) row[3],
				"/api/users/" + row[4]))
			.toList();
	}

}
