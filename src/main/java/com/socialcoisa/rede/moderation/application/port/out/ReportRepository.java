package com.socialcoisa.rede.moderation.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportStatus;

public interface ReportRepository {

	Report save(Report report);

	Optional<Report> findById(String id);

	List<Report> findByStatus(ReportStatus status, int limit);

	boolean existsByReporterAndTarget(String reporterId, String targetType, String targetId);
}
