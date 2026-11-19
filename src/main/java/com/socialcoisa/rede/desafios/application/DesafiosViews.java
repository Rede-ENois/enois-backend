package com.socialcoisa.rede.desafios.application;

import java.time.Instant;
import java.util.List;

import com.socialcoisa.rede.profile.application.NameStyleView;

/**
 * Views HTTP de Desafios. O desafio e a participação são NOMINAIS — convite com responsável e
 * resultado com autoria, então o autor (id, nome, @, avatar e estilo do nome) vai no JSON, como
 * na Colagem. A torcida é contada, nunca nominada: só o total e se quem lê torceu.
 */
public final class DesafiosViews {

	/** O desafio na lista e no detalhe; `destaque` só chega quando encerrado com reconhecimento. */
	public record DesafioView(String id, String title, String description, DesafioAuthor author, Instant createdAt,
			Instant endsAt, String status, String destaqueId, long participacoes, boolean mine) {
	}

	public record DesafioAuthor(String id, String name, String username, String avatar, String avatarFrame,
			NameStyleView nameStyle) {
	}

	public record ParticipacaoView(String id, String desafioId, DesafioAuthor author, String content,
			Instant createdAt, long cheers, boolean cheered, boolean mine) {
	}

	public record DesafiosPage(List<DesafioView> items, String nextCursor) {
	}

	public record ParticipacoesPage(List<ParticipacaoView> items) {
	}

	private DesafiosViews() {
	}
}
