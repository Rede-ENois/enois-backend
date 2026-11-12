package com.socialcoisa.rede.jobs.application.port.out;

import java.util.Optional;

/**
 * Baixa o currículo do CDN para anexar ao e-mail de candidatura.
 *
 * <p>É uma porta porque o anexo é o ponto onde a plataforma pode mentir: se o arquivo não vier, o
 * e-mail sai sem ele e o registro precisa dizer isso. Quem chama trata {@code Optional.empty()} como
 * "não anexado", nunca como "anexado".</p>
 */
public interface ResumeFetcher {

	/**
	 * O arquivo, quando ele existe, é público e cabe no teto de anexo. Vazio em qualquer outro caso
	 * (URL inválida, host interno, resposta de erro, arquivo grande demais).
	 */
	Optional<FetchedResume> fetch(String url);

	/** Bytes do arquivo e o nome com que ele deve chegar ao destinatário. */
	record FetchedResume(byte[] bytes, String fileName, String contentType) {
	}
}
