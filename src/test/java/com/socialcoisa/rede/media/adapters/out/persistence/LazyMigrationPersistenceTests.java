package com.socialcoisa.rede.media.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaUrlMigrationRepository;
import com.socialcoisa.rede.media.application.port.out.MediaUrlMigrationRepository.Status;
import com.socialcoisa.rede.media.application.port.out.MediaUrlReferenceUpdater;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaStorageProvider;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import jakarta.persistence.EntityManager;

/**
 * SQL da migração oportunista no banco de verdade (H2): claim de asset aceito UMA vez, fluxo de
 * status da URL (FAILED pode tentar de novo; COPIED->ACTIVE) e troca de referência por CAS que
 * nunca sobrescreve mudança concorrente.
 */
@SpringBootTest
@AutoConfigureMockMvc
// Sem @Transactional de propósito: o helper de autenticação cria a conta por uma requisição
// MockMvc e o TestDatabase a lê por outro JdbcTemplate. Numa transação de teste a conta fica
// não-commitada e a leitura (conexão diferente quando o contexto é trocado) não a vê — era o
// "usuario nao criado" que só aparecia na suíte completa.
@Tag("critico")
class LazyMigrationPersistenceTests {

	@Autowired
	private MediaAssetRepository assets;

	@Autowired
	private MediaUrlMigrationRepository migrations;

	@Autowired
	private MediaUrlReferenceUpdater references;

	@Autowired
	private EntityManager em;

	/** Só o update direto precisa de transação; a autenticação precisa COMMITAR (ver nota acima). */
	@Autowired
	private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void claimDoAssetEVencedorUnico() {
		Instant agora = Instant.now();
		MediaAsset asset = MediaAsset.pending("media_claim_test", new UserId("user_claim"), "foto.png",
			"image/png", 10, "POST_IMAGE", "uploads/media_claim_test/foto.png",
			"/uploads/media_claim_test/foto.png", agora.minusSeconds(7200), agora.minusSeconds(3600),
			MediaStorageProvider.IMAGEKIT);
		assets.save(asset);

		assertThat(assets.claimForS3Copy("media_claim_test", agora, agora.minusSeconds(600), 5)).isTrue();
		// Claim fresco de outra réplica NUNCA ganha o mesmo asset.
		assertThat(assets.claimForS3Copy("media_claim_test", agora, agora.minusSeconds(600), 5)).isFalse();
	}

	@Test
	void fluxoDeStatusDaUrlComRetentativa() {
		Instant agora = Instant.now();
		String hash = "hash_fluxo_teste";
		migrations.ensurePending(hash, "https://ik.imagekit.io/socialcoisa/uploads/a/b.png", agora);

		assertThat(migrations.claim(hash, agora, agora.minusSeconds(600), 5)).isTrue();
		assertThat(migrations.claim(hash, agora, agora.minusSeconds(600), 5)).isFalse();

		migrations.markFailed(hash, "origem respondeu 404", agora);
		assertThat(migrations.findByHash(hash).orElseThrow().status()).isEqualTo(Status.FAILED);
		// FAILED volta para a fila (tentativa nova).
		assertThat(migrations.claim(hash, agora, agora.minusSeconds(600), 5)).isTrue();

		migrations.markCopied(hash, "https://cdn.exemplo.com/uploads/a/b.png", agora);
		assertThat(migrations.findByHash(hash).orElseThrow().status()).isEqualTo(Status.COPIED);
		migrations.markActive(hash, agora);
		assertThat(migrations.findByHash(hash).orElseThrow().status()).isEqualTo(Status.ACTIVE);
	}

	@Test
	void referenciaTrocaPorCasESeguraContraCorrida() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("media_ref_cas");
		String antiga = "https://ik.imagekit.io/socialcoisa/uploads/ref/velha.png";
		String nova = "https://cdn.exemplo.com/uploads/ref/velha.png";
		transactionTemplate.executeWithoutResult(status ->
			em.createNativeQuery("update users set avatar = :a where id = :id")
				.setParameter("a", antiga)
				.setParameter("id", user.userId())
				.executeUpdate());

		assertThat(references.findLegacyUrls(500)).contains(antiga);
		assertThat(references.updateReferences(antiga, nova)).isEqualTo(1);
		// CAS: a antiga já não existe mais — segunda passada não mexe em nada.
		assertThat(references.updateReferences(antiga, nova)).isZero();

		// Mudança CONCORRENTE (a pessoa trocou a foto no meio da migração): a referência nova
		// nunca é sobrescrita pela migração do objeto antigo.
		String concorrente = "https://ik.imagekit.io/socialcoisa/uploads/ref/outra.png";
		transactionTemplate.executeWithoutResult(status ->
			em.createNativeQuery("update users set avatar = :a where id = :id")
				.setParameter("a", concorrente)
				.setParameter("id", user.userId())
				.executeUpdate());
		assertThat(references.updateReferences(antiga, nova)).isZero();
	}
}
