package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.shared.security.Hashes;

/**
 * Migra as sessões criadas antes do hashing: troca token/refresh em claro pelo SHA-256 no próprio
 * registro. É idempotente (64 hex é ignorado) e não invalida ninguém — o cliente continua mandando
 * o token cru e o adapter calcula o hash na busca. Roda no boot, antes do pod ficar ready.
 */
@Component
class SessionTokenHashMigration implements ApplicationRunner {

	private static final Logger LOG = LoggerFactory.getLogger(SessionTokenHashMigration.class);
	private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");

	private final SpringDataAuthSessionJpaRepository repository;

	SessionTokenHashMigration(SpringDataAuthSessionJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		rehashLegacyRows();
	}

	/** Cobre sessões cruas criadas por um pod antigo durante o rolling update. */
	@Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
	@Transactional
	public void rehashNewLegacyRows() {
		rehashLegacyRows();
	}

	private void rehashLegacyRows() {
		int migrated = 0;
		// Só as linhas ainda cruas voltam do banco: hash tem sempre 64 caracteres, então a
		// consulta dirigida devolve vazio numa frota saudável. O findAll antigo carregava TODA a
		// auth_sessions (uma linha por login) a cada rodada só para descartar tudo em Java.
		for (AuthSessionJpaEntity session : repository.findLegacyRawSessions()) {
			String token = session.token();
			if (token == null || HASH.matcher(token).matches()) {
				continue;
			}
			String refresh = session.refreshToken();
			repository.rehash(token, Hashes.sha256Hex(token),
				refresh == null ? null : Hashes.sha256Hex(refresh));
			migrated++;
		}
		if (migrated > 0) {
			LOG.info("Sessoes migradas para hash (credenciais em claro removidas do banco): {}", migrated);
		}
	}
}
