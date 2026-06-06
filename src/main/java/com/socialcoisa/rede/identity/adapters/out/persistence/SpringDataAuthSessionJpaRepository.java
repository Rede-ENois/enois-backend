package com.socialcoisa.rede.identity.adapters.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataAuthSessionJpaRepository extends JpaRepository<AuthSessionJpaEntity, String> {
}
