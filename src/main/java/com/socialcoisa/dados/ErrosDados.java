package com.socialcoisa.dados;

import java.time.Instant;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.ErrorResponse;

@RestControllerAdvice(assignableTypes = DadosController.class)
class ErrosDados {

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ErrorResponse> erro(ApiException falha) {
		var resposta = ResponseEntity.status(falha.status());
		if (falha.retryAfterSeconds() > 0) {
			resposta.header(HttpHeaders.RETRY_AFTER, Long.toString(falha.retryAfterSeconds()));
		}
		return resposta.body(new ErrorResponse(falha.code(), falha.getMessage(), Instant.now()));
	}
}
