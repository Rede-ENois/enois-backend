package com.socialcoisa.rede.shared.web;

import java.time.Instant;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;

@RestControllerAdvice
public class GlobalExceptionHandler {

	/** Marca a requisição quando o cliente fechou a conexão; o filtro de acesso usa para não gravar 500. */
	static final String CLIENT_DISCONNECTED_ATTRIBUTE = "rede.clientDisconnected";

	private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private final com.socialcoisa.rede.ops.monitoring.MonitoringService monitoring;

	GlobalExceptionHandler(com.socialcoisa.rede.ops.monitoring.MonitoringService monitoring) {
		this.monitoring = monitoring;
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ErrorResponse> handleApiException(ApiException exception,
			jakarta.servlet.http.HttpServletRequest request) {
		// 4xx é fluxo normal (validação, permissão); 5xx é falha do servidor e precisa aparecer
		// no CloudWatch com o código do erro, sem corpo de requisição nem dado pessoal.
		if (exception.status() >= 500) {
			LOGGER.error("api_error status={} code={} message={}", exception.status(), exception.code(),
				exception.getMessage(), exception);
			recordException(exception.status(), exception.code(), exception.getMessage(), request, exception);
		}
		else {
			LOGGER.debug("api_client_error status={} code={}", exception.status(), exception.code());
		}
		var response = ResponseEntity.status(exception.status());
		if (exception.retryAfterSeconds() > 0) {
			response.header("Retry-After", Long.toString(exception.retryAfterSeconds()));
		}
		return response.body(new ErrorResponse(exception.code(), exception.getMessage(), Instant.now()));
	}

	/**
	 * Cabeçalho de sessão ausente é 401, não 400. Semanticamente a rota está protegida e o cliente
	 * não se autenticou; devolver BAD_REQUEST fazia o front tratar como requisição malformada e
	 * escondia a exigência de login dos monitores. Outros cabeçalhos obrigatórios continuam 400.
	 */
	@ExceptionHandler(MissingRequestHeaderException.class)
	ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException exception) {
		if ("X-Session-Token".equalsIgnoreCase(exception.getHeaderName())) {
			return ResponseEntity.status(401)
				.body(new ErrorResponse("UNAUTHENTICATED", "Sessao invalida ou expirada.", Instant.now()));
		}
		return ResponseEntity.badRequest()
			.body(new ErrorResponse("BAD_REQUEST", "Requisicao invalida.", Instant.now()));
	}

	// InvalidDataAccessApiUsageException cobre, por exemplo, PageRequest com size < 1 (limit=0).
	@ExceptionHandler({ MethodArgumentNotValidException.class,
			HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
			MissingServletRequestParameterException.class, IllegalArgumentException.class,
			InvalidDataAccessApiUsageException.class })
	ResponseEntity<ErrorResponse> handleBadRequest(Exception exception) {
		// DEBUG de propósito: um cliente malformado não deve gerar milhares de linhas INFO.
		LOGGER.debug("bad_request type={}", exception.getClass().getSimpleName());
		return ResponseEntity.badRequest()
			.body(new ErrorResponse("BAD_REQUEST", "Requisicao invalida.", Instant.now()));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ErrorResponse> handleConflict(DataIntegrityViolationException exception) {
		// A resposta NAO vaza detalhe de banco (a causa raiz fica no log), mas o CÓDIGO diz o que
		// aconteceu. A mensagem única "Operacao conflita com o estado atual dos dados" era o que o
		// painel mostrava quando algo falhava — sem dizer o quê, e a pessoa acabava achando que
		// tinha feito algo errado. O código é estável (o front pode reagir a ele) e a mensagem é
		// escrita para quem lê.
		String cause = rootMessage(exception);
		LOGGER.warn("data_integrity_violation: {}", cause);
		String code = conflictCode(cause);
		return ResponseEntity.status(409).body(new ErrorResponse(code, conflictMessage(code), Instant.now()));
	}

	/**
	 * O código do conflito, a partir da causa que o banco devolveu. O texto original (em inglês,
	 * do driver) fica no log; aqui vira o par (código estável, mensagem para quem lê).
	 */
	private static String conflictCode(String cause) {
		String text = cause == null ? "" : cause.toLowerCase(java.util.Locale.ROOT);
		if (text.contains("duplicate") || text.contains("unique")) {
			return "CONFLICT_DUPLICATE";
		}
		if (text.contains("foreign key") || text.contains("referential")) {
			return "CONFLICT_REFERENCE";
		}
		if (text.contains("too long") || text.contains("value too large") || text.contains("out of range")) {
			return "CONFLICT_VALUE_TOO_LARGE";
		}
		if (text.contains("cannot be null") || text.contains("null value")) {
			return "CONFLICT_MISSING_VALUE";
		}
		if (text.contains("check constraint")) {
			return "CONFLICT_CHECK_CONSTRAINT";
		}
		return "CONFLICT";
	}

	/** A mensagem para quem lê: diz o que aconteceu, sem revelar estrutura do banco. */
	private static String conflictMessage(String code) {
		return switch (code) {
			case "CONFLICT_DUPLICATE" -> "Este registro ja existe.";
			case "CONFLICT_REFERENCE" -> "Operacao conflita com outro registro que depende deste.";
			case "CONFLICT_VALUE_TOO_LARGE" -> "Valor maior do que o permitido.";
			case "CONFLICT_MISSING_VALUE" -> "Faltou um dado obrigatorio.";
			case "CONFLICT_CHECK_CONSTRAINT" -> "Valor fora das regras aceitas.";
			default -> "Operacao conflita com o estado atual dos dados.";
		};
	}

	/** A mensagem mais especifica da cadeia: o "Data too long for column ..." mora no fim dela. */
	private String rootMessage(Throwable exception) {
		Throwable cause = exception;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause.getMessage();
	}

	/**
	 * O arquivo estourou o limite do Servlet (ver `spring.servlet.multipart.max-file-size`). Sem
	 * este tratamento, um ZIP grande virava "Erro interno inesperado" — foi assim que o import do
	 * LinkedIn (5 MB no servico, 1 MB no Servlet) ficou sem resposta util para quem tentava.
	 */
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException exception) {
		return ResponseEntity.status(413)
			.body(new ErrorResponse("FILE_TOO_LARGE", "O arquivo enviado passa do limite permitido.",
				Instant.now()));
	}

	@ExceptionHandler(RequestNotPermitted.class)
	ResponseEntity<ErrorResponse> handleRateLimit(RequestNotPermitted exception) {
		return ResponseEntity.status(429)
			.header("Retry-After", "60")
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

	/**
	 * Cliente que fecha o vídeo no meio do stream (seek/pause) ou conexão lenta que estoura o
	 * timeout assíncrono não é erro do servidor: a resposta já estava comprometida e não há o que
	 * corrigir. Antes isso caía no handler genérico e enchia a monitoração de 500 falsos.
	 */
	@ExceptionHandler({ AsyncRequestNotUsableException.class, AsyncRequestTimeoutException.class })
	void handleAsyncRequestEnded(Exception exception, jakarta.servlet.http.HttpServletRequest request) {
		request.setAttribute(CLIENT_DISCONNECTED_ATTRIBUTE, Boolean.TRUE);
		LOGGER.debug("async_request_ended type={}", exception.getClass().getSimpleName());
	}

	/**
	 * A resposta JSON também pode morrer no meio: o navegador fecha a aba ou cancela o fetch e o
	 * Tomcat devolve "Broken pipe" ao serializar. Isso não é falha do servidor. Um erro real de
	 * serialização (Jackson) continua sendo 500 e registrado.
	 */
	@ExceptionHandler(HttpMessageNotWritableException.class)
	ResponseEntity<ErrorResponse> handleNotWritable(HttpMessageNotWritableException exception,
			jakarta.servlet.http.HttpServletRequest request) {
		if (isClientDisconnect(exception)) {
			request.setAttribute(CLIENT_DISCONNECTED_ATTRIBUTE, Boolean.TRUE);
			LOGGER.debug("client_disconnected type={}", exception.getClass().getSimpleName());
			return null;
		}
		LOGGER.error("response_write_failed", exception);
		recordException(500, "RESPONSE_WRITE_FAILED", exception.getMessage(), request, exception);
		return ResponseEntity.internalServerError()
			.body(new ErrorResponse("INTERNAL_ERROR", "Erro interno inesperado.", Instant.now()));
	}

	private static boolean isClientDisconnect(Throwable exception) {
		Throwable cause = exception;
		while (cause != null) {
			String message = cause.getMessage();
			if (message != null) {
				String lower = message.toLowerCase(Locale.ROOT);
				if (lower.contains("broken pipe") || lower.contains("connection reset")
						|| lower.contains("disconnected client") || lower.contains("closedchannel")
						|| lower.contains("failed to write") || lower.contains("eofexception")) {
					return true;
				}
			}
			cause = cause.getCause();
		}
		return false;
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> handleUnexpected(Exception exception,
			jakarta.servlet.http.HttpServletRequest request) {
		LOGGER.error("unhandled_exception", exception);
		recordException(500, exception.getClass().getSimpleName(), exception.getMessage(), request, exception);
		return ResponseEntity.internalServerError()
			.body(new ErrorResponse("INTERNAL_ERROR", "Erro interno inesperado.", Instant.now()));
	}

	private void recordException(int status, String code, String message,
			jakarta.servlet.http.HttpServletRequest request) {
		recordException(status, code, message, request, null);
	}

	private void recordException(int status, String code, String message,
			jakarta.servlet.http.HttpServletRequest request, Throwable failure) {
		try {
			Object route = request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
			monitoring.recordException(status, code, message, request.getMethod(), request.getRequestURI(),
				route == null ? null : route.toString(), org.slf4j.MDC.get("requestId"), org.slf4j.MDC.get("userId"),
				failure);
		}
		catch (RuntimeException ignored) {
			// Monitoração nunca pode mascarar o erro original.
		}
	}
}
