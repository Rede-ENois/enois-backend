package com.socialcoisa.rede.notices.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.notices.application.port.out.AdminNoticeRepository;
import com.socialcoisa.rede.notices.domain.AdminNotice;
import com.socialcoisa.rede.notices.domain.NoticeSurface;
import com.socialcoisa.rede.shared.html.SafeHtml;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O aviso único da plataforma: publicar substitui, limpar remove.
 *
 * Sem rascunho nem agendamento de propósito — o painel é superfície de operação, e a regra mais
 * simples que atende "o admin escreve e aparece" é essa. Aviso programado pede uma fila que o
 * projeto ainda não tem.
 */
@Service
public class NoticeService {

	/** A chave da linha única; ver V197. */
	public static final String NOTICE_ID = "ativo";
	static final int MAX_TITLE = 140;

	private final AdminNoticeRepository notices;
	private final Clock clock;

	public NoticeService(AdminNoticeRepository notices, Clock clock) {
		this.notices = notices;
		this.clock = clock;
	}

	public Optional<AdminNotice> current() {
		return notices.findCurrent();
	}

	/**
	 * Publica (ou reescreve) o aviso vigente. O `publishedAt` da versão anterior é preservado: ele
	 * diz desde quando a plataforma está naquele aviso. Quem muda a cada edição é `updatedAt`, e é
	 * ele que reabre o popup de quem já tinha fechado a versão velha — a tela guarda a dispensa por
	 * (id + updatedAt), então uma republicação é, para quem lê, um aviso novo.
	 */
	public AdminNotice publish(String rawTitle, String rawBodyHtml, NoticeSurface surface) {
		String title = rawTitle == null ? "" : rawTitle.trim();
		if (!StringUtils.hasText(title)) {
			throw new ApiException(400, "INVALID_NOTICE_TITLE", "O aviso precisa de um titulo.");
		}
		if (title.length() > MAX_TITLE) {
			throw new ApiException(400, "NOTICE_TITLE_TOO_LONG",
				"O titulo do aviso tem no maximo " + MAX_TITLE + " caracteres.");
		}
		if (surface == null) {
			throw new ApiException(400, "INVALID_NOTICE_SURFACE",
				"Escolha onde o aviso aparece: banner, modal ou os dois.");
		}
		Instant now = clock.instant();
		Instant publishedAt = notices.findCurrent().map(AdminNotice::publishedAt).orElse(now);
		// requirePageHtml: o mesmo crivo de um post do tipo página, incluindo o CSS do <style>.
		// A tela renderiza no iframe isolado, mas sanitizar aqui é o que impede o painel de admin
		// de virar vetor de script contra qualquer visitante — o editor do admin não é confiável.
		AdminNotice notice = AdminNotice.of(NOTICE_ID, title, SafeHtml.requirePageHtml(rawBodyHtml), surface,
			publishedAt, now);
		notices.save(notice);
		return notice;
	}

	/** Tira o aviso do ar. As dispensas no aparelho de cada um não voltam a valer: não há aviso. */
	public void retire() {
		notices.clear();
	}
}
