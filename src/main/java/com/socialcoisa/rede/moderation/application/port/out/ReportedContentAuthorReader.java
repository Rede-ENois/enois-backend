package com.socialcoisa.rede.moderation.application.port.out;

import java.util.Optional;

/**
 * Autor do conteudo denunciado (quem sofre a sancao). Implementado pelo modulo de feed; outros
 * alvos (perfil, vaga) ficam para revisao humana — a sancao automatica so vale para post e
 * comentario, onde o autor e inequivoco.
 */
public interface ReportedContentAuthorReader {

	Optional<String> authorOf(String targetType, String targetId);

	/**
	 * Autor do alvo a partir da denúncia inteira. Na denúncia de chamada o autor não é derivável do
	 * id do alvo (o alvo é a sala): quem foi denunciado está guardado no contexto da denúncia.
	 */
	default Optional<String> authorOf(com.socialcoisa.rede.moderation.domain.Report report) {
		return authorOf(report.targetType().name(), report.targetId());
	}
}
