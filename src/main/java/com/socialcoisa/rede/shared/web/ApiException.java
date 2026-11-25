package com.socialcoisa.rede.shared.web;

public class ApiException extends RuntimeException {

	private final int status;
	private final String code;
	private String detail;
	private long retryAfterSeconds;
	private String etag;

	public String etag() {
		return etag;
	}

	public ApiException withEtag(String value) {
		this.etag = value;
		return this;
	}

	public ApiException(int status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	/** Mesma resposta, com a causa original anexada: o log e o monitoramento 5xx mostram a exceção real. */
	public ApiException(int status, String code, String message, Throwable cause) {
		super(message, cause);
		this.status = status;
		this.code = code;
	}

	public int status() {
		return status;
	}

	public String code() {
		return code;
	}

	/** Explicação adicional que o cliente usa para escolher a mensagem, nunca dado sensível. */
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
