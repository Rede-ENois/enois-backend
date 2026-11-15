package com.socialcoisa.rede.jobs.adapters.in.web;

import java.util.List;
import java.util.Optional;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.jobs.application.ApplicationBoardPageService;
import com.socialcoisa.rede.jobs.domain.JobApplication;
import com.socialcoisa.rede.jobs.domain.JobApplicationStatus;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CursorCodec;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.PageResponse;

/**
 * Tabela paginada das candidaturas de uma vaga (aba "Tabela" e as colunas do board).
 *
 * <p>Mora em arquivo próprio porque {@link SelectionProcessController} já cobre fluxo, board,
 * movimento e notas. A autorização continua no serviço: só o autor da vaga lê a lista.
 */
@RestController
@RequestMapping("/jobs/{jobId}/process/applications")
class ApplicationBoardPageController {

	private final ApplicationBoardPageService pages;
	private final CurrentUserResolver currentUserResolver;
	private final CursorCodec cursorCodec;

	ApplicationBoardPageController(ApplicationBoardPageService pages, CurrentUserResolver currentUserResolver,
			CursorCodec cursorCodec) {
		this.pages = pages;
		this.currentUserResolver = currentUserResolver;
		this.cursorCodec = cursorCodec;
	}

	/**
	 * Uma página de candidaturas, do mais recente para o mais antigo. `cursor` é o valor de
	 * `nextCursor` da página anterior; a resposta só traz novo cursor quando há mais linhas.
	 * `status` filtra pelo estado da candidatura e `search` casa nome, título, mensagem e o resto
	 * do retrato do candidato. Um `status` desconhecido é 400, não uma lista vazia silenciosa.
	 */
	@GetMapping
	PageResponse<BoardApplicationView> page(@PathVariable String jobId,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "30") int limit,
			@RequestParam(required = false) String status, @RequestParam(required = false) String search,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Optional<CursorCodec.PageCursor> pageCursor = cursorCodec.decodePage(cursor);
		Long beforeId = pageCursor.map(decoded -> parseCursorId(decoded.tieBreak())).orElse(null);
		var page = pages.page(jobId, user.id().value(),
			pageCursor.map(CursorCodec.PageCursor::instant).orElse(null), beforeId,
			parseStatus(status), search, limit);
		List<BoardApplicationView> views = page.rows().stream().map(BoardApplicationView::of).toList();
		// Cursor só quando a página veio cheia: é a única indicação de que pode haver mais.
		boolean full = page.lastAppliedAt() != null && views.size() >= Math.clamp(limit, 1,
			ApplicationBoardPageService.MAX_PAGE_SIZE);
		String nextCursor = full
			? cursorCodec.encodePage(page.lastAppliedAt(), String.valueOf(page.lastId())) : null;
		return new PageResponse<>(views, nextCursor);
	}

	private static JobApplicationStatus parseStatus(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return JobApplicationStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
		}
		catch (IllegalArgumentException unknown) {
			throw new ApiException(400, "INVALID_APPLICATION_STATUS",
				"Estado desconhecido. Use APPLIED, IN_REVIEW, ADVANCED ou NOT_SELECTED.");
		}
	}

	private static Long parseCursorId(String tieBreak) {
		try {
			return tieBreak == null ? null : Long.valueOf(tieBreak);
		}
		catch (NumberFormatException invalid) {
			throw new ApiException(400, "INVALID_CURSOR", "Cursor inválido.");
		}
	}

	/** Linha da tabela: a candidatura e onde ela está no fluxo. */
	record BoardApplicationView(String applicationId, String userId, String status, String appliedAt,
			String stageId, boolean rejected, String lastMovementAt) {

		static BoardApplicationView of(ApplicationBoardPageService.BoardRow row) {
			JobApplication application = row.application();
			var position = row.position();
			return new BoardApplicationView(application.id(), application.userId(), application.status().name(),
				application.appliedAt() == null ? null : application.appliedAt().toString(),
				position == null ? null : position.stageId(), position != null && position.rejected(),
				position == null || position.lastMovementAt() == null ? null
					: position.lastMovementAt().toString());
		}
	}

}
