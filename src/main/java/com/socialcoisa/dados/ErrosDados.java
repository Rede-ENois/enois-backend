package com.socialcoisa.dados;

import java.time.Instant;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.ErrorResponse;

import io.github.resilience4j.ratelimiter.RequestNotPermitted;

@RestControllerAdvice(assignableTypes = {DadosController.class, SessoesDadosController.class})
class ErrosDados {

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ErrorResponse> erro(ApiException falha) {
		var resposta = ResponseEntity.status(falha.status());
		if (falha.retryAfterSeconds() > 0) {
			resposta.header(HttpHeaders.RETRY_AFTER, Long.toString(falha.retryAfterSeconds()));
		}
		return resposta.body(new ErrorResponse(falha.code(), falha.getMessage(), Instant.now()));
	}

	@ExceptionHandler(RequestNotPermitted.class)
	ResponseEntity<ErrorResponse> limite() {
		return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, "60")
			.body(new ErrorResponse("RATE_LIMITED", "Limite de requisicoes excedido.", Instant.now()));
	}
}
