package com.socialcoisa.rede.media.application.port.out;

import java.time.Instant;
import java.util.Map;

/**
 * Assina a subida do arquivo direto no storage, sem passar pelo app.
 *
 * Existe para o S3: quem tem o arquivo e o browser, e a URL assinada autoriza aquela subida por
 * alguns minutos, sem credencial nenhuma no cliente. O ImageKit nao usa isto — o fluxo dele e o
 * multipart assinado proprio, que o front ja faz.
 */
public interface MediaUploadSigner {

	/**
	 * Metodo, endereco e cabecalhos que o cliente precisa mandar para subir o arquivo.
	 *
	 * @param contentLength tamanho declarado no registro do asset; entra na assinatura para o
	 *     storage recusar um corpo maior (ou menor) que o combinado
	 */
	SignedUpload sign(String objectKey, String mimeType, long contentLength);

	record SignedUpload(String url, String method, Map<String, String> headers, Instant expiresAt) {
	}
}
