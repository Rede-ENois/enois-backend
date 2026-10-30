package com.socialcoisa.rede.shared.web;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The small HTML page answered to a link clicked from an e-mail (unsubscribe flows): whoever
 * clicks comes from a mail client, not from the app, so a JSON body would be useless.
 */
public final class ConfirmationPage {

	private static final String HOME = "https://enois.ia.br";

	private ConfirmationPage() {
	}

	/** {@code title} and {@code message} are fixed server copy, never user input. */
	public static ResponseEntity<String> of(boolean ok, String title, String message) {
		String html = """
			<!DOCTYPE html><html lang="pt-BR"><head><meta charset="UTF-8">
			<meta name="viewport" content="width=device-width, initial-scale=1.0"><title>%s</title></head>
			<body style="margin:0;background:#f8f7ff;font-family:'Inter',system-ui,sans-serif;display:grid;place-items:center;min-height:100vh">
			<div style="max-width:440px;background:#fff;border-radius:16px;padding:32px;margin:16px;box-shadow:0 8px 24px -8px rgba(99,102,241,.15);text-align:center">
			<h1 style="margin:0 0 12px;color:#1a1a2e;font-size:20px">%s</h1>
			<p style="margin:0 0 20px;color:#4b5563;font-size:14px;line-height:1.6">%s</p>
			<a href="%s" style="display:inline-block;padding:12px 24px;background:linear-gradient(135deg,#6366f1,#8b5cf6);color:#fff;text-decoration:none;border-radius:999px;font-weight:600;font-size:14px">Ir para o Énóis</a>
			</div></body></html>
			""".formatted(title, title, message, HOME);
		return ResponseEntity.status(ok ? 200 : 400).contentType(MediaType.TEXT_HTML).body(html);
	}
}
