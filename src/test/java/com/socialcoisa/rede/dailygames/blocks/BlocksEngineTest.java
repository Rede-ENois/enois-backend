package com.socialcoisa.rede.dailygames.blocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlocksEngineTest {
    @Test void gravityAdvancesWithoutInputAndIsDeterministic() {
        var initial = BlocksEngine.replay(42, 10, List.of(), 0);
        var moved = BlocksEngine.replay(42, 10, List.of(), 5);
        assertThat(moved.activeCells().get(0).y()).isEqualTo(initial.activeCells().get(0).y() + 1);
        assertThat(BlocksEngine.replay(42, 10, List.of(), 5)).isEqualTo(moved);
        assertThat(moved.activeCells()).hasSize(4);
        assertThat(moved.nextCells()).hasSize(4);
        assertThat(moved.rulesVersion()).isEqualTo(2);
    }
    @Test void hardDropsEventuallyLoseAndValidate() {
        List<BlocksEngine.Event> events = new ArrayList<>();
        BlocksEngine.Result result;
        do {
            events.add(new BlocksEngine.Event(events.size() + 1, BlocksEngine.Action.HARD_DROP));
            result = BlocksEngine.replay(9, 100, events, events.size());
        } while (result.status() == BlocksEngine.Status.PLAYING && events.size() < 30);
        assertThat(result.status()).isEqualTo(BlocksEngine.Status.LOST);
        assertThat(result.activeCells()).isEmpty();
        assertThat(BlocksEngine.validateCompleted(9, 100, events, events.size())).isEqualTo(result);
        events.add(new BlocksEngine.Event(events.size() + 1, BlocksEngine.Action.WAIT));
        var afterEnd = BlocksEngine.replay(9, 100, events, events.size() + 1);
        assertThat(afterEnd.status()).isEqualTo(BlocksEngine.Status.LOST);
        assertThat(afterEnd.board()).isEqualTo(result.board());
        assertThat(afterEnd.score()).isEqualTo(result.score());
    }
    @Test void rejectsIncompleteAndInvalidTimeOrEventOrder() {
        assertThatIllegalArgumentException().isThrownBy(() -> BlocksEngine.validateCompleted(1, 10, List.of(), 0));
        assertThatIllegalArgumentException().isThrownBy(() -> BlocksEngine.replay(1, 0, List.of(), 0));
        assertThatIllegalArgumentException().isThrownBy(() -> BlocksEngine.replay(1, 1, null, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> BlocksEngine.replay(1, 1, List.of(), 6001));
        var event = new BlocksEngine.Event(1, BlocksEngine.Action.LEFT);
        assertThatIllegalArgumentException().isThrownBy(() -> BlocksEngine.replay(1, 1, List.of(event, event), 1));
        assertThatIllegalArgumentException().isThrownBy(() -> BlocksEngine.replay(1, 1, List.of(event), 0));
    }
    @Test void blockedMovementDoesNotLeaveBoard() {
        var events = new ArrayList<BlocksEngine.Event>();
        for (int i = 1; i <= 15; i++) events.add(new BlocksEngine.Event(i, BlocksEngine.Action.LEFT));
        var result = BlocksEngine.replay(22, 10, events, 15);
        assertThat(result.activeCells()).allSatisfy(cell -> assertThat(cell.x()).isBetween(0, 9));
    }
}
