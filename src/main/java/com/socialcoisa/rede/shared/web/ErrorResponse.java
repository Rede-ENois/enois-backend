package com.socialcoisa.rede.shared.web;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Corpo de erro da API. {@code detail} só aparece quando o erro tem algo a mais para o cliente
 * decidir o que mostrar; fica fora do JSON quando é nulo, para o corpo de sempre não mudar.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, Instant timestamp, String detail) {

	public ErrorResponse(String code, String message, Instant timestamp) {
		this(code, message, timestamp, null);
	}
}
