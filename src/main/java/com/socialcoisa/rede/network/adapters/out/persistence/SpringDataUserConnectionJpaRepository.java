package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

	/**
	 * UPDATE direto: dois "remover conexão" simultâneos não podem falhar com
	 * "Record has changed since last read" (o segundo update encontrava a linha já alterada).
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE UserConnectionJpaEntity c
			SET c.status = :status, c.removedAt = :removedAt
			WHERE c.id = :id""")
	int markRemoved(@Param("id") Long id, @Param("status") ConnectionStatus status,
			@Param("removedAt") Instant removedAt);

	/**
	 * Aceite condicional: só uma chamada concorrente encontra a linha PENDING. A que perder a
	 * corrida recebe 0 e não repete a notificação.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE UserConnectionJpaEntity c
			SET c.status = 'ACCEPTED', c.acceptedAt = :acceptedAt
			WHERE c.id = :id AND c.status = 'PENDING'""")
	int markAccepted(@Param("id") Long id, @Param("acceptedAt") Instant acceptedAt);

	List<UserConnectionJpaEntity> findByRequesterUserIdAndStatus(String requesterUserId, ConnectionStatus status);

	List<UserConnectionJpaEntity> findByReceiverUserIdAndStatus(String receiverUserId, ConnectionStatus status);

	List<UserConnectionJpaEntity> findByRequesterUserIdAndStatusOrReceiverUserIdAndStatus(
		String requesterUserId, ConnectionStatus requesterStatus,
		String receiverUserId, ConnectionStatus receiverStatus);

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
			SELECT c.receiverUserId FROM UserConnectionJpaEntity c
			WHERE c.requesterUserId = :userId AND c.status = 'ACCEPTED' AND c.receiverUserId IN :others
			UNION
			SELECT c.requesterUserId FROM UserConnectionJpaEntity c
			WHERE c.receiverUserId = :userId AND c.status = 'ACCEPTED' AND c.requesterUserId IN :others""")
	List<String> findAcceptedAmong(@Param("userId") String userId,
			@Param("others") java.util.Collection<String> others);

	/** One keyset page of accepted connections on either side of {@code userId}, newest first. */
	@Query("""
			SELECT c FROM UserConnectionJpaEntity c
			WHERE (c.requesterUserId = :userId OR c.receiverUserId = :userId)
			  AND c.status = 'ACCEPTED'
			  AND (:beforeId IS NULL OR c.id < :beforeId)
			ORDER BY c.id DESC""")
	List<UserConnectionJpaEntity> findAcceptedPage(@Param("userId") String userId, @Param("beforeId") Long beforeId,
			Pageable page);

	/** One keyset page of accepted connections received by {@code userId} (its followers), newest first. */
	@Query("""
			SELECT c FROM UserConnectionJpaEntity c
			WHERE c.receiverUserId = :userId
			  AND c.status = 'ACCEPTED'
			  AND (:beforeId IS NULL OR c.id < :beforeId)
			ORDER BY c.id DESC""")
	List<UserConnectionJpaEntity> findAcceptedReceivedPage(@Param("userId") String userId,
			@Param("beforeId") Long beforeId, Pageable page);

	@Query("""
			SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END
			FROM UserConnectionJpaEntity c
			WHERE ((c.requesterUserId = :user1 AND c.receiverUserId = :user2)
			   OR (c.requesterUserId = :user2 AND c.receiverUserId = :user1))
			  AND c.status = 'ACCEPTED'""")
	boolean existsAcceptedConnection(@Param("user1") String user1, @Param("user2") String user2);

}
