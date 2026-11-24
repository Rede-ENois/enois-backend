package com.socialcoisa.rede.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Formato da configuração da varredura: 5 partes e identificadores válidos. */
@Tag("critico")
class SuspiciousContentTargetsTests {

	@Test
	void parsesTargetsAndTextColumns() {
		var parsed = SuspiciousContentTargets.parseTargets(
			"communities:created_by:id:name|description:created_at;"
				+ "freelances:user_id:id:title|scope:created_at;entrada-invalida");

		assertThat(parsed).hasSize(2);
		assertThat(parsed.get(0).table()).isEqualTo("communities");
		assertThat(parsed.get(0).authorColumn()).isEqualTo("created_by");
		assertThat(parsed.get(0).textColumns()).containsExactly("name", "description");
		assertThat(parsed.get(1).createdColumn()).isEqualTo("created_at");
	}

	@Test
	void rejectsIdentifiersThatAreNotSafeColumnNames() {
		assertThat(SuspiciousContentTargets.parseTargets(
			"communities:created_by:id:name;drop table users:created_at")).isEmpty();
	}

	@Test
	void patternsAreLowercasedAndDeduplicatedByTheCaller() {
		assertThat(SuspiciousContentTargets.parsePatterns("<SCRIPT, Mass Assignment ,,onerror="))
			.containsExactly("<script", "mass assignment", "onerror=");
	}
}
