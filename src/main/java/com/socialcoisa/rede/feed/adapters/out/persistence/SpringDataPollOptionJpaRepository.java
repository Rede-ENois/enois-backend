package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPollOptionJpaRepository extends JpaRepository<PollOptionJpaEntity, String> {

	List<PollOptionJpaEntity> findByPostIdInOrderByPositionAsc(Collection<String> postIds);
}
