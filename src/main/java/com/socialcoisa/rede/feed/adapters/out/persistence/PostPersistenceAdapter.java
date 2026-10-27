package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.AuthorIdentity;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostStatus;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.profile.application.port.out.SocialPostCountReader;

@Repository
class PostPersistenceAdapter implements PostRepository, SocialPostCountReader {

	private final SpringDataPostJpaRepository repository;

	PostPersistenceAdapter(SpringDataPostJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public Post save(Post post) {
		return repository.save(PostJpaEntity.fromDomain(post)).toDomain();
	}

	@Override
	public List<Post> findExpiredMoments(Instant now, int limit) {
		return repository.findByTypeAndStatusAndExpiresAtBefore(PostType.MOMENT, PostStatus.ACTIVE, now).stream()
			.limit(Math.max(1, limit))
			.map(PostJpaEntity::toDomain).toList();
	}

	@Override
	public Optional<Post> findById(PostId id) {
		return repository.findById(id.value()).map(PostJpaEntity::toDomain);
	}

	@Override
	public List<Post> findByIds(Collection<PostId> ids) {
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		return repository.findByIdIn(ids.stream().map(PostId::value).toList()).stream()
			.map(PostJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<Post> findAllByAuthor(UserId authorId) {
		return repository.findByAuthorId(authorId.value()).stream()
			.map(PostJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<Post> findActiveByAuthors(Set<UserId> authorIds, Instant before, int limit) {
		if (authorIds == null || authorIds.isEmpty()) {
			return List.of();
		}
		// Para você/Seguindo: o que a identidade SOCIAL assinou — BUSINESS assina o feed corporativo.
		return repository.findSocialByAuthorIds(authorIds.stream().map(UserId::value).toList(), PostStatus.ACTIVE,
			AuthorIdentity.SOCIAL, before, PageRequest.of(0, limit)).stream()
			.map(PostJpaEntity::toDomain)
			.toList();
	}

	@Override
	public java.util.Optional<Post> findActiveRepostByAuthorAndSource(UserId authorId, String sourcePostId) {
		return repository.findRepostByAuthorAndSource(authorId.value(), sourcePostId,
			com.socialcoisa.rede.feed.domain.PostStatus.ACTIVE).map(PostJpaEntity::toDomain);
	}

	@Override
	public List<Post> findActiveRepostsBySource(String sourcePostId) {
		return repository.findRepostsBySource(sourcePostId,
			com.socialcoisa.rede.feed.domain.PostStatus.ACTIVE).stream()
			.map(PostJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<Post> findActiveByAuthor(UserId authorId, Instant before, int limit) {
		// Ponteiro de blog fica fora do perfil (o conteudo dele mora no blog) e o que a identidade
		// profissional assinou fica fora do perfil SOCIAL (fica na pagina profissional).
		return repository.findSocialByAuthor(authorId.value(), PostStatus.ACTIVE, AuthorIdentity.SOCIAL,
			PostType.BLOG, before, PageRequest.of(0, limit)).stream()
			.map(PostJpaEntity::toDomain)
			.toList();
	}

	@Override
	public int countActiveByAuthor(UserId authorId) {
		return (int) repository.countSocialByAuthor(authorId.value(), PostStatus.ACTIVE, AuthorIdentity.SOCIAL,
			PostType.BLOG);
	}

	@Override
	public int countByUser(String userId) {
		return countActiveByAuthor(new UserId(userId));
	}

	@Override
	public List<Post> findActiveCorporateByAuthor(UserId authorId, Instant before, int limit) {
		return repository.findCorporateByAuthor(authorId.value(), PostStatus.ACTIVE, AuthorIdentity.CORPORATE,
			before, PageRequest.of(0, limit)).stream()
			.map(PostJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<Post> findActiveCorporate(Instant before, int limit) {
		return repository.findCorporateByStatus(PostStatus.ACTIVE, AuthorIdentity.CORPORATE, before,
			PageRequest.of(0, limit)).stream()
			.map(PostJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<Post> findTrending(Instant since, int limit) {
		return repository.findTrending(PostStatus.ACTIVE, List.of(PostType.BLOG, PostType.BUSINESS), since,
			PageRequest.of(0, limit)).stream()
			.map(PostJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<Post> findActiveByTypes(Set<PostType> types, Instant before, int limit) {
		return repository.findByTypes(PostStatus.ACTIVE, types, before, PageRequest.of(0, limit)).stream()
			.map(PostJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<Post> findActiveAllExcludingTopics(Collection<String> blockedTopics, Instant before, int limit) {
		return repository.findFeedExcludingAndBlocking(PostStatus.ACTIVE, List.of(PostType.BLOG, PostType.BUSINESS),
			blockedTopics == null || blockedTopics.isEmpty() ? List.of("") : blockedTopics, before,
			PageRequest.of(0, limit)).stream()
			.map(PostJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<Post> findActiveByAuthorsExcludingTopics(Set<UserId> authorIds, Collection<String> blockedTopics,
			Instant before, int limit) {
		if (authorIds == null || authorIds.isEmpty()) {
			return List.of();
		}
		return repository.findSocialByAuthorIdsAndBlocking(authorIds.stream().map(UserId::value).toList(),
			PostStatus.ACTIVE, AuthorIdentity.SOCIAL,
			blockedTopics == null || blockedTopics.isEmpty() ? List.of("") : blockedTopics, before,
			PageRequest.of(0, limit)).stream()
			.map(PostJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<Post> findActiveAll(Instant before, int limit) {
		// "Tudo" nunca mostra post de blog (quem quer acompanhar se inscreve) nem BUSINESS (assina
		// o feed corporativo): uma unica consulta com as exclusoes, nada de filtrar fora.
		return repository.findFeedExcluding(PostStatus.ACTIVE, List.of(PostType.BLOG, PostType.BUSINESS), before,
			PageRequest.of(0, limit)).stream()
			.map(PostJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<Post> findActiveByChannel(String channelId, Instant before, int limit) {
		if (channelId == null || channelId.isBlank()) {
			return List.of();
		}
		return repository.findByChannelIdAndStatusAndCreatedAtBeforeOrderByCreatedAtDesc(channelId.trim(),
				PostStatus.ACTIVE, before, PageRequest.of(0, limit)).stream()
			.map(PostJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<Post> findActiveByType(PostType type, Instant before, int limit) {
		return repository.findByTypeAndStatusAndChannelIdIsNullAndCreatedAtBeforeOrderByCreatedAtDesc(type, PostStatus.ACTIVE, before,
			PageRequest.of(0, limit)).stream()
			.map(PostJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<Post> findActiveByCommunity(String communityId, Instant before, int limit) {
		if (communityId == null || communityId.isBlank()) {
			return List.of();
		}
		return repository.findByCommunityIdAndStatusAndChannelIdIsNullAndCreatedAtBeforeOrderByCreatedAtDesc(communityId.trim(),
			PostStatus.ACTIVE, before, PageRequest.of(0, limit)).stream()
			.map(PostJpaEntity::toDomain)
			.toList();
	}
}
