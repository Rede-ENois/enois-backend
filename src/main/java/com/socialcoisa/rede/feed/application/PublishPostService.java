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
import com.socialcoisa.rede.feed.domain.AuthorIdentity;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.media.application.port.in.ValidateMediaOwnershipUseCase;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.ContributorTagView;
import com.socialcoisa.rede.profile.application.NameStyleView;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.html.SafeHtml;
import com.socialcoisa.rede.shared.text.PlainText;
import com.socialcoisa.rede.shared.security.BehavioralTimingGuard;
import com.socialcoisa.rede.shared.security.ContentDuplicationGuard;
import com.socialcoisa.rede.shared.security.NewAccountCooldown;
import com.socialcoisa.rede.shared.security.UserRateLimiter;
import com.socialcoisa.rede.shared.security.UserRateLimiter;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class PublishPostService implements PublishPostUseCase {

	/** Teto do texto do artigo — maior que o do post (1.200) porque artigo e outro formato. */
	static final int MAX_ARTICLE_BODY = 10_000;

	private final PostRepository postRepository;
	private final ArticleRepository articleRepository;
	private final IdGenerator idGenerator;
	private final Clock clock;
	private final FeedDistributionService feedDistributionService;
	private final OutboxEventRepository outboxEventRepository;
	private final ValidateMediaOwnershipUseCase validateMediaOwnershipUseCase;
	private final NotificationService notificationService;
	private final com.socialcoisa.rede.profile.application.port.out.UserProfileRepository userProfileRepository;
	private final com.socialcoisa.rede.contributions.application.port.out.UserSealRepository userSeals;
	private final com.socialcoisa.rede.shared.sanctions.SanctionGate sanctionGate;
	private final com.socialcoisa.rede.shared.risk.RiskEngine riskEngine;
	private final com.socialcoisa.rede.shared.age.AgeGate agePolicy;
	private final PostCommunityReader postCommunityReader;
	private final com.socialcoisa.rede.feed.application.port.out.CommunityChannelReader communityChannelReader;
	private final com.socialcoisa.rede.corporate.application.port.out.CorporateProfileRepository corporateProfiles;
	/** Avisa quem escuta fora daqui (automod de spam), DEPOIS do commit. */
	private final org.springframework.context.ApplicationEventPublisher events;
	/** Cooldown de conta nova (PR #29): segura o flood inicial sem punir quem já tem história. */
	private final NewAccountCooldown newAccountCooldown;
	/** Rate limit por usuário e dedup de conteúdo (PR #28). */
	private final UserRateLimiter userRateLimiter;
	private final ContentDuplicationGuard duplicationGuard;
	/** Detecção comportamental de automação (PR #36). */
	private final BehavioralTimingGuard behavioralTimingGuard;

	public PublishPostService(PostRepository postRepository, ArticleRepository articleRepository,
			IdGenerator idGenerator, Clock clock,
			FeedDistributionService feedDistributionService, OutboxEventRepository outboxEventRepository,
			ValidateMediaOwnershipUseCase validateMediaOwnershipUseCase,
			NotificationService notificationService,
			com.socialcoisa.rede.profile.application.port.out.UserProfileRepository userProfileRepository,
			com.socialcoisa.rede.contributions.application.port.out.UserSealRepository userSeals,
			com.socialcoisa.rede.shared.sanctions.SanctionGate sanctionGate,
			com.socialcoisa.rede.shared.risk.RiskEngine riskEngine,
			com.socialcoisa.rede.shared.age.AgeGate agePolicy,
			PostCommunityReader postCommunityReader,
			com.socialcoisa.rede.feed.application.port.out.CommunityChannelReader communityChannelReader,
			com.socialcoisa.rede.corporate.application.port.out.CorporateProfileRepository corporateProfiles,
			org.springframework.context.ApplicationEventPublisher events,
			NewAccountCooldown newAccountCooldown,
			UserRateLimiter userRateLimiter,
			ContentDuplicationGuard duplicationGuard,
			BehavioralTimingGuard behavioralTimingGuard) {
		this.postRepository = postRepository;
		this.articleRepository = articleRepository;
		this.idGenerator = idGenerator;
		this.clock = clock;
		this.feedDistributionService = feedDistributionService;
		this.outboxEventRepository = outboxEventRepository;
		this.validateMediaOwnershipUseCase = validateMediaOwnershipUseCase;
		this.notificationService = notificationService;
		this.userProfileRepository = userProfileRepository;
		this.userSeals = userSeals;
		this.sanctionGate = sanctionGate;
		this.riskEngine = riskEngine;
		this.agePolicy = agePolicy;
		this.postCommunityReader = postCommunityReader;
		this.communityChannelReader = communityChannelReader;
		this.corporateProfiles = corporateProfiles;
		this.events = events;
		this.newAccountCooldown = newAccountCooldown;
		this.userRateLimiter = userRateLimiter;
		this.duplicationGuard = duplicationGuard;
		this.behavioralTimingGuard = behavioralTimingGuard;
	}

	@Override
	@Transactional
	public PostView publish(PublishPostCommand command) {
		// ECA Digital: sem data de nascimento declarada, nenhuma publicacao sai (tela de regularizacao).
		agePolicy.requireCanWrite(command.authorId().value());
		sanctionGate.requireNotRestricted(command.authorId().value());
		newAccountCooldown.require(command.authorId().value());
		userRateLimiter.require(command.authorId().value(), UserRateLimiter.CREATE_POST);
		behavioralTimingGuard.record(command.authorId().value());
		// Motor de risco (plano mestre, item 7): avalia e registra o risco desta escrita. Em modo
		// de observacao ele nao bloqueia nada — so devolve a linha de base que faltava.
		riskEngine.assess(command.authorId().value(), "publish", command.content());
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
		// Regra do plano: BUSINESS assina o feed corporativo — não nasce dentro de comunidade
		// (a tabela de separação de feeds vale no backend, não só na tela).
		AuthorIdentity authorIdentity = command.type() == PostType.BUSINESS
			? requireActiveCorporateProfile(command.authorId().value())
			: AuthorIdentity.SOCIAL;
		if (command.type() == PostType.BUSINESS && communityId != null) {
			throw new ApiException(400, "BUSINESS_NOT_IN_COMMUNITY",
				"Publicação corporativa não vai para comunidades. Publique no seu feed corporativo.");
		}
		String topic;
		PostCommunityReader.CommunityRef community = null;
		if (communityId != null) {
			// Publicar numa comunidade e escrever no feed dela: sem ser membro, qualquer pessoa
			// logada colocaria post na vitrine dos outros (e o post nao aparece para quem participa).
			community = postCommunityReader.byIds(List.of(communityId), command.authorId().value()).get(communityId);
			if (community == null) {
				throw new ApiException(404, "COMMUNITY_NOT_FOUND", "Comunidade nao encontrada.");
			}
			if (!community.joined()) {
				throw new ApiException(403, "NOT_A_MEMBER", "Entre na comunidade para publicar nela.");
			}
			// Com canal, quem responde pelo assunto é a regra do canal (mensagem mais precisa).
			if (!StringUtils.hasText(command.channelId()) && StringUtils.hasText(command.topic())) {
				throw new ApiException(400, "TOPIC_FROM_COMMUNITY",
					"Post em comunidade herda o assunto dela — o compositor não escolhe assunto aqui.");
			}
		}
		// Canal privado: só existe dentro de comunidade e o TEMA vem do canal (o compositor não
		// escolhe assunto). O acesso continua sendo o da comunidade; o post não entra em feed.
		String channelId = StringUtils.hasText(command.channelId()) ? command.channelId().trim() : null;
		String channelTopic = null;
		if (channelId != null) {
			if (communityId == null) {
				throw new ApiException(400, "CHANNEL_REQUIRES_COMMUNITY",
					"Canal só existe dentro de uma comunidade.");
			}
			if (StringUtils.hasText(command.topic())) {
				throw new ApiException(400, "TOPIC_FROM_CHANNEL",
					"Post de canal herda o tema do canal — o compositor não escolhe assunto aqui.");
			}
			var channel = communityChannelReader.byId(channelId)
				.orElseThrow(() -> new ApiException(404, "CHANNEL_NOT_FOUND", "Canal não encontrado."));
			if (!channel.communityId().equals(communityId)) {
				throw new ApiException(404, "CHANNEL_NOT_FOUND", "Canal não encontrado nesta comunidade.");
			}
			channelTopic = channel.topic();
		}
		// Classificação REAL do assunto: em comunidade, o tópico é o DELA (declarado por ela).
		// Post avulso nasce com o assunto que o AUTOR declarou no compositor (opcional, validado
		// contra a taxonomia). Sem assunto, sem classificação — nunca é bloqueado por preferência.
		if (channelId != null) {
			topic = channelTopic != null ? channelTopic : community.topic();
		} else if (community != null) {
			topic = community.topic();
		} else if (StringUtils.hasText(command.topic())) {
			String declared = command.topic().trim().toUpperCase(java.util.Locale.ROOT);
			if (!com.socialcoisa.rede.preferences.domain.ContentTaxonomy.TOPIC_SET.contains(declared)) {
				throw new ApiException(400, "INVALID_TOPIC", "Assunto inválido: escolha um da lista.");
			}
			topic = declared;
		} else {
			topic = null;
		}
		if (StringUtils.hasText(command.content())) {
			duplicationGuard.require(command.authorId().value(), command.content());
		}
		Post post = Post.publish(idGenerator.newPostId(), command.authorId(), command.type(), textOf(command),
			pageHtml(command), tags, mediaIds, communityId, topic, channelId, clock.instant(), authorIdentity,
			command.mediaSpoiler());
		Post saved = postRepository.save(post);
		String articleBody = articleBody(command);
		if (articleBody != null) {
			// Corpo do artigo em tabela propria: nao pesa na linha que o feed le e nao viaja na
			// listagem (ver PostView.from). Mesma transacao do post: ou nasce inteiro, ou nao nasce.
			articleRepository.save(saved.id(), articleBody, clock.instant());
		}
		feedDistributionService.distribute(saved);
		outboxEventRepository.append("PostPublished", saved.id().value(), "{\"postId\":\"" + saved.id().value() + "\"}");
		// O automod de spam roda fora desta transação (listener AFTER_COMMIT + executor): flood,
		// repetição e posts muito curtos passam pela IA sem depender de denúncia.
		events.publishEvent(new PostPublishedEvent(saved.id().value(), saved.authorId().value()));
		notifyMentions(command.mentionedUserIds(), command.authorId().value(), saved.id().value(), "post");
		UserProfile author = userProfileRepository.findById(command.authorId().value()).orElse(null);
		if (author != null) {
			return PostView.from(saved, author.username(), author.name(), author.avatar(), author.allBadges())
				.withAvatarFrame(author.avatarFrame())
				.withAuthorStyle(saved.authorIdentity() == AuthorIdentity.SOCIAL ? NameStyleView.from(author) : null)
				.withContributor(saved.authorIdentity() == AuthorIdentity.SOCIAL && userSeals.hasActiveSeal(author.id())
					? ContributorTagView.from(author) : null);
		}
		return PostView.from(saved);
	}

	/**
	 * Uma publicação BUSINESS exige um perfil profissional ATIVO pertencente à mesma conta que
	 * está publicando — o perfil é resolvido pelo backend a partir da sessão, nunca aceito do
	 * payload do navegador.
	 */
	private AuthorIdentity requireActiveCorporateProfile(String authorId) {
		return corporateProfiles.findByUserId(authorId)
			.filter(com.socialcoisa.rede.corporate.domain.CorporateProfile::active)
			.map(p -> AuthorIdentity.CORPORATE)
			.orElseThrow(() -> new ApiException(403, "NO_ACTIVE_CORPORATE_PROFILE",
				"Você precisa de um perfil profissional ativo para publicar no feed corporativo."));
	}

	private void notifyMentions(List<String> mentionedUserIds, String actorId, String postId, String kind) {		if (mentionedUserIds == null) return;
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
