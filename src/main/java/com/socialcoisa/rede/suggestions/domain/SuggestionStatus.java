package com.socialcoisa.rede.suggestions.domain;

/** PENDING espera um moderador; PUBLISHED já virou issue pública; REJECTED nunca sai do app. */
public enum SuggestionStatus {
	PENDING,
	PUBLISHED,
	REJECTED
}
