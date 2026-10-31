package com.socialcoisa.rede.blog.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.blog.domain.Blog;

public interface BlogRepository {

	Blog save(Blog blog);

	Optional<Blog> findById(String id);

	Optional<Blog> findByUserId(String userId);

	Optional<Blog> findBySlug(String slug);

	/** Vitrine de blogs (mais recentes primeiro), para a aba Blogs. */
	List<Blog> findAll(String query, int limit);
}
