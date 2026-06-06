package com.socialcoisa.rede.feed.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;

public interface PostRepository {

	Post save(Post post);

	Optional<Post> findById(PostId id);

	List<Post> findByIds(Collection<PostId> ids);

	List<Post> findActiveByAuthors(Set<UserId> authorIds, Instant before, int limit);

	List<Post> findActiveByAuthor(UserId authorId, Instant before, int limit);
}
