package com.socialcoisa.rede.media.application.port.out;

import java.io.InputStream;

/**
 * Le e escreve o OBJETO em si no storage (nao o cadastro do asset).
 *
 * Existe porque o gateway S3 do b7 nao responde CORS: o navegador nao consegue fazer o `PUT`
 * assinado nem ler o bucket anonimo. Com estas duas operacoes o app serve a midia pelo proprio
 * host — `GET /api/storage/{chave}` e `PUT /api/storage/{chave}` — e o navegador nunca fala com
 * outro dominio.
 */
public interface MediaObjectStore {

	/** Abre o objeto para leitura; `range` nulo = arquivo inteiro. */
	StoredObject read(String key, String range);

	void write(String key, String contentType, long length, InputStream body);

	/**
	 * `contentRange` vem preenchido quando o pedido foi parcial (206) — vai de volta no cabecalho
	 * `Content-Range`, que e o que faz o player de video conseguir arrastar a barra.
	 */
	record StoredObject(InputStream body, long length, String contentType, String contentRange, String eTag) {
	}
}
