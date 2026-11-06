package com.socialcoisa.rede.feed.domain;

public record Counters(int reactions, int comments, int saves, int shares, int reposts) {

	/** Compatibilidade: construtores antigos sem reposts. */
	public Counters(int reactions, int comments, int saves, int shares) {
		this(reactions, comments, saves, shares, 0);
	}

	public static Counters zero() {
		return new Counters(0, 0, 0, 0, 0);
	}
}
