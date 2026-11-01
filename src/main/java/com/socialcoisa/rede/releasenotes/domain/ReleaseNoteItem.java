package com.socialcoisa.rede.releasenotes.domain;

/** One entry of the release notes: plain text, already trimmed and defused. */
public record ReleaseNoteItem(String title, String description) {
}
