package com.socialcoisa.rede.moderation.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.moderation.domain.UserSanction;

/**
 * A suspensão tem prazo de revisão (a graça que o worker usa para virar ban) e é exatamente esse
 * prazo que o usuário punido precisa ver — a punição sem data é a que vira ticket de suporte.
 * O `dueAt` do view da listagem de penalidades carrega a data; restrição e ban ficam sem data.
 */
@Tag("critico")
class SanctionViewDueAtTests {

	private UserSanction active(String type, Instant createdAt) {
		return new UserSanction("s1", "u1", null,
			com.socialcoisa.rede.moderation.domain.SanctionType.valueOf(type), "Motivo de teste.",
			com.socialcoisa.rede.moderation.domain.SanctionStatus.ACTIVE, createdAt, null, null);
	}

	@Test
	void suspensaoAtivaGanhaOPrazoDaGraca() {
		Instant createdAt = Instant.parse("2090-01-01T12:00:00Z");
		var view = SanctionController.SanctionView.from(active("SUSPENDED", createdAt), Duration.ofDays(7));
		assertThat(view.dueAt()).isEqualTo(createdAt.plus(Duration.ofDays(7)).toString());
		assertThat(view.type()).isEqualTo("SUSPENDED");
		assertThat(view.reason()).isEqualTo("Motivo de teste.");
	}

	@Test
	void restricaoEBanFicamSemPrazo() {
		Instant createdAt = Instant.parse("2090-01-01T12:00:00Z");
		assertThat(SanctionController.SanctionView.from(active("RESTRICTION", createdAt), Duration.ofDays(7)).dueAt()).isNull();
		assertThat(SanctionController.SanctionView.from(active("BAN", createdAt), Duration.ofDays(7)).dueAt()).isNull();
		assertThat(SanctionController.SanctionView.from(active("PERMABAN", createdAt), Duration.ofDays(7)).dueAt()).isNull();
	}

	@Test
	void suspensaoRemovidaNaoGanhaPrazo() {
		Instant createdAt = Instant.parse("2090-01-01T12:00:00Z");
		var lifted = new UserSanction("s2", "u1", null,
			com.socialcoisa.rede.moderation.domain.SanctionType.SUSPENDED, "Motivo de teste.",
			com.socialcoisa.rede.moderation.domain.SanctionStatus.REMOVED, createdAt,
			createdAt.plus(Duration.ofDays(1)), "revisor");
		assertThat(SanctionController.SanctionView.from(lifted, Duration.ofDays(7)).dueAt()).isNull();
	}
}
