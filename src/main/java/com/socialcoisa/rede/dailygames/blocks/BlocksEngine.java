package com.socialcoisa.rede.dailygames.blocks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Version 2: 100 ms ticks, automatic gravity each five ticks; caller owns the clock. */
public final class BlocksEngine {

	public static final int WIDTH = 10;
	public static final int HEIGHT = 20;
	public static final int MAX_TICKS = 6_000;
	public static final int MAX_ACTIONS = MAX_TICKS;
	public static final int TICK_MILLIS = 100;
	public static final int RULES_VERSION = 2;

	public enum Action { LEFT, RIGHT, ROTATE, SOFT_DROP, HARD_DROP, WAIT }
	public enum Status { PLAYING, WON, LOST, TIMED_OUT }
	public record Event(int tick, Action action) {}
	public record Cell(int x, int y) {}
	public record Result(Status status, int score, int lines, int actions, int elapsedTicks, List<String> board,
		List<Cell> activeCells, List<Cell> nextCells, int rulesVersion, int piecesSpawned) {}

	// Rotation is clockwise around the origin. No wall kicks; O does not rotate.
	private static final int[][][] SHAPES = {
		{{0, 0}, {1, 0}, {2, 0}, {3, 0}},
		{{0, 0}, {1, 0}, {0, 1}, {1, 1}},
		{{0, 0}, {1, 0}, {2, 0}, {1, 1}},
		{{0, 0}, {1, 0}, {1, 1}, {2, 1}},
		{{1, 0}, {2, 0}, {0, 1}, {1, 1}},
		{{0, 0}, {0, 1}, {1, 1}, {2, 1}},
		{{2, 0}, {0, 1}, {1, 1}, {2, 1}}
	};

	private BlocksEngine() {}

	/** Replays a bounded action sequence. Seed and target must come from the server challenge. */
	public static Result replay(long seed, int targetLines, List<Event> actions, int elapsedTicks) {
		if (targetLines < 1 || targetLines > 100 || actions == null || actions.size() > MAX_ACTIONS
				|| elapsedTicks < 0 || elapsedTicks > MAX_TICKS) {
			throw new IllegalArgumentException("Invalid target or replay length");
		}
		int previous = 0;
		for (Event event : actions) {
			if (event == null || event.action() == null || event.tick() <= previous || event.tick() > elapsedTicks)
				throw new IllegalArgumentException("Invalid event order or tick");
			previous = event.tick();
		}
		Game game = new Game(seed, targetLines);
		int tick = 0;
		for (Event event : actions) {
			if (tick >= elapsedTicks) break;
			while (tick < event.tick() && tick < elapsedTicks && game.status == Status.PLAYING) {
				tick++;
				if (tick % 5 == 0) game.gravity();
			}
			if (game.status == Status.PLAYING && tick >= event.tick()) game.step(event.action());
		}
		while (tick < elapsedTicks && game.status == Status.PLAYING) {
			tick++;
			if (tick % 5 == 0) game.gravity();
		}
		if (tick == MAX_TICKS && game.status == Status.PLAYING) game.status = Status.TIMED_OUT;
		return game.result(actions.size(), tick);
	}

	/** Only terminal replays can be submitted as competitive results. */
	public static Result validateCompleted(long seed, int targetLines, List<Event> actions, int elapsedTicks) {
		Result result = replay(seed, targetLines, actions, elapsedTicks);
		if (result.status() == Status.PLAYING) throw new IllegalArgumentException("Incomplete replay");
		return result;
	}

	/** Piece order for a seed, using the same bag shuffle as replay. */
	public static List<Integer> pieceSequence(long seed, int count) {
		return pieceSequence(seed, count, 0);
	}

	/**
	 * Official upcoming pieces for a session: same shuffle as replay, advanced past the
	 * {@code piecesAlreadySpawned} consumed by the game so far. Exposed so the client plays the
	 * real daily sequence; the server still owns the clock and validates by replay.
	 */
	public static List<Integer> pieceSequence(long seed, int count, int piecesAlreadySpawned) {
		if (count < 0 || count > MAX_ACTIONS || piecesAlreadySpawned < 0) throw new IllegalArgumentException("Invalid sequence length");
		Random random = new Random(seed);
		List<Integer> bag = new ArrayList<>();
		List<Integer> out = new ArrayList<>(count);
		for (int i = 0; i < piecesAlreadySpawned + count; i++) {
			if (bag.isEmpty()) {
				for (int s = 0; s < SHAPES.length; s++) bag.add(s);
				Collections.shuffle(bag, random);
			}
			int piece = bag.remove(bag.size() - 1);
			if (i >= piecesAlreadySpawned) out.add(piece);
		}
		return List.copyOf(out);
	}

	private static final class Game {
		private final boolean[][] board = new boolean[HEIGHT][WIDTH];
		private final Random random;
		private final List<Integer> bag = new ArrayList<>();
		private final int target;
		private int piece, rotation, x, y, lines, score, spawned;
		private Status status = Status.PLAYING;

		Game(long seed, int target) { this.random = new Random(seed); this.target = target; spawn(); }

		void spawn() {
			refill();
			piece = bag.remove(bag.size() - 1); rotation = 0; x = 3; y = 0; spawned++;
			refill();
			if (!fits(x, y, rotation)) status = Status.LOST;
		}

		void refill() {
			if (bag.isEmpty()) {
				for (int i = 0; i < SHAPES.length; i++) bag.add(i);
				Collections.shuffle(bag, random);
			}
		}

		void step(Action action) {
			switch (action) {
				case LEFT -> { if (fits(x - 1, y, rotation)) x--; }
				case RIGHT -> { if (fits(x + 1, y, rotation)) x++; }
				case ROTATE -> { if (fits(x, y, (rotation + 1) % 4)) rotation = (rotation + 1) % 4; }
				case HARD_DROP -> { while (fits(x, y + 1, rotation)) y++; lock(); return; }
				case SOFT_DROP -> gravity();
				case WAIT -> { }
			}
		}

		void gravity() {
			if (fits(x, y + 1, rotation)) y++; else lock();
		}

		int[][] cells(int r) {
			int[][] cells = new int[4][2];
			for (int i = 0; i < 4; i++) {
				int cx = SHAPES[piece][i][0], cy = SHAPES[piece][i][1];
				if (piece != 1) for (int turn = 0; turn < r; turn++) { int old = cx; cx = -cy; cy = old; }
				cells[i][0] = cx; cells[i][1] = cy;
			}
			return cells;
		}

		boolean fits(int px, int py, int r) {
			for (int[] cell : cells(r)) {
				int bx = px + cell[0], by = py + cell[1];
				if (bx < 0 || bx >= WIDTH || by < 0 || by >= HEIGHT || board[by][bx]) return false;
			}
			return true;
		}

		void lock() {
			for (int[] cell : cells(rotation)) board[y + cell[1]][x + cell[0]] = true;
			int cleared = 0;
			for (int row = HEIGHT - 1; row >= 0; row--) {
				boolean full = true;
				for (boolean occupied : board[row]) full &= occupied;
				if (full) {
					for (int above = row; above > 0; above--) board[above] = board[above - 1];
					board[0] = new boolean[WIDTH]; cleared++; row++;
				}
			}
			lines += cleared;
			score += switch (cleared) { case 1 -> 100; case 2 -> 300; case 3 -> 500; case 4 -> 800; default -> 0; };
			if (lines >= target) status = Status.WON; else spawn();
		}

		Result result(int count, int ticks) {
			List<String> rows = new ArrayList<>();
			for (boolean[] row : board) {
				StringBuilder line = new StringBuilder(WIDTH);
				for (boolean cell : row) line.append(cell ? '#' : '.');
				rows.add(line.toString());
			}
			List<Cell> active = new ArrayList<>();
			if (status == Status.PLAYING) {
				for (int[] cell : cells(rotation)) active.add(new Cell(x + cell[0], y + cell[1]));
			}
			List<Cell> next = new ArrayList<>();
			if (status == Status.PLAYING) {
				for (int[] cell : SHAPES[bag.get(bag.size() - 1)]) next.add(new Cell(cell[0], cell[1]));
			}
			return new Result(status, score, lines, count, ticks, List.copyOf(rows), List.copyOf(active),
				List.copyOf(next), RULES_VERSION, spawned);
		}
	}
}
