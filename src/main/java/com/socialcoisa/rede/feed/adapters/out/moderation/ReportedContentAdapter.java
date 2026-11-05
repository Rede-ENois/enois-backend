package com.socialcoisa.rede.feed.adapters.out.moderation;

import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.calls.domain.CallReportContext;
import com.socialcoisa.rede.feed.application.port.out.CommentRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.groups.application.port.out.GroupContentRepository;
import com.socialcoisa.rede.groups.domain.GroupComment;
import com.socialcoisa.rede.groups.domain.GroupPost;
import com.socialcoisa.rede.moderation.application.port.out.CallReportContextRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentReader;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;

/**
 * Conteudo do alvo denunciado para a triagem por IA.
 *
 * Post traz o texto e, quando for pagina, o HTML (e ali que o conteudo mora); comentario traz o
 * texto; perfil traz o que a pessoa escreveu sobre si — o que da para julgar num perfil denunciado
 * como spam. Alvo sem texto (vaga, ou ja apagado) devolve vazio, e a triagem assume que nao tem
 * conteudo em vez de julgar pelo id.
 */
@Component
class ReportedContentAdapter implements ReportedContentReader {

	/** Teto do que vai ao modelo: o suficiente para julgar, sem estourar o prompt. */
	private static final int MAX_CHARS = 4_000;

	private final PostRepository posts;
	private final CommentRepository comments;
	private final UserProfileRepository profiles;
	private final GroupContentRepository groupContent;
	private final CallReportContextRepository callReports;

	ReportedContentAdapter(PostRepository posts, CommentRepository comments, UserProfileRepository profiles,
			GroupContentRepository groupContent, CallReportContextRepository callReports) {
		this.posts = posts;
		this.comments = comments;
		this.profiles = profiles;
		this.groupContent = groupContent;
		this.callReports = callReports;
	}

	@Override
	public Optional<String> contentOf(String targetType, String targetId) {
		Optional<String> content = switch (targetType == null ? "" : targetType) {
			case "POST" -> posts.findById(new PostId(targetId)).map(this::postText);
			case "COMMENT" -> comments.findById(targetId).map(comment -> comment.content());
			case "USER" -> profiles.findById(targetId).map(this::profileText);
			case "GROUP_POST" -> groupContent.findPost(targetId).map(GroupPost::content);
			case "GROUP_COMMENT" -> groupContent.findComment(targetId).map(GroupComment::content);
			default -> Optional.empty();
		};
		return content.map(this::shorten).filter(StringUtils::hasText);
	}

	/**
	 * Denúncia de chamada: a sala não tem texto. O que existe é o relato de quem denunciou e a
	 * fotografia do momento, que fica pendurada na denúncia — a triagem recebe qual dos dois é,
	 * para não confundir print com fato.
	 */
	@Override
	public Optional<String> contentOf(Report report) {
		if (report.targetType() == com.socialcoisa.rede.moderation.domain.TargetType.CALL) {
			return callReports.findByReportId(report.id()).map(context -> callContextText(report, context));
		}
		return contentOf(report.targetType().name(), report.targetId());
	}

	private String callContextText(Report report, CallReportContext context) {
		StringBuilder text = new StringBuilder("Contexto do aplicativo no momento da denúncia:\n");
		text.append("o relato de quem denunciou está abaixo e é alegação, ainda não é prova; ")
			.append("fatos confirmados aqui são os que aparecem na imagem, quando houver.\n");
		if (context.mediaId() != null) {
			text.append("A imagem anexada é a captura da tela da chamada feita por quem denunciou no momento do pedido");
			text.append(" (pode estar desfocada, parcial ou da tela de quem denuncia).\n");
		}
		else {
			text.append("A denúncia não tem captura de tela: só o relato.\n");
		}
		text.append("Participantes na sala naquele instante: ")
			.append(String.join(", ", context.participants())).append('.');
		if (StringUtils.hasText(report.description())) {
			text.append("\nRelato de quem denunciou: ").append(report.description().trim());
		}
		return shorten(text.toString());
	}

	private String postText(com.socialcoisa.rede.feed.domain.Post post) {
		// Pagina e uma coisa OU outra: quando o HTML existe, e ele o conteudo (o texto de cima e
		// so o titulo) — entao os dois entram, com o HTML primeiro.
		return Stream.of(post.pageHtml(), post.content())
			.filter(StringUtils::hasText)
			.reduce((first, second) -> first + "\n\n" + second)
			.orElse("");
	}

	private String profileText(UserProfile profile) {
		return Stream.of(profile.name(), profile.headline(), profile.company(), profile.location(), profile.bio())
			.filter(StringUtils::hasText)
			.reduce((first, second) -> first + "\n" + second)
			.orElse("");
	}

	private String shorten(String content) {
		String trimmed = content.trim();
		return trimmed.length() <= MAX_CHARS ? trimmed : trimmed.substring(0, MAX_CHARS);
	}
}
