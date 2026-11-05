package com.socialcoisa.rede.moderation.application;

/**
 * Uma denúncia de conteúdo SEXUAL em POST acabou de ser criada (commit feito).
 *
 * <p>O ouvinte leva o post à quarentena imediata e manda o bot avaliar: NSFW confirmado apaga o
 * post, seguro devolve ao feed, dúvida fica para uma pessoa. O postId e o relato de quem
 * denunciou alimentam a avaliação do modelo.</p>
 */
public record SexualContentReportEvent(String postId, String reportDescription) {
}
