package com.socialcoisa.rede.shared.web;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
class RequestObservabilityFilter extends OncePerRequestFilter {

	static final String REQUEST_ID_HEADER = "X-Request-Id";

	private static final Logger LOGGER = LoggerFactory.getLogger(RequestObservabilityFilter.class);

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String requestId = requestId(request);
		long startedAt = System.nanoTime();
		MDC.put("requestId", requestId);
		response.setHeader(REQUEST_ID_HEADER, requestId);
		try {
			filterChain.doFilter(request, response);
		}
		finally {
			long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
			LOGGER.info("http_request requestId={} method={} path={} status={} durationMs={}", requestId,
				request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs);
			MDC.remove("requestId");
		}
	}

	private String requestId(HttpServletRequest request) {
		String header = request.getHeader(REQUEST_ID_HEADER);
		if (header != null && !header.isBlank()) {
			return header.trim();
		}
		return UUID.randomUUID().toString().replace("-", "");
	}
}
