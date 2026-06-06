package com.socialcoisa.rede.network.adapters.out.graph;

import java.util.Set;

import com.socialcoisa.rede.feed.application.port.out.ConnectionGraphRepository;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.network.application.NetworkService;

public class JpaConnectionGraphRepository implements ConnectionGraphRepository {

	private final NetworkService networkService;

	public JpaConnectionGraphRepository(NetworkService networkService) {
		this.networkService = networkService;
	}

	@Override
	public Set<UserId> findFriends(UserId userId) {
		return networkService.findFriends(userId);
	}

	@Override
	public Set<UserId> findFriendsOfFriends(UserId userId) {
		return networkService.findFriendsOfFriends(userId);
	}

}
