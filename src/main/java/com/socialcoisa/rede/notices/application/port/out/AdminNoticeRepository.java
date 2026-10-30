package com.socialcoisa.rede.notices.application.port.out;

import java.util.Optional;

import com.socialcoisa.rede.notices.domain.AdminNotice;

/** Persistência do aviso único da plataforma. */
public interface AdminNoticeRepository {

	Optional<AdminNotice> findCurrent();

	void save(AdminNotice notice);

	void clear();
}
