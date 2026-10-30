package com.socialcoisa.rede.notices.domain;

import java.time.Instant;

/**
 * O aviso vigente da plataforma, escrito por um administrador.
 *
 * `bodyHtml` já passou pelo crivo de sanitização de página: quem lê isto pode considerar conteúdo
 * seguro para o iframe isolado. `publishedAt` marca a primeira publicação e não muda em edição;
 * `updatedAt` muda sempre e é a identidade da versão — a tela usa (id + updatedAt) para lembrar
 * quem já fechou aquele aviso.
 */
public record AdminNotice(String id, String title, String bodyHtml, NoticeSurface surface, Instant publishedAt,
		Instant updatedAt) {

	public static AdminNotice of(String id, String title, String bodyHtml, NoticeSurface surface, Instant publishedAt,
			Instant updatedAt) {
		return new AdminNotice(id, title, bodyHtml, surface, publishedAt, updatedAt);
	}
}
