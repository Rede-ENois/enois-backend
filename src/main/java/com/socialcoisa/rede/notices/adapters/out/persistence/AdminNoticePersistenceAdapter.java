package com.socialcoisa.rede.notices.adapters.out.persistence;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.notices.application.NoticeService;
import com.socialcoisa.rede.notices.application.port.out.AdminNoticeRepository;
import com.socialcoisa.rede.notices.domain.AdminNotice;

@Repository
class AdminNoticePersistenceAdapter implements AdminNoticeRepository {

	private final SpringDataAdminNoticeJpaRepository repository;

	AdminNoticePersistenceAdapter(SpringDataAdminNoticeJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public Optional<AdminNotice> findCurrent() {
		return repository.findById(NoticeService.NOTICE_ID).map(AdminNoticeJpaEntity::toDomain);
	}

	@Override
	public void save(AdminNotice notice) {
		repository.save(new AdminNoticeJpaEntity(notice));
	}

	@Override
	public void clear() {
		// Sem linha, sem nada a tirar: "não há aviso" e "aviso retirado" são o mesmo estado para a tela.
		if (repository.existsById(NoticeService.NOTICE_ID)) {
			repository.deleteById(NoticeService.NOTICE_ID);
		}
	}
}
