package com.socialcoisa.rede.profile.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.profile.domain.UserProfile;

public interface UserProfileRepository {

	UserProfile save(UserProfile profile);

	Optional<UserProfile> findById(String id);

	List<UserProfile> findAllByIds(Collection<String> ids);

	Optional<UserProfile> findByUsername(String username);

	Optional<UserProfile> findByEmail(String email);

	boolean existsByUsername(String username);

	boolean existsByEmail(String email);

	int countConnections(String userId);

	int countFollowers(String userId);

	int countPostInteractionsReceived(String userId);

	int countCommunitiesJoined(String userId);

	int countEventsAttended(String userId);

	int countMentoringSessionsAsMentee(String userId);

	int countApprovedReports(String userId);

	List<UserProfile> findTopByReputation(int limit);
}
