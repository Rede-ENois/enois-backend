package com.socialcoisa.rede.feed.domain;

public record Counters(int reactions, int comments, int saves, int shares, int reposts) {

	/** Compatibilidade: construtores antigos sem reposts. */
	public Counters(int reactions, int comments, int saves, int shares) {
		this(reactions, comments, saves, shares, 0);
	}

	public static Counters zero() {
		return new Counters(0, 0, 0, 0, 0);
	}

	public Counters incrementReactions() {
		return new Counters(reactions + 1, comments, saves, shares, reposts);
	}

	public Counters decrementReactions() {
		return new Counters(Math.max(0, reactions - 1), comments, saves, shares, reposts);
	}

	public Counters incrementComments() {
		return new Counters(reactions, comments + 1, saves, shares, reposts);
	}

	public Counters decrementComments() {
		return new Counters(reactions, Math.max(0, comments - 1), saves, shares, reposts);
	}

	public Counters incrementSaves() {
		return new Counters(reactions, comments, saves + 1, shares, reposts);
	}

	public Counters decrementSaves() {
		return new Counters(reactions, comments, Math.max(0, saves - 1), shares, reposts);
	}

	public Counters incrementShares() {
		return new Counters(reactions, comments, saves, shares + 1, reposts);
	}

	public Counters incrementReposts() {
		return new Counters(reactions, comments, saves, shares, reposts + 1);
	}

	public Counters decrementReposts() {
		return new Counters(reactions, comments, saves, shares, Math.max(0, reposts - 1));
	}
}
