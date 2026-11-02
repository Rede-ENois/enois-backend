package com.socialcoisa.rede.releasenotes.domain;

import java.time.Instant;
import java.util.List;

/**
 * The current "what's new" release notes, written by an administrator.
 *
 * `publishedAt` marks the first publication and survives edits; `updatedAt` changes on every publish
 * and is the version identity: the front shows the popup once per (id + updatedAt).
 */
public record ReleaseNote(String id, List<ReleaseNoteItem> items, Instant publishedAt, Instant updatedAt) {

	public ReleaseNote {
		items = List.copyOf(items);
	}
}
