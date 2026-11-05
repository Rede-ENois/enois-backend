package com.socialcoisa.rede.moderation.application.port.out;

import java.util.Optional;

/**
 * Conteudo do alvo denunciado, como a TRIAGEM precisa ver: o texto do post (ou da pagina, quando
 * for PAGE), o texto do comentario, ou o que o perfil mostra (nome, headline, empresa, bio) quando
 * o alvo e uma pessoa.
 *
 * Antes disto a triagem recebia o ID do alvo no lugar do conteudo — o modelo analisava
 * "post_1e4c9acf..." e nao tinha contexto nenhum para julgar, entao todo veredito caia em revisao
 * humana (ou pior: decidia so pela descricao do denunciante, sem ver o conteudo).
 */
public interface ReportedContentReader {

	/** Conteudo legivel do alvo; vazio quando o alvo nao tem texto (vaga, alvo apagado). */
	Optional<String> contentOf(String targetType, String targetId);

	/**
	 * Mesmo conteúdo, a partir da denúncia inteira. Existe porque alguns alvos não têm texto
	 * próprio e o contexto só é localizável pelo id da DENÚNCIA (denúncia de chamada: a sala não
	 * tem texto; o que a triagem pode ver é a fotografia do momento, guardada por denúncia).
	 */
	default Optional<String> contentOf(com.socialcoisa.rede.moderation.domain.Report report) {
		return contentOf(report.targetType().name(), report.targetId());
	}
}
