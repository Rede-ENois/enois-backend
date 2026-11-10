package com.socialcoisa.rede.themes.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.themes.domain.ThemeGalleryStatus;
import com.socialcoisa.rede.themes.domain.ThemePackage;

public interface ThemeGalleryRepository {

	ThemePackage save(ThemePackage theme);

	Optional<ThemePackage> findById(String id);

	/** Os temas destes ids, em uma consulta (os ausentes simplesmente não voltam). */
	List<ThemePackage> findAllByIds(Collection<String> ids);

	/** Autor de vários temas, em uma consulta e sem o package_json do tema. */
	List<ThemeIdentity> authorsOf(Collection<String> ids);

	List<ThemePackage> findPublished(int limit);

	List<ThemePackage> findByAuthor(String authorId, int limit);
}
