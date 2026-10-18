package com.socialcoisa.rede.dailygames;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Deterministic perfect maze. Bit flags N=1, E=2, S=4, W=8 denote open passages. */
public final class MazeRules {
    public static final int SIZE = 9;
    private static final int[] DX = {0,1,0,-1}, DY = {-1,0,1,0};
    private MazeRules() {}
    public static int[] generate(long seed) {
        int[] maze = new int[SIZE*SIZE];
        boolean[] visited = new boolean[maze.length];
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        Random random = new Random(seed);
        visited[0]=true; stack.push(0);
        while (!stack.isEmpty()) {
            int cell=stack.peek(), x=cell%SIZE, y=cell/SIZE;
            List<Integer> choices = new ArrayList<>();
            for (int d=0; d<4; d++) {
                int nx=x+DX[d], ny=y+DY[d];
                if (nx>=0 && nx<SIZE && ny>=0 && ny<SIZE && !visited[ny*SIZE+nx]) choices.add(d);
            }
            if (choices.isEmpty()) { stack.pop(); continue; }
            int d=choices.get(random.nextInt(choices.size()));
            int next=(y+DY[d])*SIZE+x+DX[d];
            maze[cell] |= 1<<d; maze[next] |= 1<<((d+2)%4);
            visited[next]=true; stack.push(next);
        }
        return maze;
    }
    public static int move(int[] maze, int position, String direction) {
        int d = switch (direction == null ? "" : direction) { case "UP" -> 0; case "RIGHT" -> 1; case "DOWN" -> 2; case "LEFT" -> 3; default -> -1; };
        if (d < 0 || position < 0 || position >= maze.length || (maze[position] & (1<<d)) == 0) throw new IllegalArgumentException("Movimento invalido");
        return position + DY[d]*SIZE+DX[d];
    }
}
