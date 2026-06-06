package com.socialcoisa.rede.feed.domain;

public record Counters(int reactions, int comments, int saves, int shares) {

	public static Counters zero() {
		return new Counters(0, 0, 0, 0);
	}

	public Counters incrementReactions() {
		return new Counters(reactions + 1, comments, saves, shares);
	}

	public Counters decrementReactions() {
		return new Counters(Math.max(0, reactions - 1), comments, saves, shares);
	}

	public Counters incrementComments() {
		return new Counters(reactions, comments + 1, saves, shares);
	}

	public Counters incrementSaves() {
		return new Counters(reactions, comments, saves + 1, shares);
	}

	public Counters decrementSaves() {
		return new Counters(reactions, comments, Math.max(0, saves - 1), shares);
	}

	public Counters incrementShares() {
		return new Counters(reactions, comments, saves, shares + 1);
	}
}
