package com.socialcoisa.rede.feed.adapters.out.network;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.socialcoisa.rede.feed.application.port.out.ConnectionGraphRepository;
import com.socialcoisa.rede.feed.domain.UserId;

public class InMemoryConnectionGraphRepository implements ConnectionGraphRepository {

	private final Map<UserId, Set<UserId>> connections = new ConcurrentHashMap<>();

	@Override
	public Set<UserId> findFriends(UserId userId) {
		return Set.copyOf(connections.getOrDefault(userId, Set.of()));
	}

	@Override
	public Set<UserId> findFriendsOfFriends(UserId userId) {
		Set<UserId> result = new HashSet<>();
		for (UserId friend : findFriends(userId)) {
			result.addAll(findFriends(friend));
		}
		result.remove(userId);
		return result;
	}
}
