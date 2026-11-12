package com.socialcoisa.rede.themes.adapters.out.persistence;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.socialcoisa.rede.themes.domain.ThemeGalleryStatus;

interface SpringDataThemeGalleryJpaRepository extends JpaRepository<ThemePackageJpaEntity, String> {

	@org.springframework.data.jpa.repository.Query("""
		select new com.socialcoisa.rede.themes.application.port.out.ThemeIdentity(t.id, t.authorId)
		from ThemePackageJpaEntity t where t.id in :ids
		""")
	List<com.socialcoisa.rede.themes.application.port.out.ThemeIdentity> authorsOf(
		@org.springframework.data.repository.query.Param("ids") java.util.Collection<String> ids);

	List<ThemePackageJpaEntity> findByStatusOrderByCreatedAtDesc(ThemeGalleryStatus status, Pageable pageable);

	List<ThemePackageJpaEntity> findTop50ByAuthorIdOrderByCreatedAtDesc(String authorId);
}
