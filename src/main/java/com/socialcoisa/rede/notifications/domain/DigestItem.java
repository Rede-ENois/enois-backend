package com.socialcoisa.rede.notifications.domain;

/**
 * One line of the weekly digest: what it is, where it comes from, and the app path that opens it.
 * {@code score} only orders the posts (reactions plus comments); it is never shown, the digest
 * does not publish engagement numbers.
 */
public record DigestItem(String title, String context, String path, int score) {
}
