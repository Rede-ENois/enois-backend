package com.socialcoisa.rede.shared.web;

import java.time.Instant;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ErrorResponse> handleApiException(ApiException exception) {
		return ResponseEntity.status(exception.status())
			.body(new ErrorResponse(exception.code(), exception.getMessage(), Instant.now()));
	}

	@ExceptionHandler({ MissingRequestHeaderException.class, MethodArgumentNotValidException.class })
	ResponseEntity<ErrorResponse> handleBadRequest(Exception exception) {
		return ResponseEntity.badRequest()
			.body(new ErrorResponse("BAD_REQUEST", "Requisicao invalida.", Instant.now()));
	}

	@ExceptionHandler(RequestNotPermitted.class)
	ResponseEntity<ErrorResponse> handleRateLimit(RequestNotPermitted exception) {
		return ResponseEntity.status(429)
			.body(new ErrorResponse("RATE_LIMITED", "Limite de requisicoes excedido.", Instant.now()));
	}

	@ExceptionHandler({ CallNotPermittedException.class, BulkheadFullException.class })
	ResponseEntity<ErrorResponse> handleUnavailable(RuntimeException exception) {
		return ResponseEntity.status(503)
			.body(new ErrorResponse("DEPENDENCY_UNAVAILABLE", "Dependencia temporariamente indisponivel.", Instant.now()));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
		return ResponseEntity.internalServerError()
			.body(new ErrorResponse("INTERNAL_ERROR", "Erro interno inesperado.", Instant.now()));
	}
}
