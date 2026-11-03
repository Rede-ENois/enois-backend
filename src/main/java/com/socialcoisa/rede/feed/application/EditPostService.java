package com.socialcoisa.rede.feed.application;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.feed.application.port.in.EditPostUseCase;
import com.socialcoisa.rede.feed.application.port.out.ArticleRepository;
import com.socialcoisa.rede.feed.application.port.out.AuditLogRepository;
import com.socialcoisa.rede.feed.application.port.out.FeedCacheRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.AuthorIdentity;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.profile.application.ContributorTagView;
import com.socialcoisa.rede.profile.application.NameStyleView;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.html.SafeHtml;
import com.socialcoisa.rede.shared.text.PlainText;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Edicao de publicacao propria. So o autor edita (validado no backend, nao no cliente) e o conteudo
 * segue as mesmas regras do post novo. A CATEGORIA (tipo) tambem e editavel desde 19/10 — trocar
 * INSIGHT por QUESTION, por exemplo — e as regras de conteudo do tipo novo valem na hora; midias e
 * comunidade ficam como estao. A pagina (posts do tipo PAGE) e re-sanitizada a cada edicao, porque
 * o HTML vem de fora de novo.
 */
@Service
public class EditPostService implements EditPostUseCase {


	private static final int MAX_TAGS = 5;

	private final PostRepository postRepository;
	private final ArticleRepository articleRepository;
	private final FeedCacheRepository feedCacheRepository;
	private final AuditLogRepository auditLogRepository;
	private final UserProfileRepository userProfileRepository;
	private final com.socialcoisa.rede.contributions.application.port.out.UserSealRepository userSeals;
	private final Clock clock;
	private final com.socialcoisa.rede.media.application.port.in.GetMediaSummariesUseCase mediaSummaries;

	public EditPostService(PostRepository postRepository, ArticleRepository articleRepository,
			FeedCacheRepository feedCacheRepository,
			AuditLogRepository auditLogRepository, UserProfileRepository userProfileRepository,
			com.socialcoisa.rede.contributions.application.port.out.UserSealRepository userSeals, Clock clock,
			com.socialcoisa.rede.media.application.port.in.GetMediaSummariesUseCase mediaSummaries) {
		this.mediaSummaries = mediaSummaries;
		this.postRepository = postRepository;
		this.articleRepository = articleRepository;
		this.feedCacheRepository = feedCacheRepository;
		this.auditLogRepository = auditLogRepository;
		this.userProfileRepository = userProfileRepository;
		this.userSeals = userSeals;
		this.clock = clock;
	}

	@Override
	@Transactional
	public PostView edit(EditPostCommand command) {
		Post post = postRepository.findById(command.postId())
			.orElseThrow(() -> new ApiException(404, "POST_NOT_FOUND", "Post nao encontrado."));
		if (!post.belongsTo(command.userId())) {
			throw new ApiException(403, "POST_OWNER_MISMATCH", "Post nao pertence ao usuario autenticado.");
		}
		if (!post.isActive()) {
			throw new ApiException(404, "POST_NOT_FOUND", "Post nao encontrado.");
		}
		// A CATEGORIA e editavel (pedido via suporte): nulo mantem, preenchido troca — e as regras
		// de conteudo passam a ser as do tipo NOVO, iguais as da criacao.
		PostType tipoNovo = command.type() == null ? post.type() : command.type();
		// A identidade NAO acompanha troca de tipo: um post BUSINESS nunca vira social (sairia
		// do feed corporativo para o social sem regra nenhuma) e um social nunca vira BUSINESS
		// por edicao (publicar no corporativo exige perfil profissional ativo na criacao).
		if ((post.authorIdentity() == AuthorIdentity.CORPORATE) != (tipoNovo == PostType.BUSINESS)) {
			throw new ApiException(400, "IDENTITY_TYPE_MISMATCH",
				post.authorIdentity() == AuthorIdentity.CORPORATE
					? "Publicação corporativa permanece BUSINESS. Se quiser um post social, publique um novo."
					: "Posts sociais não viram BUSINESS. Publique um novo com o perfil profissional.");
		}
		String content = validateContent(command.content(), tipoNovo);
		List<String> tags = normalizeTags(command.tags());
		// O assunto acompanha a mesma regra da criacao: post de COMUNIDADE herda o dela (o
		// compositor nao escolhe); post avulso pode trocar o assunto declarado pelo autor.
		String topic = normalizeTopic(post, command.topic());

		Post edited = post.edited(tipoNovo, content, pageHtml(tipoNovo, command), tags, topic, clock.instant());
		Post saved = postRepository.save(edited);
		String articleBody = articleBody(tipoNovo, command);
		if (articleBody != null) {
			// Editar artigo sem mandar o texto apagaria o corpo: a regra e a mesma da pagina.
			// E um post que VIRA artigo ganha a linha agora (updateBody so mexe em linha existente).
			if (articleRepository.findBody(saved.id()).isPresent()) {
				articleRepository.updateBody(saved.id(), articleBody, clock.instant());
			}
			else {
				articleRepository.save(saved.id(), articleBody, clock.instant());
			}
		}
		feedCacheRepository.removeEverywhere(saved.id());
		auditLogRepository.record(command.userId(), "POST_EDITED", saved.id().value(), null);

		var author = userProfileRepository.findById(saved.authorId().value()).orElse(null);
		PostView view = author != null
			? PostView.from(saved, author.username(), author.name(), author.avatar(), author.allBadges())
				.withAvatarFrame(author.avatarFrame())
				.withAuthorStyle(saved.authorIdentity() == AuthorIdentity.SOCIAL ? NameStyleView.from(author) : null)
				.withContributor(saved.authorIdentity() == AuthorIdentity.SOCIAL && userSeals.hasActiveSeal(author.id())
					? ContributorTagView.from(author) : null)
			: PostView.from(saved);
		return view.withMedia(mediaSummaries.visibleByIds(saved.mediaIds()));
	}

	/**
	 * A pagina acompanha o tipo do post (nao o que o cliente manda): num PAGE ela e obrigatoria,
	 * nos outros o HTML recebido e descartado.
	 */
	private String pageHtml(PostType tipo, EditPostCommand command) {
		if (tipo != PostType.PAGE) {
			return null;
		}
		return SafeHtml.requirePageHtml(command.pageHtml());
	}

	/**
	 * Texto do post editado. Num post de pagina ele pode ficar vazio (pagina e uma coisa OU outra:
	 * o HTML e o conteudo), mas em qualquer outro tipo continua obrigatorio.
	 */
	/** Corpo do artigo acompanha o tipo do post (nao o que o cliente manda), como a pagina. */
	private String articleBody(PostType tipo, EditPostCommand command) {
		if (tipo != PostType.ARTICLE) {
			return null;
		}
		return PublishPostService.validateArticleBody(command.articleBody());
	}

	private String validateContent(String content, PostType type) {
		if (!StringUtils.hasText(content)) {
			if (type == PostType.PAGE) {
				return "";
			}
			throw new ApiException(400, "INVALID_CONTENT", "Conteudo obrigatorio.");
		}
		// Mesmo limite da criacao, por tipo: editar nao pode ser a porta dos fundos do limite. Measured on
		// the text as sent, like creation does — defuse shortens repeated runs and would let it through.
		int maxContent = com.socialcoisa.rede.feed.domain.PostContentLimits.of(type);
		if (content.trim().length() > maxContent) {
			throw new ApiException(400, "CONTENT_TOO_LONG", "Este tipo de post aceita no maximo " + maxContent + " caracteres.");
		}
		return PlainText.defuse(content.trim());
	}

	private List<String> normalizeTags(List<String> tags) {
		List<String> normalized = tags == null ? List.of() : tags.stream()
			.filter(StringUtils::hasText)
			.map(String::trim)
			.distinct()
			.toList();
		if (normalized.size() > MAX_TAGS) {
			throw new ApiException(400, "TOO_MANY_TAGS", "Post deve ter no maximo 5 tags.");
		}
		return normalized;
	}

	/**
	 * Assunto na edicao: nulo/vazio MANTÉM o atual; preenchido troca, validado contra a taxonomia.
	 * Post de comunidade não aceita: o assunto é o dela, sempre.
	 */
	private String normalizeTopic(Post post, String topic) {
		if (!StringUtils.hasText(topic)) {
			return post.topic();
		}
		if (post.communityId() != null) {
			throw new ApiException(400, "TOPIC_FROM_COMMUNITY",
				"Post em comunidade herda o assunto dela — o compositor não escolhe assunto aqui.");
		}
		String declared = topic.trim().toUpperCase(java.util.Locale.ROOT);
		if (!com.socialcoisa.rede.preferences.domain.ContentTaxonomy.TOPIC_SET.contains(declared)) {
			throw new ApiException(400, "INVALID_TOPIC", "Assunto inválido: escolha um da lista.");
		}
		return declared;
	}
}
