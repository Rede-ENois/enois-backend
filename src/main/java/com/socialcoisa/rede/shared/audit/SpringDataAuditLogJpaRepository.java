package com.socialcoisa.rede.shared.audit;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataAuditLogJpaRepository extends JpaRepository<AuditLogJpaEntity, String> {
}
