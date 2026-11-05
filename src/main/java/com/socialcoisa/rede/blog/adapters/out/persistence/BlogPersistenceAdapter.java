package com.socialcoisa.rede.blog.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.blog.application.port.out.BlogRepository;
import com.socialcoisa.rede.blog.domain.Blog;

@Repository
class BlogPersistenceAdapter implements BlogRepository {

	private final SpringDataBlogJpaRepository springData;

	BlogPersistenceAdapter(SpringDataBlogJpaRepository springData) {
		this.springData = springData;
	}

	@Override
	public Blog save(Blog blog) {
		return springData.save(BlogJpaEntity.fromDomain(blog)).toDomain();
	}

	@Override
	public Optional<Blog> findById(String id) {
		return springData.findById(id).map(BlogJpaEntity::toDomain);
	}

	@Override
	public Optional<Blog> findByUserId(String userId) {
		return springData.findByUserId(userId).map(BlogJpaEntity::toDomain);
	}

	@Override
	public Optional<Blog> findBySlug(String slug) {
		return springData.findBySlug(slug).map(BlogJpaEntity::toDomain);
	}

	@Override
	public List<Blog> findAll(String query, int limit) {
		String like = com.socialcoisa.rede.shared.web.LikePattern.contains(query);
		return springData.findDirectory(like, PageRequest.of(0, limit)).stream()
			.map(BlogJpaEntity::toDomain)
			.toList();
	}
}
