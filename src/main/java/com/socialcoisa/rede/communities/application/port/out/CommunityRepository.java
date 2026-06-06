package com.socialcoisa.rede.communities.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.communities.domain.Community;
import com.socialcoisa.rede.communities.domain.CommunityMember;

public interface CommunityRepository {

	void save(Community community);

	Optional<Community> findById(String id);

	List<Community> findAll(String topic, int limit);

	void saveMember(CommunityMember member);

	void removeMember(String communityId, String userId);

	boolean isMember(String communityId, String userId);

	int countMembers(String communityId);

	List<CommunityMember> findMembersByCommunity(String communityId);

}
