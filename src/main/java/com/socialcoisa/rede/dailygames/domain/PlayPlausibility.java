package com.socialcoisa.rede.dailygames.domain;

/**
 * Server-side floor on how fast a daily game can be finished by a person.
 *
 * A finished run under the floor still ends the game for the player, but the session stores
 * {@link #TOO_FAST} and the run stays out of the public ranking and the prize queue. The floors sit
 * well below a fast human on purpose: they separate scripted runs from skill, never the other way.
 */
public final class PlayPlausibility {

	/** Value stored in the session's {@code integrity_flag} column. */
	public static final String TOO_FAST = "TOO_FAST";

	/**
	 * Maze: each move is one request answered before the next is sent, so even holding a key a
	 * person stays far above 20 steps per second over a whole route.
	 */
	public static final long MAZE_MILLIS_PER_STEP = 50;

	/** Words: typing five letters and confirming takes longer than this per guess. */
	public static final long WORD_MILLIS_PER_GUESS = 750;

	private PlayPlausibility() {
	}

	/** {@code shortestRoute} is the number of steps of the maze's only route from entrance to exit. */
	public static boolean mazeTooFast(long elapsedMillis, int shortestRoute) {
		return elapsedMillis < shortestRoute * MAZE_MILLIS_PER_STEP;
	}

	public static boolean wordTooFast(long elapsedMillis, int attempts) {
		return elapsedMillis < attempts * WORD_MILLIS_PER_GUESS;
	}
}
