package com.socialcoisa.rede.shared.persistence;

import java.util.Arrays;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Recuperacao de migracao FAILED antes de migrar.
 *
 * O DDL do MariaDB nao e transacional: quando uma migracao quebra no meio (foi o caso da V17, que
 * tentava criar uma FK entre collations diferentes), o Flyway deixa a versao marcada como FAILED na
 * flyway_schema_history e passa a recusar QUALQUER migracao seguinte nesse banco. O pod nao sobe, o
 * rollout trava e o cluster continua servindo a versao antiga em silencio.
 *
 * Aqui o boot limpa esse marcador com `repair()` — que so remove entradas FAILED e realinha
 * checksums — e tenta de novo. Sem isso, tirar o backend do ar exigiria alguem com acesso ao banco
 * rodando `flyway repair` na mao.
 *
 * O repair e a ultima tentativa, nao o sucesso: se a migracao reparada continuar pendente ou falhar
 * de novo, o boot termina com erro. Sem essa checagem o pod subia sem ela, servindo a versao nova do
 * codigo sobre o schema velho.
 */
@Configuration(proxyBeanMethods = false)
class FlywayMigrationConfig {

	private static final Logger LOG = LoggerFactory.getLogger(FlywayMigrationConfig.class);

	@Bean
	FlywayMigrationStrategy repairFailedBeforeMigrate() {
		return FlywayMigrationConfig::migrateWithRepair;
	}

	static void migrateWithRepair(Flyway flyway) {
		List<MigrationInfo> failed = Arrays.stream(flyway.info().all())
			.filter(info -> info.getState() != null && info.getState().isFailed())
			.toList();
		if (!failed.isEmpty()) {
			LOG.warn("Flyway encontrou migracao(oes) FAILED {}; limpando o marcador (repair) antes de migrar.",
				failed.stream().map(FlywayMigrationConfig::describe).toList());
			flyway.repair();
		}
		flyway.migrate();
		if (!failed.isEmpty()) {
			refuseToBootWithUnapplied(flyway, failed);
		}
	}

	private static void refuseToBootWithUnapplied(Flyway flyway, List<MigrationInfo> repaired) {
		List<String> unapplied = Arrays.stream(flyway.info().all())
			.filter(FlywayMigrationConfig::neverRan)
			.map(FlywayMigrationConfig::describe)
			.toList();
		if (!unapplied.isEmpty()) {
			throw new IllegalStateException("Migracao(oes) sem aplicacao apos o repair "
				+ repaired.stream().map(FlywayMigrationConfig::describe).toList() + ": " + unapplied
				+ ". O servico nao sobe com o schema antigo.");
		}
	}

	private static boolean neverRan(MigrationInfo info) {
		return switch (info.getState()) {
			case PENDING, FAILED, IGNORED, OUT_OF_ORDER, ABOVE_TARGET -> true;
			default -> false;
		};
	}

	private static String describe(MigrationInfo info) {
		return info.getVersion() + " - " + info.getDescription();
	}
}
