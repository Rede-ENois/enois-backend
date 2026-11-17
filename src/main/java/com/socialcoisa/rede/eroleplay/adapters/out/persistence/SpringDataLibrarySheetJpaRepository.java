package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Busca da biblioteca APENAS por metadados publicáveis (nome do personagem e sistema): a coluna
 * de campos é longtext e o JPQL não aceita {@code lower()} sobre ela — e campo de ficha não é
 * chave de catálogo público.
 */
interface SpringDataLibrarySheetJpaRepository extends JpaRepository<LibrarySheetJpaEntity, String> {

	Optional<LibrarySheetJpaEntity> findBySheetId(String sheetId);

	@Query("""
		SELECT l FROM LibrarySheetJpaEntity l
		WHERE (:before IS NULL OR l.publishedAt < :before)
		  AND (:query IS NULL OR LOWER(l.characterName) LIKE :query ESCAPE '!'
		       OR LOWER(l.gameSystem) LIKE :query ESCAPE '!')
		ORDER BY l.publishedAt DESC""")
	List<LibrarySheetJpaEntity> listLibrary(@Param("query") String query, @Param("before") Instant before,
			Pageable pageable);
}
