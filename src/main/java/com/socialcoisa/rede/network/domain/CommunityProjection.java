package com.socialcoisa.rede.network.domain;

/**
 * O que a borda serve de uma comunidade — e o campo que ela <b>não</b> tem.
 *
 * <p>{@code GET /communities/{id}} devolve {@code joined}, que depende de quem está lendo. A borda
 * não tem sessão de usuário e não decide autorização, então esse campo não existe aqui: o nó
 * entrega a vitrine pública e o cliente pergunta ao Control Plane se participa. É a diferença entre
 * "cachear conteúdo público" e "cachear resposta personalizada", e é a razão de esta projeção ser
 * um tipo próprio em vez de reaproveitar o view da web.</p>
 */
public record CommunityProjection(String id, String name, String description, int members, String topic,
		String cover, String avatar, String bannerHtml, String city, String state) {
}
