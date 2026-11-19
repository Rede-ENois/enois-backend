package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPollConfigJpaRepository extends JpaRepository<PollConfigJpaEntity, String> {

	List<PollConfigJpaEntity> findByPostIdIn(Collection<String> postIds);
}
