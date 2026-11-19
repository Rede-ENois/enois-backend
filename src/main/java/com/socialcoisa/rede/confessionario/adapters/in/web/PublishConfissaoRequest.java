package com.socialcoisa.rede.confessionario.adapters.in.web;

/**
 * Corpo do POST /confessionario/posts. A confissão é só texto — sem mídia, sem enquete.
 */
public record PublishConfissaoRequest(String content) {
}
