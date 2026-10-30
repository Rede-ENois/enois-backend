package com.socialcoisa.rede.blog.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.socialcoisa.rede.blog.domain.BlogPost;

public interface BlogPostRepository {

	BlogPost save(BlogPost post);

	Optional<BlogPost> findById(String id);

	List<BlogPost> findByBlog(String blogId, Instant before, int limit);

	int countByBlog(String blogId);

	/** Post count per blog for a list, in one query; a blog without posts is absent from the map. */
	Map<String, Integer> countByBlogs(Collection<String> blogIds);

	void delete(String id);
}
