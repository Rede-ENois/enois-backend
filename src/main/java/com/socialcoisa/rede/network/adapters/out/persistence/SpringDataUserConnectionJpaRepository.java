package com.socialcoisa.rede.network.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.network.domain.ConnectionStatus;

interface SpringDataUserConnectionJpaRepository extends JpaRepository<UserConnectionJpaEntity, Long> {

	@Query("""
			SELECT c FROM UserConnectionJpaEntity c
			WHERE (c.requesterUserId = :user1 AND c.receiverUserId = :user2)
			   OR (c.requesterUserId = :user2 AND c.receiverUserId = :user1)
			ORDER BY c.createdAt DESC
			LIMIT 1""")
	Optional<UserConnectionJpaEntity> findBetweenUsers(@Param("user1") String user1, @Param("user2") String user2);

	List<UserConnectionJpaEntity> findByRequesterUserIdAndStatus(String requesterUserId, ConnectionStatus status);

	List<UserConnectionJpaEntity> findByReceiverUserIdAndStatus(String receiverUserId, ConnectionStatus status);

	int countByRequesterUserIdAndStatus(String requesterUserId, ConnectionStatus status);

	int countByReceiverUserIdAndStatus(String receiverUserId, ConnectionStatus status);

	@Query("""
			SELECT c.receiverUserId FROM UserConnectionJpaEntity c
			WHERE c.requesterUserId = :userId AND c.status = 'ACCEPTED'
			UNION
			SELECT c.requesterUserId FROM UserConnectionJpaEntity c
			WHERE c.receiverUserId = :userId AND c.status = 'ACCEPTED'""")
	List<String> findAcceptedFriendIds(@Param("userId") String userId);

	@Query("""
			SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END
			FROM UserConnectionJpaEntity c
			WHERE ((c.requesterUserId = :user1 AND c.receiverUserId = :user2)
			   OR (c.requesterUserId = :user2 AND c.receiverUserId = :user1))
			  AND c.status IN ('PENDING', 'ACCEPTED')""")
	boolean existsActiveConnection(@Param("user1") String user1, @Param("user2") String user2);

}
