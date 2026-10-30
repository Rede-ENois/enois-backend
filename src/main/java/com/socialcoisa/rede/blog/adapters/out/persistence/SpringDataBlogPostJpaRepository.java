package com.socialcoisa.rede.blog.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataBlogPostJpaRepository extends JpaRepository<BlogPostJpaEntity, String> {

	@Query("SELECT p FROM BlogPostJpaEntity p WHERE p.blogId = :blogId AND (:before IS NULL OR p.publishedAt < :before)"
		+ " ORDER BY p.publishedAt DESC")
	List<BlogPostJpaEntity> findByBlogBefore(@Param("blogId") String blogId, @Param("before") Instant before,
			Pageable pageable);

	int countByBlogId(String blogId);

	@Query("SELECT p.blogId, COUNT(p) FROM BlogPostJpaEntity p WHERE p.blogId IN :blogIds GROUP BY p.blogId")
	List<Object[]> countGroupedByBlog(@Param("blogIds") Collection<String> blogIds);
}
