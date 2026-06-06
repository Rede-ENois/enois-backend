package com.socialcoisa.rede.events.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataEventJpaRepository extends JpaRepository<EventJpaEntity, String> {

	List<EventJpaEntity> findAllByOrderByCreatedAtDesc();

	List<EventJpaEntity> findByFormatOrderByCreatedAtDesc(String format);

}
