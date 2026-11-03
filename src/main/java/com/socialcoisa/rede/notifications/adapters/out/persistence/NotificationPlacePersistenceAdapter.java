package com.socialcoisa.rede.notifications.adapters.out.persistence;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.notifications.application.port.out.NotificationPlaceRepository;
import com.socialcoisa.rede.notifications.domain.NotificationPlace;

/**
 * Plain SQL over the tables of the communities, feed, groups and events modules (read only, like
 * the weekly digest): one query per kind of target for a whole page, instead of loading each
 * module's aggregates per notification.
 */
@Repository
class NotificationPlacePersistenceAdapter implements NotificationPlaceRepository {

	private final NamedParameterJdbcTemplate jdbc;

	NotificationPlacePersistenceAdapter(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<String, NotificationPlace> communitiesByIds(Collection<String> communityIds) {
		return places("select c.id, c.id, c.name from communities c where c.id in (:keys)", communityIds,
			NotificationPlace.Type.COMMUNITY);
	}

	@Override
	public Map<String, NotificationPlace> communitiesOfPosts(Collection<String> postIds) {
		return places("select p.id, c.id, c.name from posts p join communities c on c.id = p.community_id"
			+ " where p.id in (:keys)", postIds, NotificationPlace.Type.COMMUNITY);
	}

	@Override
	public Map<String, NotificationPlace> groupsBySlugs(Collection<String> slugs) {
		return places("select g.slug, g.id, g.name from groups g where g.slug in (:keys) and g.status <> 'CLOSED'",
			slugs, NotificationPlace.Type.GROUP);
	}

	@Override
	public Map<String, String> eventDatesByIds(Collection<String> eventIds) {
		Map<String, String> dates = new HashMap<>();
		if (!eventIds.isEmpty()) {
			jdbc.query("select e.id, e.date from events e where e.id in (:keys)", Map.of("keys", eventIds),
				(RowCallbackHandler) rs -> dates.put(rs.getString(1), rs.getString(2)));
		}
		return dates;
	}

	/** Rows are {@code [key, placeId, name]}; an empty key set runs no query. */
	private Map<String, NotificationPlace> places(String sql, Collection<String> keys, NotificationPlace.Type type) {
		Map<String, NotificationPlace> places = new HashMap<>();
		if (!keys.isEmpty()) {
			jdbc.query(sql, Map.of("keys", keys), (RowCallbackHandler) rs -> places.put(rs.getString(1),
				new NotificationPlace(type, rs.getString(2), rs.getString(3), null)));
		}
		return places;
	}
}
