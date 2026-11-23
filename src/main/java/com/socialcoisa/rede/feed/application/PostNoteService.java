package com.socialcoisa.rede.feed.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.contributions.application.port.out.UserSealRepository;
import com.socialcoisa.rede.feed.application.port.out.PostNoteRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostNote;
import com.socialcoisa.rede.feed.domain.PostNoteStatus;
import com.socialcoisa.rede.profile.application.ContributorTagView;
import com.socialcoisa.rede.profile.application.NameStyleView;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.security.UserRateLimiter;
import com.socialcoisa.rede.shared.text.PlainText;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Nota de rodapé da comunidade: qualquer pessoa (menos o autor do post) envia uma correção; a
 * moderação aprova ou nega. Aprovada aparece no rodapé do post; negada não aparece e pesa na
 * credibilidade de quem enviou — enviar nota verdadeira não muda a credibilidade.
 */
@Service
public class PostNoteService {

	static final int MIN_BODY = 10;
	static final int MAX_BODY = 500;

	private final PostNoteRepository notes;
	private final PostRepository posts;
	private final UserProfileRepository profiles;
	private final UserSealRepository seals;
	private final BlockedUsersReader blockedUsers;
	private final UserRateLimiter rateLimiter;
	private final Clock clock;
	private final com.socialcoisa.rede.shared.birthday.DiaDoNascimento aniversarios;

	public PostNoteService(PostNoteRepository notes, PostRepository posts, UserProfileRepository profiles,
			UserSealRepository seals, BlockedUsersReader blockedUsers, UserRateLimiter rateLimiter, Clock clock) {
		this.notes = notes;
		this.posts = posts;
		this.profiles = profiles;
		this.seals = seals;
		this.blockedUsers = blockedUsers;
		this.rateLimiter = rateLimiter;
		this.clock = clock;
		this.aniversarios = new com.socialcoisa.rede.shared.birthday.DiaDoNascimento(profiles, clock);
	}

	@Transactional
	public PostNote create(String postId, String authorId, String rawBody) {
		Post post = posts.findById(new PostId(postId))
			.filter(Post::isActive)
			.orElseThrow(() -> new ApiException(404, "POST_NOT_FOUND", "Post não encontrado."));
		if (post.authorId().value().equals(authorId)) {
			throw new ApiException(403, "NOTE_ON_OWN_POST",
				"Nota de rodapé é uma correção da comunidade; o autor não pode publicar no próprio post.");
		}
		String body = PlainText.defuse(rawBody == null ? "" : rawBody.trim());
		if (!StringUtils.hasText(body) || body.length() < MIN_BODY) {
			throw new ApiException(400, "NOTE_TOO_SHORT",
				"A nota precisa ter pelo menos " + MIN_BODY + " caracteres.");
		}
		if (body.length() > MAX_BODY) {
			throw new ApiException(400, "NOTE_TOO_LONG",
				"A nota deve ter no máximo " + MAX_BODY + " caracteres.");
		}
		if (notes.existsByPostIdAndStatus(postId, PostNoteStatus.APPROVED)) {
			throw new ApiException(409, "NOTE_ALREADY_APPROVED",
				"Este post já tem uma nota aprovada pela comunidade.");
		}
		rateLimiter.require(authorId, UserRateLimiter.CREATE_COMMENT);
		notes.findByAuthorAndPost(authorId, postId).ifPresent(existing -> {
			throw new ApiException(409, "NOTE_ALREADY_EXISTS",
				existing.status() == PostNoteStatus.PENDING
					? "Sua nota neste post já está em análise."
					: "Você já enviou uma nota para este post.");
		});
		PostNote note = new PostNote("note_" + UUID.randomUUID().toString().replace("-", ""), post.id().value(),
			authorId, body, PostNoteStatus.PENDING, null, null, null, clock.instant());
		return notes.save(note);
	}

	/** Notas aprovadas dos posts da página, já com a apresentação do autor. */
	public Map<String, List<PostNoteView>> viewsByPost(Collection<String> postIds, Set<String> hiddenAuthors) {
		if (postIds == null || postIds.isEmpty()) {
			return Map.of();
		}
		List<PostNote> approved = notes.findApprovedByPostIds(postIds).stream()
			.filter(note -> !hiddenAuthors.contains(note.authorId()))
			.toList();
		if (approved.isEmpty()) {
			return Map.of();
		}
		Set<String> authorIds = approved.stream().map(PostNote::authorId).collect(Collectors.toSet());
		Map<String, UserProfile> profilesById = profiles.findAllByIds(authorIds).stream()
			.collect(Collectors.toMap(UserProfile::id, Function.identity()));
		Set<String> contributors = seals.findActiveUserIds(authorIds);
		Set<String> aniversariantes = aniversarios.aniversariantes(authorIds);
		Map<String, List<PostNoteView>> result = new LinkedHashMap<>();
		for (PostNote note : approved) {
			UserProfile profile = profilesById.get(note.authorId());
			PostNoteView view = new PostNoteView(note.id(), note.body(), note.createdAt(),
				profile != null ? profile.username() : null,
				profile != null ? profile.displayWithNickname() : null,
				profile != null ? profile.avatar() : null,
				profile != null ? profile.allBadges() : List.of(),
				NameStyleView.from(profile),
				contributors.contains(note.authorId()) ? ContributorTagView.from(profile) : null,
				aniversariantes.contains(note.authorId()));
			result.computeIfAbsent(note.postId(), key -> new ArrayList<>()).add(view);
		}
		return result;
	}

	public Map<String, MyPostNoteView> mineByPost(String userId, Collection<String> postIds) {
		if (!StringUtils.hasText(userId) || postIds == null || postIds.isEmpty()) {
			return Map.of();
		}
		Map<String, MyPostNoteView> result = new LinkedHashMap<>();
		for (PostNote note : notes.findByAuthorAndPosts(userId, postIds)) {
			result.put(note.postId(), new MyPostNoteView(note.id(), note.postId(), note.body(), note.status().name(),
				note.reviewReason(), note.createdAt()));
		}
		return result;
	}

	@Transactional
	public PostNote review(String noteId, String reviewerId, PostNoteStatus status, String rawReason) {
		if (status != PostNoteStatus.APPROVED && status != PostNoteStatus.DENIED) {
			throw new ApiException(400, "INVALID_NOTE_STATUS", "Use APPROVED ou DENIED.");
		}
		String reason = rawReason == null ? null : PlainText.defuse(rawReason.trim());
		if (status == PostNoteStatus.DENIED && (!StringUtils.hasText(reason) || reason.length() < 5)) {
			throw new ApiException(400, "NOTE_REASON_REQUIRED",
				"Explique por que a nota foi negada (pelo menos 5 caracteres).");
		}
		if (reason != null && reason.length() > 500) {
			throw new ApiException(400, "NOTE_REASON_TOO_LONG",
				"O motivo deve ter no máximo 500 caracteres.");
		}
		PostNote note = notes.findById(noteId)
			.orElseThrow(() -> new ApiException(404, "NOTE_NOT_FOUND", "Nota não encontrada."));
		if (note.status() != PostNoteStatus.PENDING) {
			throw new ApiException(409, "NOTE_ALREADY_REVIEWED", "Esta nota já foi revisada.");
		}
		Instant now = clock.instant();
		if (status == PostNoteStatus.APPROVED
				&& notes.existsByPostIdAndStatus(note.postId(), PostNoteStatus.APPROVED)) {
			throw new ApiException(409, "NOTE_ALREADY_APPROVED",
				"Este post já tem uma nota aprovada pela comunidade.");
		}
		PostNote saved = notes.save(note.review(status, reviewerId,
			status == PostNoteStatus.DENIED ? reason : null, now));
		if (status == PostNoteStatus.APPROVED) {
			// Só UMA nota vale por post: as outras pendentes são descartadas sem penalidade.
			for (PostNote pending : notes.findPendingByPost(note.postId())) {
				if (!pending.id().equals(note.id())) {
					notes.save(pending.review(PostNoteStatus.DISCARDED, reviewerId, null, now));
				}
			}
		}
		return saved;
	}

	public List<PostNote> pending(int limit) {
		return notes.findByStatus(PostNoteStatus.PENDING, Math.max(1, Math.min(limit, 200)));
	}

	public List<PostNote> pendingForUi(int limit, String userId) {
		return pending(limit).stream()
			.filter(note -> blockedUsers.hiddenIdsFor(userId).stream()
				.noneMatch(hidden -> hidden.equals(note.authorId())))
			.toList();
	}

	public int deniedCount(String userId) {
		return notes.countByAuthorAndStatus(userId, PostNoteStatus.DENIED);
	}

	/** Nota + apresentação do autor para a fila de moderação. */
	public List<AdminNoteView> adminViews(List<PostNote> pending) {
		if (pending.isEmpty()) {
			return List.of();
		}
		Set<String> authorIds = pending.stream().map(PostNote::authorId).collect(Collectors.toSet());
		Map<String, UserProfile> profilesById = profiles.findAllByIds(authorIds).stream()
			.collect(Collectors.toMap(UserProfile::id, Function.identity()));
		return pending.stream().map(note -> {
			UserProfile profile = profilesById.get(note.authorId());
			return new AdminNoteView(note.id(), note.postId(), note.body(), note.status().name(),
				note.createdAt(), note.authorId(), profile != null ? profile.username() : null,
				profile != null ? profile.displayWithNickname() : null);
		}).toList();
	}

	public record AdminNoteView(String id, String postId, String body, String status, Instant createdAt,
			String authorId, String authorUsername, String authorName) {
	}
}