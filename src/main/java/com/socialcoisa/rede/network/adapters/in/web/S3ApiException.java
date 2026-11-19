package com.socialcoisa.rede.network.adapters.in.web;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Erro do gateway S3 no formato que o SDK entende.
 *
 * <p>O corpo é {@code <Error><Code>...} com o <b>código da S3</b> ({@code AccessDenied},
 * {@code SignatureDoesNotMatch}, {@code NoSuchKey}), não o código interno do Énóis: o SDK lê o
 * {@code Code} para decidir se tenta de novo e para mostrar a mensagem ao usuário final.</p>
 *
 * <p>O documento é montado pelo {@link S3Xml}, onde todo valor passa pelo escape — o recurso vem do
 * cliente e o CodeQL cobriu este caminho no PR #914.</p>
 */
public class S3ApiException extends ApiException {

	private static final long serialVersionUID = 1L;

	public S3ApiException(int status, String code, String message) {
		super(status, code, message);
	}

	/** O documento XML de erro, do jeito que a S3 manda. */
	public String toXml(String resource) {
		return new S3Xml().open("Error").element("Code", code()).element("Message", getMessage())
			.element("Resource", resource == null ? "" : resource).close("Error").toString();
	}
}