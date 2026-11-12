package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;

/**
 * A config que o nó recebe tem de ser conferível com o pino que ele guardou no install.
 *
 * <p>O que estes testes fixam, e por quê:</p>
 * <ul>
 * <li>com chave configurada, a assinatura confere com a chave pública publicada — se não conferir,
 * o nó recusa toda config e a rede não sobe;</li>
 * <li>sem chave configurada, {@code signed:false} e {@code signature:null} — nunca mentir para o
 * nó;</li>
 * <li>o {@code issuedAt} vem do carimbo persistido, não do relógio da leitura: duas leituras
 * seguidas dão o mesmo documento, e o runtime não reaplica permissão a cada segundo;</li>
 * <li>nó que não opera recebe lista de tarefas vazia, dito no payload — o runtime não precisa
 * deduzir isso de {@code status}.</li>
 * </ul>
 */
class NetworkConfigServiceTests {

	private final KeyPair pair = generate();

	private static KeyPair generate() {
		try {
			return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		}
		catch (Exception impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static String base64(byte[] encoded) {
		return Base64.getEncoder().encodeToString(encoded);
	}

	private static NetworkConfigService service(String signingKey, String publicKey,
			NetworkNodeRepository.ConfigStamp stamp) {
		var repository = new StubRepository(stamp);
		return new NetworkConfigService(Clock.systemUTC(), repository, signingKey, publicKey);
	}

	private static NetworkNode node(NodeStatus status, Set<NodeProfile> profiles) {
		return new NetworkNode("nd_cfg", "no de teste", "user_1", status, profiles, "sudeste", "BR", "1.0.0",
			Instant.parse("2026-11-12T10:00:00Z"), 4, 8192, 51200, 100, null, null, null, null, 0, null, null);
	}

	@Test
	void comChaveConfiguradaAAssinaturaConfereComOPino() throws Exception {
		var service = service(base64(pair.getPrivate().getEncoded()), base64(pair.getPublic().getEncoded()),
			new NetworkNodeRepository.ConfigStamp(3, Instant.parse("2026-11-12T10:00:00Z")));

		var view = service.forNode(node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_MEDIA)));

		assertThat(view.signed()).isTrue();
		assertThat(view.algorithm()).isEqualTo("Ed25519");
		assertThat(view.configVersion()).isEqualTo(3);
		assertThat(view.issuedAt()).isEqualTo("2026-11-12T10:00:00Z");
		assertThat(view.allowedTasks()).containsExactly("MEDIA_TRANSCODE", "MEDIA_THUMBNAIL");
		// O pino publicado é exatamente a chave que confere a assinatura — o runtime não precisa
		// confiar no transporte desta resposta, mas confere que ela não contradiz o que ele gravar.
		assertThat(view.publicKey()).isEqualTo(base64(pair.getPublic().getEncoded()));

		PublicKey pin = java.security.KeyFactory.getInstance("Ed25519")
			.generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(view.publicKey())));
		var fields = new NetworkConfigSigner.SignedFields(view.formatVersion(), view.configVersion(), view.nodeId(),
			view.profiles(), view.limits().cpuCores(), view.limits().memoryMb(), view.limits().diskMb(),
			view.limits().bandwidthMbps(), view.allowedTasks(), view.issuedAt());
		Signature verifier = Signature.getInstance("Ed25519");
		verifier.initVerify(pin);
		verifier.update(fields.canonical().getBytes(StandardCharsets.UTF_8));
		assertThat(verifier.verify(Base64.getDecoder().decode(view.signature()))).isTrue();
	}

	@Test
	void semChaveConfiguradaAdmiteQueNaoAssinou() {
		var service = service("", "", new NetworkNodeRepository.ConfigStamp(1, Instant.parse("2026-11-12T10:00:00Z")));

		var view = service.forNode(node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_MEDIA)));

		assertThat(view.signed()).isFalse();
		assertThat(view.signature()).isNull();
		assertThat(view.algorithm()).isNull();
		// Sem chave de pinagem configurada não há pino a publicar.
		assertThat(view.publicKey()).isNull();
	}

	@Test
	void chaveDeAssinaturaInvalidaViraSemAssinaturaENaoMinta() {
		var service = service("nao-e-chave-nenhuma", base64(pair.getPublic().getEncoded()),
			new NetworkNodeRepository.ConfigStamp(1, Instant.parse("2026-11-12T10:00:00Z")));

		var view = service.forNode(node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_MEDIA)));

		assertThat(view.signed()).isFalse();
		assertThat(view.signature()).isNull();
		// O pino continua publicado (é chave pública, não é segredo), para o nó detectar a divergência.
		assertThat(view.publicKey()).isEqualTo(base64(pair.getPublic().getEncoded()));
	}

	@Test
	void duasLeiturasSeguidasDevolvemOMesmoDocumento() throws Exception {
		String signatureKey = base64(pair.getPrivate().getEncoded());
		String publicKey = base64(pair.getPublic().getEncoded());
		var stamp = new NetworkNodeRepository.ConfigStamp(4, Instant.parse("2026-11-12T10:00:00Z"));
		var service = service(signatureKey, publicKey, stamp);

		var first = service.forNode(node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_MEDIA)));
		var second = service.forNode(node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_MEDIA)));

		assertThat(second.issuedAt()).isEqualTo(first.issuedAt());
		assertThat(second.configVersion()).isEqualTo(first.configVersion());
		assertThat(second.allowedTasks()).isEqualTo(first.allowedTasks());
		// Ed25519 é determinística: mesma linha canônica, mesma assinatura. Um nó pode comparar
		// assinaturas para detectar mudança sem comparar campo por campo.
		assertThat(second.signature()).isEqualTo(first.signature());
	}

	@Test
	void noQueNaoOperaRecebeListaDeTarefasVazia() {
		var service = service("", "", new NetworkNodeRepository.ConfigStamp(2, Instant.parse("2026-11-12T10:00:00Z")));

		var pending = service.forNode(node(NodeStatus.PENDING_APPROVAL, Set.of(NodeProfile.NODE_MEDIA)));
		assertThat(pending.operable()).isFalse();
		assertThat(pending.allowedTasks()).isEmpty();

		// Perfil vazio também é "não opera": sem perfil não há tipo de tarefa, mesmo ACTIVE.
		var noProfile = service.forNode(node(NodeStatus.ACTIVE, Set.of()));
		assertThat(noProfile.operable()).isFalse();
		assertThat(noProfile.allowedTasks()).isEmpty();
	}

	@Test
	void perfilNodeLocalRecebeTodasAsTarefasNaOrdemDoEnunciado() {
		var service = service("", "", new NetworkNodeRepository.ConfigStamp(1, Instant.parse("2026-11-12T10:00:00Z")));

		var view = service.forNode(node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_LOCAL)));

		// NODE_LOCAL cobre todos; a ordem é a da declaração do enum, estável para o runtime comparar.
		assertThat(view.allowedTasks()).containsExactly("EDGE_CACHE", "EDGE_DELIVER", "ASYNC_JOB", "MEDIA_TRANSCODE",
			"MEDIA_THUMBNAIL", "READ_QUERY", "API_REQUEST");
	}

	/** Só o que o serviço usa: o carimbo. Qualquer outra consulta é defeito, não conveniência. */
	private static final class StubRepository implements NetworkNodeRepository {

		private final ConfigStamp stamp;

		private StubRepository(ConfigStamp stamp) {
			this.stamp = stamp;
		}

		@Override
		public Optional<ConfigStamp> configStamp(String id) {
			return Optional.of(stamp);
		}

		@Override
		public Optional<NetworkNode> findById(String id) {
			throw new AssertionError("a config do no nao relê o agregado: o carimbo basta");
		}

		@Override
		public List<NetworkNode> list(int limit) {
			throw new AssertionError("nao usado");
		}

		@Override
		public void create(String id, String displayName, String ownerUserId, List<NodeProfile> profiles,
				String region, String country, int cpuCores, int memoryMb, int diskMb, int bandwidthMbps,
				Instant now) {
			throw new AssertionError("nao usado");
		}

		@Override
		public void updateStatus(String id, NodeStatus status, String actorId, Instant now) {
			throw new AssertionError("nao usado");
		}

		@Override
		public void touch(String id, String runtimeVersion, Double cpuPercent, Integer memoryMb,
				Integer runningTasks, Instant now) {
			throw new AssertionError("nao usado");
		}

		@Override
		public long countByStatus(NodeStatus status) {
			throw new AssertionError("nao usado");
		}

		@Override
		public List<NetworkNode> findCandidates(Instant seenAfter, int limit) {
			throw new AssertionError("nao usado");
		}

		@Override
		public void recordOutcome(String id, boolean success, Long latencyMs) {
			throw new AssertionError("nao usado");
		}

		@Override
		public void quarantine(String id, String reason, Instant until) {
			throw new AssertionError("nao usado");
		}

		@Override
		public void release(String id) {
			throw new AssertionError("nao usado");
		}

		@Override
		public List<NetworkNode> findQuarantineExpired(Instant now, int limit) {
			throw new AssertionError("nao usado");
		}
	}
}