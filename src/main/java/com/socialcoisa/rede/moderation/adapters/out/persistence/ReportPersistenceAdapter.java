package com.socialcoisa.rede.moderation.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.moderation.application.port.out.ReportRepository;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportStatus;

@Repository
class ReportPersistenceAdapter implements ReportRepository {

	private final SpringDataReportJpaRepository jpaRepository;

	ReportPersistenceAdapter(SpringDataReportJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	@Override
	public Report save(Report report) {
		return jpaRepository.save(ReportJpaEntity.fromDomain(report)).toDomain();
	}

	@Override
	public Optional<Report> findById(String id) {
		return jpaRepository.findById(id).map(ReportJpaEntity::toDomain);
	}

	@Override
	public List<Report> findByStatus(ReportStatus status, int limit) {
		return jpaRepository.findByStatusOrderByCreatedAtDesc(status).stream()
			.map(ReportJpaEntity::toDomain)
			.limit(limit)
			.toList();
	}

	@Override
	public boolean existsByReporterAndTarget(String reporterId, String targetType, String targetId) {
		return jpaRepository.existsByReporterIdAndTargetTypeAndTargetId(reporterId, targetType, targetId);
	}
}
