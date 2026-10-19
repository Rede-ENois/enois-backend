package com.socialcoisa.rede.feed.application;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.feed.application.port.in.PublishPostUseCase;
import com.socialcoisa.rede.feed.application.port.out.ArticleRepository;
import com.socialcoisa.rede.feed.application.port.out.IdGenerator;
import com.socialcoisa.rede.feed.application.port.out.PostCommunityReader;
import com.socialcoisa.rede.feed.application.port.out.OutboxEventRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.media.application.port.in.ValidateMediaOwnershipUseCase;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.html.SafeHtml;
import com.socialcoisa.rede.shared.text.PlainText;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class PublishPostService implements PublishPostUseCase {

	/** Teto do texto do artigo — maior que o do post (1.200) porque artigo e outro formato. */
	static final int MAX_ARTICLE_BODY = 4_000;

	private final PostRepository postRepository;
	private final ArticleRepository articleRepository;
	private final IdGenerator idGenerator;
	private final Clock clock;
	private final FeedDistributionService feedDistributionService;
	private final OutboxEventRepository outboxEventRepository;
	private final ValidateMediaOwnershipUseCase validateMediaOwnershipUseCase;
	private final NotificationService notificationService;
	private final com.socialcoisa.rede.profile.application.port.out.UserProfileRepository userProfileRepository;
	private final com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate;
	private final PostCommunityReader postCommunityReader;

	public PublishPostService(PostRepository postRepository, ArticleRepository articleRepository,
			IdGenerator idGenerator, Clock clock,
			FeedDistributionService feedDistributionService, OutboxEventRepository outboxEventRepository,
			ValidateMediaOwnershipUseCase validateMediaOwnershipUseCase,
			NotificationService notificationService,
			com.socialcoisa.rede.profile.application.port.out.UserProfileRepository userProfileRepository,
			com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate,
			PostCommunityReader postCommunityReader) {
		this.postRepository = postRepository;
		this.articleRepository = articleRepository;
		this.idGenerator = idGenerator;
		this.clock = clock;
		this.feedDistributionService = feedDistributionService;
		this.outboxEventRepository = outboxEventRepository;
		this.validateMediaOwnershipUseCase = validateMediaOwnershipUseCase;
		this.notificationService = notificationService;
		this.userProfileRepository = userProfileRepository;
		this.sanctionGate = sanctionGate;
		this.postCommunityReader = postCommunityReader;
	}

	@Override
	@Transactional
	public PostView publish(PublishPostCommand command) {
		if (sanctionGate.restricted(command.authorId().value())) {
			throw new ApiException(403, "ACCOUNT_RESTRICTED",
				"Sua conta esta restrita por moderacao. Voce pode recorrer na pagina Penalidades.");
		}
		validate(command);
		List<String> tags = command.tags() == null ? List.of() : command.tags().stream()
			.filter(StringUtils::hasText)
			.map(String::trim)
			.distinct()
			.toList();
		List<String> mediaIds = command.mediaIds() == null ? List.of() : command.mediaIds().stream()
			.filter(StringUtils::hasText)
			.map(String::trim)
			.distinct()
			.toList();
		validateMediaOwnershipUseCase.validateOwnedBy(command.authorId(), mediaIds);
		String communityId = StringUtils.hasText(command.communityId()) ? command.communityId().trim() : null;
		if (communityId != null) {
			// Publicar numa comunidade e escrever no feed dela: sem ser membro, qualquer pessoa
			// logada colocaria post na vitrine dos outros (e o post nao aparece para quem participa).
			PostCommunityReader.CommunityRef community = postCommunityReader
				.byIds(List.of(communityId), command.authorId().value()).get(communityId);
			if (community == null) {
				throw new ApiException(404, "COMMUNITY_NOT_FOUND", "Comunidade nao encontrada.");
			}
			if (!community.joined()) {
				throw new ApiException(403, "NOT_A_MEMBER", "Entre na comunidade para publicar nela.");
			}
		}
		Post post = Post.publish(idGenerator.newPostId(), command.authorId(), command.type(), textOf(command),
			pageHtml(command), tags, mediaIds, communityId, clock.instant());
		Post saved = postRepository.save(post);
		String articleBody = articleBody(command);
		if (articleBody != null) {
			// Corpo do artigo em tabela propria: nao pesa na linha que o feed le e nao viaja na
			// listagem (ver PostView.from). Mesma transacao do post: ou nasce inteiro, ou nao nasce.
			articleRepository.save(saved.id(), articleBody, clock.instant());
		}
		feedDistributionService.distribute(saved);
		outboxEventRepository.append("PostPublished", saved.id().value(), "{\"postId\":\"" + saved.id().value() + "\"}");
		notifyMentions(command.mentionedUserIds(), command.authorId().value(), saved.id().value(), "post");
		UserProfile author = userProfileRepository.findById(command.authorId().value()).orElse(null);
		if (author != null) {
			return PostView.from(saved, author.username(), author.name(), author.avatar(), author.allBadges()).withAvatarFrame(author.avatarFrame());
		}
		return PostView.from(saved);
	}

	private void notifyMentions(List<String> mentionedUserIds, String actorId, String postId, String kind) {
		if (mentionedUserIds == null) return;
		for (String mentionedId : mentionedUserIds.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList()) {
			if (mentionedId.equals(actorId)) continue;
			if (userProfileRepository.findById(mentionedId).isEmpty()) continue;
			String title = "Menção em " + ("post".equals(kind) ? "publicação" : "comentário");
			String href = "/api/feed/posts/" + postId;
			notificationService.create(mentionedId, "MENTION", actorId, title,
				"Você foi mencionado em um " + ("post".equals(kind) ? "publicação" : "comentário") + ".",
				href, "Ver");
		}
	}

	/**
	 * Texto do post. Num post comum ele e obrigatorio; num post de pagina e opcional, porque pagina
	 * e uma coisa OU outra: o HTML e o conteudo, e o texto (quando vem) e so um titulo acima dele.
	 * A coluna e `not null`, entao a ausencia vira string vazia — nunca nulo.
	 */
	private String textOf(PublishPostCommand command) {
		if (command.type() == PostType.PAGE) {
			return StringUtils.hasText(command.content()) ? command.content().trim() : "";
		}
		// Texto do post passa pela defesa contra texto patologico (ver PlainText): limite de tamanho
		// nao impede bidi/zero-width/repeticoes gigantes dentro do limite.
		return PlainText.defuse(command.content().trim());
	}

	/**
	 * Corpo do artigo: so existe em ARTICLE (nos outros tipos o campo e ignorado, para nao virar
	 * lixo guardado), e o limite e maior que o do post justamente porque artigo e outra coisa.
	 * `posts.content` continua sendo o titulo/resumo que aparece no card.
	 */
	private String articleBody(PublishPostCommand command) {
		if (command.type() != PostType.ARTICLE) {
			return null;
		}
		return validateArticleBody(command.articleBody());
	}

	/** Regra do texto do artigo, compartilhada com a edicao (mesmo limite, mesma mensagem). */
	static String validateArticleBody(String raw) {
		if (!StringUtils.hasText(raw)) {
			throw new ApiException(400, "INVALID_ARTICLE_BODY", "Artigo precisa do texto do artigo.");
		}
		String body = raw.trim();
		if (body.length() > MAX_ARTICLE_BODY) {
			throw new ApiException(400, "ARTICLE_BODY_TOO_LONG",
				"Texto do artigo deve ter no maximo " + MAX_ARTICLE_BODY + " caracteres.");
		}
		return body;
	}

	/**
	 * Pagina so existe em posts do tipo PAGE; nos outros o HTML e ignorado (nao vira lixo guardado).
	 */
	private String pageHtml(PublishPostCommand command) {
		if (command.type() != PostType.PAGE) {
			return null;
		}
		return SafeHtml.requirePageHtml(command.pageHtml());
	}

	private void validate(PublishPostCommand command) {
		if (command.type() == null) {
			throw new ApiException(400, "INVALID_POST_TYPE", "Tipo de post obrigatorio.");
		}
		// Post de pagina e uma coisa OU outra: o HTML e obrigatorio e o texto nao (ver textOf).
		if (command.type() != PostType.PAGE && !StringUtils.hasText(command.content())) {
			throw new ApiException(400, "INVALID_CONTENT", "Conteudo obrigatorio.");
		}
		int limite = com.socialcoisa.rede.feed.domain.PostContentLimits.of(command.type());
		if (command.content() != null && command.content().trim().length() > limite) {
			throw new ApiException(400, "CONTENT_TOO_LONG",
				"Este tipo de post aceita no maximo " + limite + " caracteres.");
		}
		if (command.tags() != null && command.tags().stream().filter(StringUtils::hasText).distinct().count() > 5) {
			throw new ApiException(400, "TOO_MANY_TAGS", "Post deve ter no maximo 5 tags.");
		}
		if (command.mediaIds() != null && command.mediaIds().stream().filter(StringUtils::hasText).distinct().count() > 5) {
			throw new ApiException(400, "TOO_MANY_MEDIA", "Post deve ter no maximo 5 midias.");
		}
	}
}
