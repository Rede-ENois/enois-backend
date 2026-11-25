package com.socialcoisa.rede.shared.web;

public class ApiException extends RuntimeException {

	private final int status;
	private final String code;
	private String detail;
	private long retryAfterSeconds;
	private final String etag;

	private ApiException(int status, String code, String message, Throwable cause, String detail,
			long retryAfterSeconds, String etag) {
		super(message, cause);
		this.status = status;
		this.code = code;
		this.detail = detail;
		this.retryAfterSeconds = retryAfterSeconds;
		this.etag = etag;
	}

	public ApiException(int status, String code, String message) {
		this(status, code, message, null, null, 0L, null);
	}

	/**
	 * Mesma resposta, com a causa original anexada: o log e o monitoramento 5xx
	 * mostram a exceção real.
	 */
	public ApiException(int status, String code, String message, Throwable cause) {
		this(status, code, message, cause, null, 0L, null);
	}

	protected ApiException(int status, String code, String message, String etag) {
		this(status, code, message, null, null, 0L, etag);
	}

	public String etag() {
		return etag;
	}

	public ApiException withEtag(String value) {
		return new ApiException(this.status, this.code, this.getMessage(), this.getCause(), this.detail,
				this.retryAfterSeconds, value);
	}

	public int status() {
		return status;
	}

	public String code() {
		return code;
	}

	/**
	 * Explicação adicional que o cliente usa para escolher a mensagem, nunca dado
	 * sensível.
	 */
	public String detail() {
		return detail;
	}

	public long retryAfterSeconds() {
		return retryAfterSeconds;
	}

	public ApiException withRetryAfter(long seconds) {
		this.retryAfterSeconds = seconds;
		return this;
	}

	public ApiException withDetail(String value) {
		this.detail = value;
		return this;
	}
}