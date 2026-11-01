package com.socialcoisa.rede.releasenotes.adapters.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataReleaseNoteJpaRepository extends JpaRepository<ReleaseNoteJpaEntity, String> {
}
