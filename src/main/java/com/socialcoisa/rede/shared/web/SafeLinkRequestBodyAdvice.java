package com.socialcoisa.rede.shared.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Map;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Every string of every JSON request body bound to an object goes through
 * {@link SafeUrl#requireSafeMarkdownLinks}.
 *
 * Markdown is rendered by the front in posts, comments, messages, blog, bios, job and community
 * descriptions and more; checking here is the one place all of them route through, instead of a
 * call per field that the next new field would forget. The body is read once, checked on its
 * decoded strings (JSON escapes cannot hide a scheme) and handed on unchanged.
 */
@ControllerAdvice
class SafeLinkRequestBodyAdvice extends RequestBodyAdviceAdapter {

	/** Teto do JSON lido para a checagem de links. DTOs do app ficam muito abaixo disto. */
	private static final long MAX_JSON_BODY = 2L * 1024 * 1024;

	private final ObjectMapper objectMapper;

	SafeLinkRequestBodyAdvice(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public boolean supports(MethodParameter parameter, Type targetType,
			Class<? extends HttpMessageConverter<?>> converterType) {
		// Raw byte[]/String bodies are not user content bound to a DTO: they are payloads a handler
		// verifies or relays as-is (the GitHub webhook checks an HMAC over the exact bytes).
		return targetType != byte[].class && targetType != String.class;
	}

	@Override
	public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type targetType,
			Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
		MediaType type = input.getHeaders().getContentType();
		if (type == null || !(type.isCompatibleWith(MediaType.APPLICATION_JSON) || type.getSubtype().endsWith("+json"))) {
			return input;
		}
		long declared = input.getHeaders().getContentLength();
		if (declared > MAX_JSON_BODY) {
			throw new HttpMessageNotReadableException("Corpo JSON acima do limite permitido.", input);
		}
		// Sem Content-Length (chunked) o tamanho nao e conhecido: le no maximo o teto + 1 byte
		// para descobrir que passou, em vez de materializar o corpo inteiro.
		byte[] body = input.getBody().readNBytes((int) MAX_JSON_BODY + 1);
		if (body.length > MAX_JSON_BODY) {
			throw new HttpMessageNotReadableException("Corpo JSON acima do limite permitido.", input);
		}
		if (body.length > 0) {
			try {
				check(objectMapper.readTree(body), "texto");
			}
			catch (JacksonException malformed) {
				// The converter reports the malformed body as usual.
			}
		}
		HttpHeaders headers = input.getHeaders();
		return new HttpInputMessage() {
			@Override
			public InputStream getBody() {
				return new ByteArrayInputStream(body);
			}

			@Override
			public HttpHeaders getHeaders() {
				return headers;
			}
		};
	}

	private static void check(JsonNode node, String field) {
		if (node.isString()) {
			SafeUrl.requireSafeMarkdownLinks(node.asString(), field);
		}
		else if (node.isObject()) {
			for (Map.Entry<String, JsonNode> property : node.properties()) {
				check(property.getValue(), property.getKey());
			}
		}
		else if (node.isArray()) {
			for (JsonNode item : node) {
				check(item, field);
			}
		}
	}
}
