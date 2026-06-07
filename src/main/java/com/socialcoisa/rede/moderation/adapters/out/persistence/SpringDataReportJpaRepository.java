package com.socialcoisa.rede.moderation.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialcoisa.rede.moderation.domain.ReportStatus;

interface SpringDataReportJpaRepository extends JpaRepository<ReportJpaEntity, String> {

	List<ReportJpaEntity> findByStatusOrderByCreatedAtDesc(ReportStatus status);

	boolean existsByReporterIdAndTargetTypeAndTargetId(String reporterId, String targetType, String targetId);
}
