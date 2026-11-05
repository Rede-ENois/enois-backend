package com.socialcoisa.rede.releasenotes.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.releasenotes.application.port.out.ReleaseNoteRepository;
import com.socialcoisa.rede.releasenotes.domain.ReleaseNote;
import com.socialcoisa.rede.releasenotes.domain.ReleaseNoteItem;
import com.socialcoisa.rede.shared.text.PlainText;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * The single "what's new" release notes: publishing replaces, retiring removes.
 *
 * Same shape as the admin notice (NoticeService), but plain text items instead of HTML: the popup
 * is a short list, not a page.
 */
@Service
public class ReleaseNoteService {

	/** The key of the single row; see V225. */
	public static final String RELEASE_NOTE_ID = "ativo";
	static final int MAX_ITEMS = 8;
	static final int MAX_TITLE = 80;
	static final int MAX_DESCRIPTION = 240;

	private final ReleaseNoteRepository releaseNotes;
	private final Clock clock;

	public ReleaseNoteService(ReleaseNoteRepository releaseNotes, Clock clock) {
		this.releaseNotes = releaseNotes;
		this.clock = clock;
	}

	public Optional<ReleaseNote> current() {
		return releaseNotes.findCurrent();
	}

	/**
	 * Publishes (or rewrites) the current release notes. `publishedAt` of the previous version is
	 * kept; `updatedAt` is always now, so an edit reopens the popup for people who closed the old one.
	 */
	public ReleaseNote publish(List<ReleaseNoteItem> rawItems) {
		if (rawItems == null || rawItems.isEmpty() || rawItems.size() > MAX_ITEMS) {
			throw new ApiException(400, "INVALID_RELEASE_ITEMS",
				"A novidade precisa de 1 a " + MAX_ITEMS + " itens.");
		}
		List<ReleaseNoteItem> items = rawItems.stream().map(ReleaseNoteService::clean).toList();
		Instant now = clock.instant();
		Instant publishedAt = releaseNotes.findCurrent().map(ReleaseNote::publishedAt).orElse(now);
		ReleaseNote releaseNote = new ReleaseNote(RELEASE_NOTE_ID, items, publishedAt, now);
		releaseNotes.save(releaseNote);
		return releaseNote;
	}

	/** Takes the release notes down. */
	public void retire() {
		releaseNotes.clear();
	}

	/** Same text defense as a post (see PlainText), checked after defusing so invisible-only input is empty. */
	private static ReleaseNoteItem clean(ReleaseNoteItem raw) {
		String title = defuse(raw == null ? null : raw.title());
		String description = defuse(raw == null ? null : raw.description());
		if (!StringUtils.hasText(title)) {
			throw new ApiException(400, "RELEASE_ITEM_TITLE_REQUIRED", "Cada item precisa de um titulo.");
		}
		if (title.length() > MAX_TITLE) {
			throw new ApiException(400, "RELEASE_ITEM_TITLE_TOO_LONG",
				"O titulo do item tem no maximo " + MAX_TITLE + " caracteres.");
		}
		if (!StringUtils.hasText(description)) {
			throw new ApiException(400, "RELEASE_ITEM_DESCRIPTION_REQUIRED", "Cada item precisa de uma descricao.");
		}
		if (description.length() > MAX_DESCRIPTION) {
			throw new ApiException(400, "RELEASE_ITEM_DESCRIPTION_TOO_LONG",
				"A descricao do item tem no maximo " + MAX_DESCRIPTION + " caracteres.");
		}
		return new ReleaseNoteItem(title, description);
	}

	private static String defuse(String raw) {
		return raw == null ? "" : PlainText.defuse(raw.trim()).trim();
	}
}
