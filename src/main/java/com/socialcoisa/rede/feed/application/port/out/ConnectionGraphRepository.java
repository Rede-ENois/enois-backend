package com.socialcoisa.rede.feed.application.port.out;

import java.util.Set;

import com.socialcoisa.rede.feed.domain.UserId;

public interface ConnectionGraphRepository {

	Set<UserId> findFriends(UserId userId);

	Set<UserId> findFriendsOfFriends(UserId userId);
}
