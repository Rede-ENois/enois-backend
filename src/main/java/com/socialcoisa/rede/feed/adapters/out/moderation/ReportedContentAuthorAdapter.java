package com.socialcoisa.rede.feed.adapters.out.moderation;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.colagem.application.port.out.ColagemRepository;
import com.socialcoisa.rede.confessionario.application.port.out.ConfessionarioRepository;
import com.socialcoisa.rede.desabafos.application.port.out.DesabafosRepository;
import com.socialcoisa.rede.feed.application.port.out.CommentRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.figurinhas.application.port.out.FigurinhasComunidadeRepository;
import com.socialcoisa.rede.figurinhas.domain.FigurinhaComunidade;
import com.socialcoisa.rede.groups.application.port.out.GroupContentRepository;
import com.socialcoisa.rede.groups.domain.GroupComment;
import com.socialcoisa.rede.groups.domain.GroupPost;
import com.socialcoisa.rede.moderation.application.port.out.CallReportContextRepository;
import com.socialcoisa.rede.murais.application.port.out.MuraisRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentAuthorReader;
import com.socialcoisa.rede.moderation.domain.Report;

/**
 * Autor do conteudo denunciado, para a sancao saber em quem recai.
 *
 * Conteudo de grupo tambem resolve aqui: a publicacao e o comentario do feed privado tem autor
 * inequivoco, como os do feed principal.
 */
@Component
class ReportedContentAuthorAdapter implements ReportedContentAuthorReader {

	private final PostRepository posts;
	private final CommentRepository comments;
	private final GroupContentRepository groupContent;
	private final CallReportContextRepository callReports;
	private final ColagemRepository colagem;
	private final MuraisRepository murais;
	private final FigurinhasComunidadeRepository figurinhas;
	private final DesabafosRepository desabafos;
	private final ConfessionarioRepository confessionario;

	ReportedContentAuthorAdapter(PostRepository posts, CommentRepository comments,
			GroupContentRepository groupContent, CallReportContextRepository callReports, ColagemRepository colagem,
			MuraisRepository murais, DesabafosRepository desabafos, ConfessionarioRepository confessionario,
			FigurinhasComunidadeRepository figurinhas) {
		this.posts = posts;
		this.comments = comments;
		this.groupContent = groupContent;
		this.callReports = callReports;
		this.colagem = colagem;
		this.murais = murais;
		this.desabafos = desabafos;
		this.confessionario = confessionario;
		this.figurinhas = figurinhas;
	}

	/**
	 * Denúncia de chamada: o alvo é a sala, então o autor não sai do id do alvo. Sai do contexto
	 * gravado no momento do pedido — a pessoa que quem denunciou apontou.
	 */
	@Override
	public Optional<String> authorOf(Report report) {
		if (report.targetType() == com.socialcoisa.rede.moderation.domain.TargetType.CALL) {
			return callReports.findByReportId(report.id())
				.map(context -> context.reportedUserId());
		}
		return authorOf(report.targetType().name(), report.targetId());
	}

	@Override
	public Optional<String> authorOf(String targetType, String targetId) {
		return switch (targetType) {
			case "POST" -> posts.findById(new PostId(targetId)).map(p -> p.authorId().value());
			case "COMMENT" -> comments.findById(targetId).map(c -> c.authorId().value());
			// Denuncia de perfil: o alvo E a pessoa. Sem isto, penalizar/banir um perfil denunciado
			// (spam, por exemplo) batia em "alvo sem autor" e nada acontecia.
			case "USER" -> Optional.of(targetId);
			// Alvo sem chamada por trás (sala inexistente): ninguém para sancionar.
			case "CALL" -> Optional.empty();
			case "GROUP_POST" -> groupContent.findPost(targetId).map(GroupPost::authorId);
			case "GROUP_COMMENT" -> groupContent.findComment(targetId).map(GroupComment::authorId);
			case "COLLAGEM_POST" -> colagem.findPostById(targetId).map(p -> p.authorId().value());
			case "COLLAGEM_COMMENT" -> colagem.findCommentById(targetId).map(c -> c.authorId().value());
			case "MURAL" -> murais.findMuralById(targetId).map(m -> m.ownerId().value());
			// Desabafos e Confessionário: o anonimato é do contrato público, não um apagamento. O
			// vínculo autor-conteúdo continua gravado para a moderação responsabilizar quem quebra
			// a regra; sem ele o alvo caía em "sem autor" e a punição ficava impossível.
			case "DESABAFO_POST" -> desabafos.findPostById(targetId).map(p -> p.authorId().value());
			case "DESABAFO_COMMENT" -> desabafos.findCommentById(targetId).map(c -> c.authorId().value());
			case "CONFESSIONARIO_POST" -> confessionario.findPostById(targetId).map(p -> p.authorId().value());
			case "CONFESSIONARIO_COMMENT" -> confessionario.findCommentById(targetId).map(c -> c.authorId().value());
			// Quem cadastrou a figurinha: a penalidade humana recai sobre essa conta, e é ela que
			// tira a figurinha do índice (a linha fica para a auditoria).
			case "FIGURINHA_COMUNIDADE" -> figurinhas.findById(targetId).map(FigurinhaComunidade::autorId);
			default -> Optional.empty();
		};
	}
}
