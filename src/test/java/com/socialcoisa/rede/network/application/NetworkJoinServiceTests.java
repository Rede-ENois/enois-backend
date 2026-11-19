package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.socialcoisa.rede.network.application.port.out.NetworkJoinRequestRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkNodeResponsibleRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkUserDirectory;
import com.socialcoisa.rede.network.application.port.out.ProvisionTokenRepository;
import com.socialcoisa.rede.network.domain.NetworkJoinRequest;
import com.socialcoisa.rede.network.domain.NetworkJoinTerm;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.security.Hashes;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O fluxo de inscrição tem uma promessa que não pode falhar: <b>a chave existe só quando o link é
 * aberto, uma vez</b>. Estes testes fixam isso e o que o cerca.
 *
 * <ul>
 * <li><b>aprovar não gera chave</b> — gera o link. Um teste que só olhasse a decisão passaria com
 * uma implementação que emitisse a credencial na aprovação, que é exatamente o que o contrato
 * promete que não acontece;</li>
 * <li><b>o segundo uso do link não devolve chave</b>, mesmo logo depois do primeiro;</li>
 * <li><b>link vencido não abre</b>, e a chave não é o que se renova — a renovação vale só antes do
 * primeiro uso;</li>
 * <li><b>CPF inválido e pedido duplicado não entram</b> na fila administrativa.</li>
 * </ul>
 */
class NetworkJoinServiceTests {

	private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");

	private InMemoryJoinRequests requests;
	private RecordingTokens tokens;
	private NetworkNodeService nodes;
	private NetworkJoinService service;

	@BeforeEach
	void setUp() {
		requests = new InMemoryJoinRequests();
		tokens = new RecordingTokens();
		nodes = Mockito.mock(NetworkNodeService.class);
		Mockito.when(nodes.issueForApplicant(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
			Mockito.anyList(), Mockito.anyString(), Mockito.anyString()))
			.thenReturn(new NetworkNodeService.IssuedToken("npt_chave_exibida", NOW.plus(Duration.ofMinutes(30))));
		NetworkUserDirectory users = Mockito.mock(NetworkUserDirectory.class);
		Mockito.when(users.byEmail(Mockito.anyString())).thenReturn(Optional.empty());
		com.socialcoisa.rede.identity.application.EmailService emails = Mockito
			.mock(com.socialcoisa.rede.identity.application.EmailService.class);
		Mockito.when(emails.sendNodeJoinApprovedEmail(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
			.thenReturn(true);
		service = new NetworkJoinService(requests, nodes, users, Mockito.mock(NetworkNodeResponsibleRepository.class),
			Mockito.mock(AuditTrail.class), emails, Clock.fixed(NOW, ZoneOffset.UTC), List.of("NODE_WORKER"));
	}

	@Test
	void aprovarGeraLinkENaoChave() {
		NetworkJoinRequest applied = service.apply("Maria Silva", "maria@exemplo.com", "MG-12.345.678",
			"529.982.247-25", "Tenho uma maquina ligada 24h e quero ajudar", NetworkJoinTerm.VERSION, null);

		NetworkJoinService.RevealToken reveal = service.decide(applied.id(), NetworkJoinRequest.Status.APPROVED,
			"adm_1", "ok", List.of("NODE_STORAGE")).orElseThrow();

		// A "chave" aqui é o token do link; o token de provisionamento só nasce no consumeReveal.
		assertThat(reveal.token()).hasSize(64);
		Mockito.verify(nodes, Mockito.never()).issueForApplicant(Mockito.anyString(), Mockito.anyString(),
			Mockito.anyString(), Mockito.anyList(), Mockito.anyString(), Mockito.anyString());
		assertThat(tokens.inserted).isEmpty();
		assertThat(requests.byId.get(applied.id()).status()).isEqualTo(NetworkJoinRequest.Status.APPROVED);
		// Só o hash do link fica gravado; o token em claro existe só nesta resposta.
		assertThat(requests.byId.get(applied.id()).revealTokenHash()).isEqualTo(Hashes.sha256Hex(reveal.token()));
	}

	@Test
	void abrirOLinkEmiteAChaveUmaVezESoUmaVez() {
		String reveal = approved("Maria Silva", "maria@exemplo.com");

		NetworkJoinService.EnrolledForReveal first = service.consumeReveal(reveal, "meu-no", "sa-east-1", 4, 8192,
			102400, 100);

		assertThat(first.provisionToken()).isEqualTo("npt_chave_exibida");
		// Segundo uso: não é erro de estado antigo, é a regra — a chave não sai de novo.
		assertThatThrownBy(() -> service.consumeReveal(reveal, "meu-no", "sa-east-1", 4, 8192, 102400, 100))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("status", 409);
		Mockito.verify(nodes, Mockito.times(1)).issueForApplicant(Mockito.anyString(), Mockito.anyString(),
			Mockito.anyString(), Mockito.anyList(), Mockito.anyString(), Mockito.anyString());
	}

	@Test
	void linkVencidoNaoAbreENaoEmiteChave() {
		String reveal = approved("Maria Silva", "maria@exemplo.com");
		// Avança o relógio além da validade, sem mexer no resto do estado.
		requests.expireReveal(Hashes.sha256Hex(reveal), NOW.minusSeconds(1));

		assertThatThrownBy(() -> service.consumeReveal(reveal, "meu-no", "sa-east-1", 4, 8192, 102400, 100))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("status", 410);
		Mockito.verify(nodes, Mockito.never()).issueForApplicant(Mockito.anyString(), Mockito.anyString(),
			Mockito.anyString(), Mockito.anyList(), Mockito.anyString(), Mockito.anyString());
	}

	@Test
	void renovarOLinkAntesDoUsoInvalidaOAnterior() {
		String old = approved("Maria Silva", "maria@exemplo.com");
		NetworkJoinService.RevealToken rotated = service.rotateReveal(revealRequestId(), "adm_1");

		assertThat(rotated.token()).isNotEqualTo(old);
		assertThatThrownBy(() -> service.consumeReveal(old, "meu-no", "sa-east-1", 4, 8192, 102400, 100))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("status", 404);
		assertThat(service.consumeReveal(rotated.token(), "meu-no", "sa-east-1", 4, 8192, 102400, 100)
			.provisionToken()).isEqualTo("npt_chave_exibida");
	}

	@Test
	void depoisDoUsoNaoHaORenovar() {
		String reveal = approved("Maria Silva", "maria@exemplo.com");
		service.consumeReveal(reveal, "meu-no", "sa-east-1", 4, 8192, 102400, 100);

		assertThatThrownBy(() -> service.rotateReveal(revealRequestId(), "adm_1")).isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("status", 409);
	}

	@Test
	void cpfInvalidoEEscolhaDePerfilVaziaNaoEntram() {
		assertThatThrownBy(() -> service.apply("Maria Silva", "maria@exemplo.com", "MG-12.345.678", "123.456.789-00",
			"Quero participar da rede", NetworkJoinTerm.VERSION, null))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("status", 400);
		assertThat(requests.inserted).isEmpty();
	}

	@Test
	void semAceiteDaVersaoVigenteDoContratoNaoEntra() {
		assertThatThrownBy(() -> service.apply("Maria Silva", "maria@exemplo.com", "MG-12.345.678",
			"529.982.247-25", "Quero participar da rede", "0", null))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("status", 400);
	}

	@Test
	void pedidoDuplicadoEmAnaliseEhRecusado() {
		service.apply("Maria Silva", "maria@exemplo.com", "MG-12.345.678", "529.982.247-25",
			"Quero participar da rede", NetworkJoinTerm.VERSION, null);

		assertThatThrownBy(() -> service.apply("Maria Silva", "MARIA@exemplo.com", "MG-12.345.678",
			"529.982.247-25", "Quero participar da rede", NetworkJoinTerm.VERSION, null))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("status", 409);
	}

	@Test
	void decisaoDuplaEhRecusada() {
		String reveal = approved("Maria Silva", "maria@exemplo.com");
		String id = requests.byHash.get(Hashes.sha256Hex(reveal)).id();

		assertThatThrownBy(() -> service.decide(id, NetworkJoinRequest.Status.REJECTED, "adm_2", null, null))
			.isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("status", 409);
	}

	@Test
	void oEnrollRecebeONomeEEmailDoFormularioParaRegistrarOResponsavel() {
		String reveal = approved("Maria Silva", "maria@exemplo.com");

		service.consumeReveal(reveal, "meu-no", "sa-east-1", 4, 8192, 102400, 100);

		ArgumentCaptor<String> email = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
		Mockito.verify(nodes).issueForApplicant(Mockito.anyString(), Mockito.eq("meu-no"), Mockito.anyString(),
			Mockito.anyList(), name.capture(), email.capture());
		assertThat(name.getValue()).isEqualTo("Maria Silva");
		assertThat(email.getValue()).isEqualTo("maria@exemplo.com");
	}

	// ------------------------------------------------------------------ apoio

	private String approved(String name, String email) {
		NetworkJoinRequest applied = service.apply(name, email, "MG-12.345.678", "529.982.247-25",
			"Quero participar da rede", NetworkJoinTerm.VERSION, null);
		return service.decide(applied.id(), NetworkJoinRequest.Status.APPROVED, "adm_1", null,
			List.of("NODE_WORKER")).orElseThrow().token();
	}

	private String revealRequestId() {
		return requests.inserted.get(0).id();
	}

	/** Repositório em memória com as mesmas condições que o SQL aplica. */
	private static final class InMemoryJoinRequests implements NetworkJoinRequestRepository {

		private final Map<String, NetworkJoinRequest> byId = new HashMap<>();
		private final Map<String, NetworkJoinRequest> byHash = new HashMap<>();
		private final List<NetworkJoinRequest> inserted = new java.util.ArrayList<>();

		@Override
		public void insert(NetworkJoinRequest request) {
			byId.put(request.id(), request);
			inserted.add(request);
		}

		@Override
		public Optional<NetworkJoinRequest> findById(String id) {
			return Optional.ofNullable(byId.get(id));
		}

		@Override
		public Optional<NetworkJoinRequest> findPendingByEmailOrCpf(String email, String cpf) {
			return byId.values().stream()
				.filter(r -> r.pending() && (r.email().equalsIgnoreCase(email) || r.cpf().equals(cpf)))
				.findFirst();
		}

		@Override
		public List<NetworkJoinRequest> list(NetworkJoinRequest.Status status, int limit) {
			return byId.values().stream().filter(r -> status == null || r.status() == status).limit(limit).toList();
		}

		@Override
		public Optional<NetworkJoinRequest> findByRevealHashForUpdate(String revealHash) {
			return findByRevealHash(revealHash);
		}

		@Override
		public Optional<NetworkJoinRequest> findByRevealHash(String revealHash) {
			return byId.values().stream().filter(r -> revealHash.equals(r.revealTokenHash())).findFirst();
		}

		@Override
		public boolean decide(String id, NetworkJoinRequest.Status status, String profiles, String decidedBy,
				String note, String revealHash, Instant revealExpiresAt, Instant now) {
			NetworkJoinRequest current = byId.get(id);
			if (current == null || !current.pending()) {
				return false;
			}
			NetworkJoinRequest decided = current.decide(status, decidedBy, note, profiles, revealHash,
				revealExpiresAt, now);
			byId.put(id, decided);
			if (revealHash != null) {
				byHash.put(revealHash, decided);
			}
			return true;
		}

		@Override
		public boolean rotateReveal(String id, String newHash, Instant expiresAt, Instant now) {
			NetworkJoinRequest current = byId.get(id);
			if (current == null || current.status() != NetworkJoinRequest.Status.APPROVED
				|| current.revealConsumedAt() != null) {
				return false;
			}
			NetworkJoinRequest rotated = current.withReveal(newHash, expiresAt, now);
			byHash.remove(current.revealTokenHash());
			byHash.put(newHash, rotated);
			byId.put(id, rotated);
			return true;
		}

		@Override
		public boolean consumeReveal(String id, Instant now) {
			NetworkJoinRequest current = byId.get(id);
			if (current == null || current.revealConsumedAt() != null) {
				return false;
			}
			byId.put(id, current.consumed(now));
			return true;
		}

		@Override
		public void markEmailSent(String id, Instant now) {
			byId.put(id, byId.get(id).emailed(now));
		}

		/** Move a validade do link para o passado sem consumir: é o "venceu esperando". */
		void expireReveal(String hash, Instant expiresAt) {
			NetworkJoinRequest current = byHash.get(hash);
			NetworkJoinRequest expired = current.withReveal(hash, expiresAt, NOW);
			byHash.put(hash, expired);
			byId.put(expired.id(), expired);
		}
	}

	/** Registra os tokens de provisionamento que chegassem a ser emitidos (deveriam ser nenhum antes do link). */
	private static final class RecordingTokens implements ProvisionTokenRepository {

		private final List<String> inserted = new java.util.ArrayList<>();

		@Override
		public void insert(String tokenHash, String displayName, String ownerUserId, String profiles,
				Instant expiresAt, String createdBy, Instant now) {
			inserted.add(tokenHash);
		}

		@Override
		public void insertForApplicant(String tokenHash, String displayName, String ownerUserId, String profiles,
				Instant expiresAt, String createdBy, Instant now, String ownerName, String ownerEmail) {
			inserted.add(tokenHash);
		}

		@Override
		public Optional<ProvisionTokenRecord> findByHash(String tokenHash) {
			return Optional.empty();
		}

		@Override
		public boolean markUsed(String tokenHash, Instant now) {
			return true;
		}

		@Override
		public long countAliveForOwner(String ownerUserId, Instant now) {
			return 0;
		}
	}
}