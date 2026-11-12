package com.socialcoisa.rede.themes.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.themes.application.port.out.ThemeGalleryRepository;
import com.socialcoisa.rede.themes.domain.ThemeGalleryStatus;
import com.socialcoisa.rede.themes.domain.ThemePackage;

@Repository
class ThemeGalleryPersistenceAdapter implements ThemeGalleryRepository {

	private final SpringDataThemeGalleryJpaRepository themes;

	ThemeGalleryPersistenceAdapter(SpringDataThemeGalleryJpaRepository themes) {
		this.themes = themes;
	}

	@Override
	public ThemePackage save(ThemePackage theme) {
		return themes.save(ThemePackageJpaEntity.fromDomain(theme)).toDomain();
	}

	@Override
	public Optional<ThemePackage> findById(String id) {
		return themes.findById(id).map(ThemePackageJpaEntity::toDomain);
	}

	@Override
	public List<ThemePackage> findAllByIds(Collection<String> ids) {
		return themes.findAllById(ids).stream().map(ThemePackageJpaEntity::toDomain).toList();
	}

	@Override
	public List<com.socialcoisa.rede.themes.application.port.out.ThemeIdentity> authorsOf(Collection<String> ids) {
		return themes.authorsOf(ids);
	}

	@Override
	public List<ThemePackage> findPublished(int limit) {
		return themes.findByStatusOrderByCreatedAtDesc(ThemeGalleryStatus.PUBLISHED,
			PageRequest.of(0, Math.max(1, limit))).stream().map(ThemePackageJpaEntity::toDomain).toList();
	}

	@Override
	public List<ThemePackage> findByAuthor(String authorId, int limit) {
		return themes.findTop50ByAuthorIdOrderByCreatedAtDesc(authorId).stream()
			.map(ThemePackageJpaEntity::toDomain).toList();
	}
}
