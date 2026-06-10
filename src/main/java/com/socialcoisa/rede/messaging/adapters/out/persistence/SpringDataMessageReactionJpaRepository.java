package com.socialcoisa.rede.messaging.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataMessageReactionJpaRepository extends JpaRepository<MessageReactionJpaEntity, String> {

	Optional<MessageReactionJpaEntity> findByMessageIdAndUserId(String messageId, String userId);

	List<MessageReactionJpaEntity> findByMessageIdOrderByCreatedAtDesc(String messageId);
}
