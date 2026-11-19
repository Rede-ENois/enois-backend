package com.socialcoisa.rede.feed.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.contributions.application.port.out.UserSealRepository;
import com.socialcoisa.rede.feed.application.port.out.CommentRepository;
import com.socialcoisa.rede.feed.application.port.out.OutboxEventRepository;
import com.socialcoisa.rede.feed.application.port.out.PostInteractionRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Comment;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.identity.application.port.out.IdentityVerificationRepository;
import com.socialcoisa.rede.media.application.port.in.ValidateMediaOwnershipUseCase;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.age.AgeGate;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.risk.RiskEngine;
import com.socialcoisa.rede.shared.sanctions.SanctionGate;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.shared.security.BehavioralTimingGuard;
import com.socialcoisa.rede.shared.security.ContentDuplicationGuard;
import com.socialcoisa.rede.shared.security.NewAccountCooldown;
import com.socialcoisa.rede.shared.security.UserRateLimiter;
import com.socialcoisa.rede.shared.web.CursorCodec;

class PostInteractionServiceCommentFallbackTest {

@Test
void getCommentsWhenAuthorProfileMissingUsesUsuarioAndNullUsername() {
PostRepository postRepository = mock(PostRepository.class);
CommentRepository commentRepository = mock(CommentRepository.class);
PostInteractionRepository postInteractionRepository = mock(PostInteractionRepository.class);
OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
CursorCodec cursorCodec = mock(CursorCodec.class);
Clock clock = Clock.fixed(Instant.parse("2026-11-19T08:00:00Z"), ZoneOffset.UTC);
NotificationService notificationService = mock(NotificationService.class);
UserProfileRepository userProfileRepository = mock(UserProfileRepository.class);
UserSealRepository userSeals = mock(UserSealRepository.class);
BlockedUsersReader blockedUsersReader = mock(BlockedUsersReader.class);
ValidateMediaOwnershipUseCase validateMediaOwnership = mock(ValidateMediaOwnershipUseCase.class);
SanctionGate sanctionGate = mock(SanctionGate.class);
		SanctionGateReader sanctionGateReader = mock(SanctionGateReader.class);
RiskEngine riskEngine = mock(RiskEngine.class);
CoinService coins = mock(CoinService.class);
NewAccountCooldown newAccountCooldown = mock(NewAccountCooldown.class);
UserRateLimiter userRateLimiter = mock(UserRateLimiter.class);
ContentDuplicationGuard duplicationGuard = mock(ContentDuplicationGuard.class);
BehavioralTimingGuard behavioralTimingGuard = mock(BehavioralTimingGuard.class);
AgeGate agePolicy = mock(AgeGate.class);
IdentityVerificationRepository identityVerifications = mock(IdentityVerificationRepository.class);

PostInteractionService service = new PostInteractionService(
postRepository, commentRepository, postInteractionRepository, outboxEventRepository,
cursorCodec, clock, notificationService, userProfileRepository, userSeals,
blockedUsersReader, validateMediaOwnership, sanctionGate, sanctionGateReader, riskEngine, coins,
newAccountCooldown, userRateLimiter, duplicationGuard, behavioralTimingGuard,
agePolicy, identityVerifications
);

String marker = UUID.randomUUID().toString().replace("-", "");
PostId postId = new PostId("post_" + marker);
String missingAuthorId = "user_missing_" + marker;
Comment comment = Comment.create(
"comment_" + marker,
postId,
new UserId(missingAuthorId),
null,
"Comentario de autor removido " + marker,
List.of(),
false,
clock.instant()
);

Post activePost = mock(Post.class);
		when(activePost.isActive()).thenReturn(true);
		when(activePost.acceptedCommentId()).thenReturn(null);

when(postRepository.findById(postId)).thenReturn(Optional.of(activePost));
when(commentRepository.findPinnedByPost(postId)).thenReturn(Optional.empty());
when(commentRepository.findActiveByPostExcludingPinned(postId, Instant.parse("9999-12-31T23:59:59Z"), 20))
.thenReturn(List.of(comment));
		when(sanctionGateReader.bannedAmong(anySet())).thenReturn(Set.of());
when(userSeals.findActiveUserIds(anySet())).thenReturn(Set.of());
when(identityVerifications.verifiedAmong(anySet())).thenReturn(Set.of(missingAuthorId));
when(userProfileRepository.findAllByIds(anyCollection())).thenReturn(List.of());
when(postInteractionRepository.countCommentReactionsByType(anyList(), anySet())).thenReturn(Map.of());
when(cursorCodec.encode(any())).thenReturn("cursor");

var page = service.getComments(new GetCommentsQuery(postId, Optional.empty(), 20, Optional.empty()));

assertEquals(1, page.items().size());
var item = page.items().get(0);
assertEquals("Usuário", item.authorName());
assertNull(item.authorUsername());
assertFalse(item.authorHumanityVerified());
}
}
