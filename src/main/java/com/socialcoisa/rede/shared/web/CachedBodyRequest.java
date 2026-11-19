package com.socialcoisa.rede.shared.web;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Corpo lido uma vez e reapresentado a quem vem depois (o InputStream do Servlet é de uso único).
 *
 * <p>Dois leitores precisam do corpo antes do controller: o detector de POST duplicado (assinatura
 * do envio) e a linha de base de recusa administrativa (assinatura do acesso, quando a resposta já
 * sai 401/403). Quem lê tem de devolver o corpo exatamente como veio.</p>
 */
final class CachedBodyRequest extends HttpServletRequestWrapper {

	private final byte[] body;

	CachedBodyRequest(HttpServletRequest request, byte[] body) {
		super(request);
		this.body = body;
	}

	@Override
	public ServletInputStream getInputStream() {
		ByteArrayInputStream input = new ByteArrayInputStream(body);
		return new ServletInputStream() {
			@Override public boolean isFinished() { return input.available() == 0; }
			@Override public boolean isReady() { return true; }
			@Override public void setReadListener(ReadListener listener) { }
			@Override public int read() { return input.read(); }
		};
	}

	@Override
	public BufferedReader getReader() {
		return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
	}
}