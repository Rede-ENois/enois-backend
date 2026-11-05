package com.socialcoisa.rede.blog.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataBlogJpaRepository extends JpaRepository<BlogJpaEntity, String> {

	Optional<BlogJpaEntity> findByUserId(String userId);

	Optional<BlogJpaEntity> findBySlug(String slug);

	@Query("""
		SELECT b FROM BlogJpaEntity b
		WHERE (:query IS NULL OR LOWER(b.title) LIKE :query ESCAPE '!'
		       OR LOWER(b.description) LIKE :query ESCAPE '!')
		ORDER BY b.createdAt DESC
		""")
	List<BlogJpaEntity> findDirectory(@Param("query") String query, Pageable pageable);
}
