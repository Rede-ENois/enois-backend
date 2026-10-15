package com.socialcoisa.rede.shared.web;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ErrorResponse> handleApiException(ApiException exception) {
		return ResponseEntity.status(exception.status())
			.body(new ErrorResponse(exception.code(), exception.getMessage(), Instant.now()));
	}

	// InvalidDataAccessApiUsageException cobre, por exemplo, PageRequest com size < 1 (limit=0).
	@ExceptionHandler({ MissingRequestHeaderException.class, MethodArgumentNotValidException.class,
			HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
			IllegalArgumentException.class, InvalidDataAccessApiUsageException.class })
	ResponseEntity<ErrorResponse> handleBadRequest(Exception exception) {
		return ResponseEntity.badRequest()
			.body(new ErrorResponse("BAD_REQUEST", "Requisicao invalida.", Instant.now()));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ErrorResponse> handleConflict(DataIntegrityViolationException exception) {
		return ResponseEntity.status(409)
			.body(new ErrorResponse("CONFLICT", "Operacao conflita com o estado atual dos dados.", Instant.now()));
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

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException exception) {
		return ResponseEntity.status(404)
			.body(new ErrorResponse("NOT_FOUND", "Recurso nao encontrado.", Instant.now()));
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ErrorResponse> handleMethodNotAllowed(Exception exception) {
		return ResponseEntity.status(405)
			.body(new ErrorResponse("METHOD_NOT_ALLOWED", "Metodo nao suportado para este recurso.", Instant.now()));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
		LOGGER.error("unhandled_exception", exception);
		return ResponseEntity.internalServerError()
			.body(new ErrorResponse("INTERNAL_ERROR", "Erro interno inesperado.", Instant.now()));
	}
}
