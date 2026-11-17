package com.socialcoisa.rede.feed.application;

import java.time.Instant;

/**
 * Um evento do diario de atividade do perfil: publicacao propria (PUBLICACAO), comentario feito
 * pelo dono (COMENTARIO, so aparece para ele) ou repost (REPOST). `trecho` e o excerto do proprio
 * conteudo; `contexto` descreve o post original por tras de comentario e repost e vem null em
 * publicacoes. A linha do tempo agrupa por dia no cliente.
 */
public record DiarioEventoView(String tipo, Instant quando, String postId, String trecho, Contexto contexto) {

	/** Post original por tras de um comentario ou repost: autor e trecho pra dar contexto na linha. */
	public record Contexto(String autorNome, String autorUsername, String trecho) {
	}
}
