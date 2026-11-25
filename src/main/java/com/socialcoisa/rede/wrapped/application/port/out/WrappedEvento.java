package com.socialcoisa.rede.wrapped.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import com.socialcoisa.rede.wrapped.domain.WrappedAno;

/**
 * Um fato de atividade da conta no ano, já no recorte que a análise precisa. É o formato mínimo que
 * as consultas devolvem: a mensagem do evento (fonte + id da publicação) e quando aconteceu.
 *
 * @param cursor    o id da linha de origem (a chave da paginação: estável, nunca repete)
 * @param momento   o {@code created_at} original do fato (a data de verdade, não a do processamento)
 * @param tipo      o que o fato é (publicação, comentário, reação...)
 * @param postId    a publicação envolvida (a chave de agrupamento das "tretas"/posts marcantes)
 * @param texto     o texto do evento, quando ele tem texto (publicação/comentário); pode ser nulo
 * @param autorId   quem agiu: a própria conta ou o autor do conteúdo, depende do tipo
 * @param autorUsername username do autor (para montar o link do perfil), quando a consulta o resolve
 * @param autorNome   nome do autor
 * @param communityId comunidade da publicação, quando houver
 */
public record WrappedEvento(
		String cursor,
		Instant momento,
		Tipo tipo,
		String postId,
		String texto,
		String autorId,
		String autorUsername,
		String autorNome,
		String communityId) {

	public enum Tipo {
		PUBLICACAO,
		COMENTARIO_PROPRIO,
		REACAO_FEITA,
		COMENTARIO_FEITO,
		COMPARTILHAMENTO_FEITO,
		REPOST_FEITO,
		/** Voto em enquete: uma manifestação feita, como a reação. */
		VOTO_FEITO,
		/** Publicação salva pela conta: abrir depois é interação, não engajamento no autor. */
		SALVO_FEITO,
		REACAO_RECEBIDA,
		COMENTARIO_RECEBIDO,
		COMPARTILHAMENTO_RECEBIDO,
		REPOST_RECEBIDO,
		/** Reação recebida em um COMENTÁRIO da conta (não no post): alimenta "comentários com reação". */
		REACAO_RECEBIDA_COMENTARIO
	}

	/** A hora local e a data local do fato (o mesmo fuso de {@link WrappedAno}) — o que agrupa o ritmo. */
	public LocalDateTime momentoLocal() {
		return LocalDateTime.ofInstant(momento, ZoneId.of("America/Sao_Paulo"));
	}

	/** A data local do fato (para "dias ativos"). */
	public LocalDate diaLocal() {
		return LocalDate.ofInstant(momento, ZoneId.of("America/Sao_Paulo"));
	}

	/** Uma publicação própria — é ela que entra na análise de assuntos e nos "posts marcantes". */
	public boolean ehPublicacaoPropria() {
		return tipo == Tipo.PUBLICACAO;
	}

	public boolean temTexto() {
		return texto != null && !texto.isBlank();
	}
}
