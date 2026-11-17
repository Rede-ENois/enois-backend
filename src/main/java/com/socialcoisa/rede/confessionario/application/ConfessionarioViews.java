package com.socialcoisa.rede.confessionario.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Views HTTP do Confessionário. Nenhuma delas carrega AUTOR: o anonimato é do contrato, não da
 * tela — sem id, nome, username, avatar ou estilo no JSON, nenhum cliente (atual ou futuro)
 * consegue reexpor quem contou. O autor reconhece a própria história pelo `mine`, que o
 * servidor calcula comparando a sessão de quem lê com o vínculo interno (que continua
 * existindo para moderação, auditoria e penalidade — anonimato público não é ausência de
 * responsabilização). A reação é contada, nunca nominada: o resumo traz só os totais por tipo.
 */
public final class ConfessionarioViews {

	public record ConfissaoPostView(String id, String content, Instant createdAt, long reactions,
			Map<String, Long> reactionSummary, String myReaction, long comments, boolean mine) {
	}

	public record ConfissaoCommentView(String id, String postId, String content, Instant createdAt,
			boolean mine) {
	}

	public record ConfessionarioPage(List<ConfissaoPostView> items, String nextCursor) {
	}

	public record ConfessionarioCommentsPage(List<ConfissaoCommentView> items) {
	}

	private ConfessionarioViews() {
	}
}
