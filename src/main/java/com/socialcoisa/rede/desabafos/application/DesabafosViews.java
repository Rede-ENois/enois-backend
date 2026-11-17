package com.socialcoisa.rede.desabafos.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Views HTTP dos Desabafos. Nenhuma delas carrega AUTOR: o anonimato é do contrato, não da tela —
 * sem id, nome, username, avatar ou estilo no JSON, nenhum cliente (atual ou futuro) consegue
 * reexpor quem escreveu. O autor reconhece o próprio texto pelo `mine`, que o servidor calcula
 * comparando a sessão de quem lê com o vínculo interno (que continua existindo para moderação,
 * auditoria e penalidade — anonimato público não é ausência de responsabilização). A reação é
 * contada, nunca nominada: o resumo traz só os totais por tipo.
 */
public final class DesabafosViews {

	public record DesabafoPostView(String id, String content, Instant createdAt, long reactions,
			Map<String, Long> reactionSummary, String myReaction, long comments, boolean mine) {
	}

	public record DesabafoCommentView(String id, String postId, String content, Instant createdAt,
			boolean mine) {
	}

	public record DesabafosPage(List<DesabafoPostView> items, String nextCursor) {
	}

	public record DesabafosCommentsPage(List<DesabafoCommentView> items) {
	}

	private DesabafosViews() {
	}
}
