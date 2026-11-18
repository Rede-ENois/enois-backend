package com.socialcoisa.rede.network.adapters.in.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Erro do gateway S3 no formato que o SDK entende.
 *
 * <p>O corpo é {@code <Error><Code>...} com o <b>código da S3</b> ({@code AccessDenied},
 * {@code SignatureDoesNotMatch}, {@code NoSuchKey}), não o código interno do Énóis: o SDK lê o
 * {@code Code} para decidir se tenta de novo e para mostrar a mensagem ao usuário final.</p>
 */
public class S3ApiException extends ApiException {

	private static final long serialVersionUID = 1L;

	public S3ApiException(int status, String code, String message) {
		super(status, code, message);
	}

	/** O documento XML de erro, do jeito que a S3 manda. */
	public String toXml(String resource) {
		Map<String, String> campos = new LinkedHashMap<>();
		campos.put("Code", code());
		campos.put("Message", getMessage());
		campos.put("Resource", resource == null ? "" : resource);
		StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error>");
		campos.forEach((k, v) -> xml.append('<').append(k).append('>').append(S3GatewayController.esc(v))
			.append("</").append(k).append('>'));
		xml.append("</Error>");
		return xml.toString();
	}
}