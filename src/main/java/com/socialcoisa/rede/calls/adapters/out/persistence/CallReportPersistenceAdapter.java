package com.socialcoisa.rede.calls.adapters.out.persistence;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.calls.application.port.out.CallReportRepository;
import com.socialcoisa.rede.calls.domain.CallReportContext;

@Repository
class CallReportPersistenceAdapter implements CallReportRepository {

	private final SpringDataCallReportJpaRepository reports;

	CallReportPersistenceAdapter(SpringDataCallReportJpaRepository reports) {
		this.reports = reports;
	}

	@Override
	public void save(CallReportContext context) {
		reports.save(CallReportJpaEntity.from(context));
	}

	@Override
	public Optional<CallReportContext> findByReportId(String reportId) {
		return reports.findById(reportId).map(CallReportJpaEntity::toDomain);
	}

	@Override
	public boolean exists(String reporterId, String callId, String reportedUserId) {
		return reports.countByReporter(reporterId, callId, reportedUserId) > 0;
	}
}
