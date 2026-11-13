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

import com.socialcoisa.rede.network.application.port.out.NetworkAuthorityRepository;
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
		return service(signingKey, publicKey, stamp, 0, 300);
	}

	private static NetworkConfigService service(String signingKey, String publicKey,
			NetworkNodeRepository.ConfigStamp stamp, int term) {
		return service(signingKey, publicKey, stamp, term, 300);
	}

	private static NetworkConfigService service(String signingKey, String publicKey,
			NetworkNodeRepository.ConfigStamp stamp, int term, int ttlSeconds) {
		return service(signingKey, publicKey, stamp, term, ttlSeconds, "");
	}

	private static NetworkConfigService service(String signingKey, String publicKey,
			NetworkNodeRepository.ConfigStamp stamp, int term, int ttlSeconds, String allowedRoutes) {
		var repository = new StubRepository(stamp);
		NetworkAuthorityRepository authorities = new NetworkAuthorityRepository() {
			@Override
			public java.util.Optional<AuthorityRecord> current() {
				return java.util.Optional.of(new AuthorityRecord(term, term == 0 ? null : "cp-" + term, null));
			}

			@Override
			public boolean promote(String authorityId, int expectedTerm, int nextTerm, Instant now) {
				throw new UnsupportedOperationException("leitura de config nao promove");
			}

			@Override
			public java.util.List<String> declared() {
				return java.util.List.of();
			}

			@Override
			public void declare(String instanceId, String actorId, Instant now) {
				throw new UnsupportedOperationException("leitura de config nao declara autoridade");
			}

			@Override
			public boolean revoke(String instanceId) {
				throw new UnsupportedOperationException("leitura de config nao revoga autoridade");
			}

			@Override
			public java.util.Set<String> aliveSince(Instant threshold) {
				return java.util.Set.of();
			}
		};
		return new NetworkConfigService(Clock.systemUTC(), repository, authorities, signingKey, publicKey,
			ttlSeconds, allowedRoutes);
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
				view.limits().bandwidthMbps(), view.allowedTasks(), view.allowedRoutes(), view.issuedAt(),
				view.ttlSeconds(), view.term());
		Signature verifier = Signature.getInstance("Ed25519");
		verifier.initVerify(pin);
		verifier.update(fields.canonical().getBytes(StandardCharsets.UTF_8));
		assertThat(verifier.verify(Base64.getDecoder().decode(view.signature()))).isTrue();
	}

	/**
	 * O termo está DENTRO da linha assinada: promover muda o documento e muda a assinatura. Se ele
	 * ficasse só no JSON, quem forjasse a resposta trocaria o número e a regra do nó ("termo só sobe")
	 * viraria decoração — o nó não teria como saber qual termo a autoridade de fato assinou.
	 */
	@Test
	void termoEntraNaLinhaAssinadaENaoSoNoPayload() {
		String signatureKey = base64(pair.getPrivate().getEncoded());
		String publicKey = base64(pair.getPublic().getEncoded());
		var stamp = new NetworkNodeRepository.ConfigStamp(3, Instant.parse("2026-11-12T10:00:00Z"));

		var termo1 = service(signatureKey, publicKey, stamp, 1).forNode(node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_MEDIA)));
		var termo2 = service(signatureKey, publicKey, stamp, 2).forNode(node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_MEDIA)));

		assertThat(termo1.term()).isEqualTo(1);
		assertThat(termo2.term()).isEqualTo(2);
		// Mesmo carimbo, mesmas permissões, mesmo relógio: só o termo difere, e a assinatura difere.
		assertThat(termo2.signature()).isNotEqualTo(termo1.signature());
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

	/**
	 * O prazo de validade está DENTRO da linha assinada, no formato 3. Se ficasse só no payload, quem
	 * forjasse a resposta prolongaria indefinidamente uma config velha — e o cenário "Control Plane
	 * fora por mais que o TTL" (doc 06 §6) viraria decoração: o nó seguiria com a permissão para
	 * sempre sem nunca saber que o mando venceu.
	 */
	@Test
	void ttlEntraNaLinhaAssinadaENaoSoNoPayload() {
		String signatureKey = base64(pair.getPrivate().getEncoded());
		String publicKey = base64(pair.getPublic().getEncoded());
		var stamp = new NetworkNodeRepository.ConfigStamp(3, Instant.parse("2026-11-12T10:00:00Z"));

		var curto = service(signatureKey, publicKey, stamp, 1, 300).forNode(node(NodeStatus.ACTIVE,
			Set.of(NodeProfile.NODE_MEDIA)));
		var longo = service(signatureKey, publicKey, stamp, 1, 3600).forNode(node(NodeStatus.ACTIVE,
			Set.of(NodeProfile.NODE_MEDIA)));

		assertThat(curto.ttlSeconds()).isEqualTo(300);
		assertThat(longo.ttlSeconds()).isEqualTo(3600);
		// Mesmo carimbo, mesmas permissões, mesmo termo: só o TTL difere, e a assinatura difere.
		assertThat(longo.signature()).isNotEqualTo(curto.signature());
	}

	/** TTL configurado zero ou negativo venceria a config no instante do nascimento: piso em 300s. */
	@Test
	void ttlInvalidoRecaiNoPisoDeTrezentosSegundos() {
		var service = service("", "", new NetworkNodeRepository.ConfigStamp(1, Instant.parse("2026-11-12T10:00:00Z")),
			0, 0);

		var view = service.forNode(node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_MEDIA)));

		assertThat(view.ttlSeconds()).isEqualTo(300);
	}

	/**
	 * A rota autorizada é permissão, e permissão vai na ASSINATURA. Uma config sem rota declarada
	 * não muda o comportamento de ninguém: a lista sai vazia e a task {@code API_REQUEST} é recusada
	 * pelo runtime, porque destino que não está na lista não é destino autorizado.
	 */
	@Test
	void rotaDeclaradaVaiNoPayloadENaAssinatura() throws Exception {
		String signatureKey = base64(pair.getPrivate().getEncoded());
		String publicKey = base64(pair.getPublic().getEncoded());
		var stamp = new NetworkNodeRepository.ConfigStamp(3, Instant.parse("2026-11-12T10:00:00Z"));

		var view = service(signatureKey, publicKey, stamp, 1, 300,
				"catalogo=https://catalogo.exemplo/api,acervo=https://acervo.exemplo/api")
			.forNode(node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_MEDIA)));

		assertThat(view.allowedRoutes()).containsExactly("acervo=https://acervo.exemplo/api",
			"catalogo=https://catalogo.exemplo/api");

		// A rota está DENTRO da linha assinada: trocar o destino na resposta quebraria a assinatura.
		PublicKey pin = java.security.KeyFactory.getInstance("Ed25519")
			.generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(view.publicKey())));
		var fields = new NetworkConfigSigner.SignedFields(view.formatVersion(), view.configVersion(), view.nodeId(),
			view.profiles(), view.limits().cpuCores(), view.limits().memoryMb(), view.limits().diskMb(),
			view.limits().bandwidthMbps(), view.allowedTasks(), view.allowedRoutes(), view.issuedAt(),
			view.ttlSeconds(), view.term());
		Signature verifier = Signature.getInstance("Ed25519");
		verifier.initVerify(pin);
		verifier.update(fields.canonical().getBytes(StandardCharsets.UTF_8));
		assertThat(verifier.verify(Base64.getDecoder().decode(view.signature()))).isTrue();

		// E o destino trocado não confere: é a assinatura que impede o SSRF.
		var trocada = new NetworkConfigSigner.SignedFields(view.formatVersion(), view.configVersion(), view.nodeId(),
			view.profiles(), view.limits().cpuCores(), view.limits().memoryMb(), view.limits().diskMb(),
			view.limits().bandwidthMbps(), view.allowedTasks(),
			List.of("catalogo=http://169.254.169.254/latest/meta-data"), view.issuedAt(), view.ttlSeconds(),
			view.term());
		Signature outra = Signature.getInstance("Ed25519");
		outra.initVerify(pin);
		outra.update(trocada.canonical().getBytes(StandardCharsets.UTF_8));
		assertThat(outra.verify(Base64.getDecoder().decode(view.signature()))).isFalse();
	}

	/** Sem rota declarada, a lista é vazia — o nó não tem para onde ir e a task é recusada. */
	@Test
	void semRotaDeclaradaAListaVazia() {
		var service = service("", "", new NetworkNodeRepository.ConfigStamp(1, Instant.parse("2026-11-12T10:00:00Z")));

		var view = service.forNode(node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_MEDIA)));

		assertThat(view.allowedRoutes()).isEmpty();
	}

	/** Rota malformada é descartada: uma entrada sem "=" entraria na assinatura e o runtime não
	 * conseguiria resolver o nome — o nó falharia no meio do trabalho por causa de config errada. */
	@Test
	void rotaMalformadaEDescartada() {
		var service = service("", "", new NetworkNodeRepository.ConfigStamp(1, Instant.parse("2026-11-12T10:00:00Z")),
				0, 300, "semSeparador,=https://sem-nome.exemplo/,nome-sem-url=");

		var view = service.forNode(node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_MEDIA)));

        assertThat(view.allowedRoutes()).isEmpty();
    }

	/** A vírgula é caractere legítimo de URL (query string): ela NÃO pode cortar a entrada.
	 * Cortar ali daria ao nó um destino diferente do assinado — a chamada iria para um
	 * caminho que ninguém autorizou, que é exatamente o que a lista assinada existe para
	 * impedir. Aqui o parser só abre entrada nova quando a vírgula é seguida de {@code nome=};
	 * então a vírgula da query fica, e {@code catalogo=https://api.exemplo/x?ids=1,2} continua
	 * uma rota só. O {@code acervo=} logo depois prova que a quebra de entradas segue viva. */
	@Test
	void virgulaNaUrlNaoCortaARota() {
		var service = service("", "", new NetworkNodeRepository.ConfigStamp(1, Instant.parse("2026-11-12T10:00:00Z")),
				0, 300, "catalogo=https://api.exemplo/x?ids=1,2,acervo=https://acervo.exemplo/api");

		var view = service.forNode(node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_MEDIA)));

		assertThat(view.allowedRoutes()).containsExactly(
				"acervo=https://acervo.exemplo/api",
				"catalogo=https://api.exemplo/x?ids=1,2");
	}

	/** O nome da rota é o que o payload traz: um nome fora do conjunto que o runtime aceita
	 * entraria assinado e nunca seria resolvido — a task morreria como "rota não autorizada"
	 * no meio do trabalho, sem que ninguém entendesse por quê. */
	@Test
	void nomeDeRotaForaDoConjuntoEDescartado() {
		var service = service("", "", new NetworkNodeRepository.ConfigStamp(1, Instant.parse("2026-11-12T10:00:00Z")),
				0, 300, "nome com espaco=https://a.exemplo/api,ok=https://b.exemplo/api");

		var view = service.forNode(node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_MEDIA)));

		assertThat(view.allowedRoutes()).containsExactly("ok=https://b.exemplo/api");
	}

	/** Destino que não é http(s) não é rota de API: o executor rejeita esquema outro que
	 * http/https, e a rota entraria assinada para ser sempre recusada. */
	@Test
	void destinoNaoHttpEDescartado() {
		var service = service("", "", new NetworkNodeRepository.ConfigStamp(1, Instant.parse("2026-11-12T10:00:00Z")),
				0, 300, "arquivo=file:///etc/passwd,ok=https://b.exemplo/api");

		var view = service.forNode(node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_MEDIA)));

		assertThat(view.allowedRoutes()).containsExactly("ok=https://b.exemplo/api");
	}

	/** Aspas e barra invertida viram escape no JSON e o "]" fecha o array: um destino com
	 * esses caracteres entraria assinado e a leitura da lista no runtime a recortaria — a
	 * rota poderia virar duas, ou a config inteira ser recusada. */
	@Test
	void destinoComCaractereQueBracaJsonEDescartado() {
		var service = service("", "", new NetworkNodeRepository.ConfigStamp(1, Instant.parse("2026-11-12T10:00:00Z")),
				0, 300, "quebra=https://a.exemplo/x\"y,fecha=https://a.exemplo/x]z,ok=https://b.exemplo/api");

		var view = service.forNode(node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_MEDIA)));

		assertThat(view.allowedRoutes()).containsExactly("ok=https://b.exemplo/api");
	}

	/** Dois destinos com o mesmo nome não podem coexistir: o runtime resolve por nome, e
	 * uma das duas URLs nunca seria chamada — sem que ninguém perceba. */
	@Test
	void nomeRepetidoComOutroDestinoDescartaODuplicado() {
		var service = service("", "", new NetworkNodeRepository.ConfigStamp(1, Instant.parse("2026-11-12T10:00:00Z")),
				0, 300, "catalogo=https://a.exemplo/api,catalogo=https://b.exemplo/api");

		var view = service.forNode(node(NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_MEDIA)));

		assertThat(view.allowedRoutes()).containsExactly("catalogo=https://a.exemplo/api");
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
		public List<NetworkNode> listByOwner(String ownerUserId, int limit) {
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
		public void updateContract(String id, int cpuCores, int memoryMb, int diskMb, int bandwidthMbps,
				List<NodeProfile> profiles, Instant now) {
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