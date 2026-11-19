package com.socialcoisa.rede.network.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkJoinRequestRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkNodeResponsibleRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkUserDirectory;
import com.socialcoisa.rede.network.application.port.out.ProvisionTokenRepository;
import com.socialcoisa.rede.network.domain.Cpf;
import com.socialcoisa.rede.network.domain.NetworkJoinRequest;
import com.socialcoisa.rede.network.domain.NetworkJoinTerm;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.security.Hashes;

/**
 * Inscrição para servir um nó: o formulário público (RG, CPF, motivo, contrato) e a decisão da
 * administração.
 *
 * <p>O fluxo tem três portas e uma regra que atravessa todas: <b>a chave de provisionamento não
 * existe antes de alguém abrir o link</b>. Aprovar gera um token de <i>exibição</i> (o link do
 * e-mail), não a chave; abrir o link gera o token de provisionamento, mostra uma vez e consome o
 * link. Assim:</p>
 *
 * <ul>
 * <li>renovar o link (perdeu o e-mail antes de abrir) é seguro — não há credencial antiga para
 * vazar;</li>
 * <li>duas abertas do mesmo link não dobram chave — o consumo é condicional e travado;</li>
 * <li>depois de exibida, a chave não tem reemissão. Perdeu, é novo pedido (contrato, cláusula 8).</li>
 * </ul>
 *
 * <p>Quando o e-mail do formulário corresponde a uma conta do site, o enroll reconhece a conta e o
 * nó nasce com dono — o painel mostra a pessoa pela conta, com o retrato da inscrição ao lado.</p>
 */
@Service
public class NetworkJoinService {

	/** Teto de inscrições pendentes na fila: a listagem do painel é paginada por isso. */
	private static final int MAX_PENDING = 500;

	private static final int MAX_LIST = 200;

	/** Validade do link de exibição enviado por e-mail. */
	static final Duration REVEAL_TTL = Duration.ofDays(7);

	/** Nome do nó e identificação do inscrito: limites do formulário. */
	private static final int MAX_NAME = 120;

	private static final int MAX_MOTIVATION = 2000;

	private final NetworkJoinRequestRepository requests;
	private final NetworkNodeService nodes;
	private final NetworkUserDirectory users;
	private final NetworkNodeResponsibleRepository responsibles;
	private final AuditTrail audit;
	private final com.socialcoisa.rede.identity.application.EmailService emails;
	private final Clock clock;
	private final SecureRandom random = new SecureRandom();

	public NetworkJoinService(NetworkJoinRequestRepository requests, NetworkNodeService nodes,
			NetworkUserDirectory users, NetworkNodeResponsibleRepository responsibles, AuditTrail audit,
			com.socialcoisa.rede.identity.application.EmailService emails, Clock clock,
			@Value("${rede.network.join.profiles:NODE_WORKER}") List<String> defaultProfiles) {
		this.requests = requests;
		this.nodes = nodes;
		this.users = users;
		this.responsibles = responsibles;
		this.audit = audit;
		this.emails = emails;
		this.clock = clock;
		this.defaultProfiles = normalizeProfiles(defaultProfiles);
	}

	private final List<NodeProfile> defaultProfiles;

	/** A inscrição pública. É o formulário inteiro em uma chamada, e é anônima: sem sessão. */
	@Transactional
	public NetworkJoinRequest apply(String fullName, String email, String rg, String cpfRaw, String motivation,
			String acceptedTermVersion, List<String> requestedProfiles) {
		String name = requireText(fullName, "NODE_APPLY_NAME", "Informe seu nome completo.", MAX_NAME);
		String mail = requireEmail(email);
		String cpf = Cpf.parse(cpfRaw)
			.orElseThrow(() -> new ApiException(400, "NODE_APPLY_CPF_INVALID", "CPF invalido."))
			.digits();
		if (rg == null || rg.isBlank() || rg.strip().length() < 5 || rg.strip().length() > 20) {
			throw new ApiException(400, "NODE_APPLY_RG_INVALID", "Informe o RG (5 a 20 caracteres).");
		}
		String reason = requireText(motivation, "NODE_APPLY_MOTIVATION",
			"Conte por que quer participar da rede.", MAX_MOTIVATION);
		if (!NetworkJoinTerm.VERSION.equals(acceptedTermVersion)) {
			throw new ApiException(400, "NODE_APPLY_TERM_VERSION",
				"Versao do contrato de responsabilidade invalida ou ausente (" + NetworkJoinTerm.VERSION + ").");
		}
		String profiles = normalizeProfiles(requestedProfiles).stream()
			.map(Enum::name)
			.collect(java.util.stream.Collectors.joining(","));

		requests.findPendingByEmailOrCpf(mail, cpf).ifPresent(existing -> {
			throw new ApiException(409, "NODE_APPLY_DUPLICATE",
				"Voce ja tem uma inscricao em analise. Aguarde a decisao da administracao.");
		});

		Instant now = clock.instant();
		NetworkJoinRequest created = new NetworkJoinRequest(NodeIds.next("njr"),
			name.strip(), mail, rg.strip(), cpf, reason.strip(), NetworkJoinTerm.VERSION, now,
			NetworkJoinRequest.Status.PENDING, profiles, null, null, null, null, null, null, null, now, now);
		requests.insert(created);
		audit.record("public", "NODE_JOIN_APPLICATION", created.id(),
			"{\"term\":\"" + NetworkJoinTerm.VERSION + "\",\"profiles\":\"" + profiles + "\"}");
		return created;
	}

	/**
	 * A decisão da administração. Aprovar <b>não</b> gera chave: gera o token do link de e-mail,
	 * com os perfis que o admin escolheu para esta máquina.
	 */
	@Transactional
	public Optional<RevealToken> decide(String requestId, NetworkJoinRequest.Status decision, String actorId,
			String note, List<String> chosenProfiles) {
		NetworkJoinRequest request = requests.findById(requestId)
			.orElseThrow(() -> new ApiException(404, "NODE_JOIN_NOT_FOUND", "Inscricao nao encontrada."));
		if (!request.pending()) {
			throw new ApiException(409, "NODE_JOIN_ALREADY_DECIDED",
				"Esta inscricao ja foi decidida por outro administrador.");
		}
		if (decision != NetworkJoinRequest.Status.APPROVED && decision != NetworkJoinRequest.Status.REJECTED) {
			throw new ApiException(400, "NODE_JOIN_DECISION_INVALID", "Decisao invalida.");
		}

		String profiles = decision == NetworkJoinRequest.Status.APPROVED
			? normalizeProfiles(chosenProfiles).stream().map(Enum::name)
				.collect(java.util.stream.Collectors.joining(","))
			: "";
		String revealHash = null;
		Instant revealExpiresAt = null;
		String plainReveal = null;
		if (decision == NetworkJoinRequest.Status.APPROVED) {
			plainReveal = newRevealToken();
			revealHash = Hashes.sha256Hex(plainReveal);
			revealExpiresAt = clock.instant().plus(REVEAL_TTL);
		}
		// Condicional por estado: dois admins abrindo a fila ao mesmo tempo — só o primeiro decide.
		if (!requests.decide(requestId, decision, profiles, actorId, note, revealHash, revealExpiresAt,
			clock.instant())) {
			throw new ApiException(409, "NODE_JOIN_ALREADY_DECIDED",
				"Esta inscricao ja foi decidida por outro administrador.");
		}
		audit.record(actorId, decision == NetworkJoinRequest.Status.APPROVED ? "NODE_JOIN_APPROVED"
			: "NODE_JOIN_REJECTED", requestId, "{\"profiles\":\"" + profiles + "\"}");
		if (plainReveal != null) {
			deliverReveal(requestId, plainReveal, request.email(), request.fullName());
		}
		return decision == NetworkJoinRequest.Status.APPROVED
			? Optional.of(new RevealToken(requestId, plainReveal, request.email(), request.fullName(), revealExpiresAt))
			: Optional.empty();
	}

	/**
	 * Novo link para inscrição aprovada que ainda não exibiu a chave (perdeu o e-mail). Depois do
	 * primeiro uso, não há o que renovar: a chave existe e o caminho é o novo pedido.
	 */
	@Transactional
	public RevealToken rotateReveal(String requestId, String actorId) {
		NetworkJoinRequest request = requests.findById(requestId)
			.orElseThrow(() -> new ApiException(404, "NODE_JOIN_NOT_FOUND", "Inscricao nao encontrada."));
		if (request.status() != NetworkJoinRequest.Status.APPROVED || request.revealConsumedAt() != null) {
			throw new ApiException(409, "NODE_JOIN_REVEAL_NOT_ROTATABLE",
				"O link so pode ser renovado antes do primeiro uso.");
		}
		String plain = newRevealToken();
		if (!requests.rotateReveal(requestId, Hashes.sha256Hex(plain), clock.instant().plus(REVEAL_TTL),
			clock.instant())) {
			throw new ApiException(409, "NODE_JOIN_REVEAL_USED", "O link acabou de ser usado em outra aba.");
		}
		audit.record(actorId, "NODE_JOIN_REVEAL_ROTATED", requestId, "{}");
		deliverReveal(requestId, plain, request.email(), request.fullName());
		return new RevealToken(requestId, plain, request.email(), request.fullName(),
			clock.instant().plus(REVEAL_TTL));
	}

	/**
	 * O link aberto: consome o uso único e emite a chave de provisionamento — <b>agora</b>, e não na
	 * aprovação. O runtime do nó consome essa chave pelo fluxo de sempre (enroll → aprovação →
	 * chave de API); nada do protocolo muda para quem já roda nó.
	 */
	@Transactional
	public EnrolledForReveal consumeReveal(String rawRevealToken, String nodeName, String declaredLocation,
			int cpuCores, int memoryMb, int diskMb, int bandwidthMbps) {
		if (rawRevealToken == null || rawRevealToken.isBlank()) {
			throw new ApiException(400, "NODE_REVEAL_INVALID", "Link invalido ou expirado.");
		}
		String hash = Hashes.sha256Hex(rawRevealToken.trim());
		// SELECT ... FOR UPDATE: duas abas com o mesmo link — uma exibe a chave, a outra vê "usado".
		NetworkJoinRequest request = requests.findByRevealHashForUpdate(hash)
			.orElseThrow(() -> new ApiException(404, "NODE_REVEAL_INVALID", "Link invalido ou expirado."));
		Instant now = clock.instant();
		if (request.revealConsumedAt() != null) {
			throw new ApiException(409, "NODE_REVEAL_USED",
				"Este link ja foi usado. A chave nao pode ser exibida de novo.");
		}
		if (!request.revealUsable(now)) {
			throw new ApiException(410, "NODE_REVEAL_EXPIRED",
				"O link expirou. Peca um novo link a administracao antes do primeiro uso.");
		}
		// Mesma corrida por condicional: o FOR UPDATE já serializa, e o update é a prova no banco.
		if (!requests.consumeReveal(request.id(), now)) {
			throw new ApiException(409, "NODE_REVEAL_USED",
				"Este link ja foi usado. A chave nao pode ser exibida de novo.");
		}

		// A pessoa tem conta? O enroll reconhece pelo e-mail — o nó nasce com dono e o painel mostra
		// a pessoa, não um id solto. Sem conta, o dono é o sistema e o retrato fica na tabela de
		// responsáveis.
		NetworkUserDirectory.Account account = users.byEmail(request.email()).orElse(null);
		String ownerUserId = account != null ? account.id() : "system-join";
		String displayName = nodeName == null || nodeName.isBlank() ? "no-de-" + request.fullName().split(" ")[0]
			: nodeName.strip();
		if (displayName.length() > MAX_NAME) {
			displayName = displayName.substring(0, MAX_NAME);
		}

		// A geração do token de provisionamento fica no serviço de nós, que já tem o TTL e a regra
		// do nome: repetir a lógica aqui seria um segundo caminho para a mesma credencial.
		String plain = nodes.issueForApplicant(request.id(), displayName, ownerUserId,
			parseProfiles(request.profiles()), request.fullName(), request.email()).token();
		audit.record(request.id(), "NODE_REVEAL_CONSUMED", displayName,
			"{\"owner\":\"" + ownerUserId + "\"}");
		return new EnrolledForReveal(plain, request.fullName(), request.email(),
			account != null ? account.name() : request.fullName(), ownerUserId, displayName, request.profiles());
	}

	@Transactional(readOnly = true)
	public List<NetworkJoinRequest> list(NetworkJoinRequest.Status status, int limit) {
		return requests.list(status, Math.min(Math.max(limit, 1), MAX_LIST));
	}

	@Transactional(readOnly = true)
	public NetworkJoinRequest byId(String requestId) {
		return requests.findById(requestId)
			.orElseThrow(() -> new ApiException(404, "NODE_JOIN_NOT_FOUND", "Inscricao nao encontrada."));
	}

	/** O texto vigente do contrato e a versão, para o formulário exibir. */
	public static String termText() {
		return NetworkJoinTerm.TEXT;
	}

	public static String termVersion() {
		return NetworkJoinTerm.VERSION;
	}

	private String newRevealToken() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	private static String requireText(String value, String code, String message, int max) {
		if (value == null || value.isBlank()) {
			throw new ApiException(400, code, message);
		}
		String trimmed = value.strip();
		if (trimmed.length() > max) {
			throw new ApiException(400, code, message + " (ate " + max + " caracteres).");
		}
		return trimmed;
	}

	private static String requireEmail(String email) {
		if (email == null) {
			throw new ApiException(400, "NODE_APPLY_EMAIL_INVALID", "Informe um e-mail valido.");
		}
		String trimmed = email.strip();
		if (trimmed.length() < 6 || trimmed.length() > 200 || !trimmed.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
			throw new ApiException(400, "NODE_APPLY_EMAIL_INVALID", "Informe um e-mail valido.");
		}
		return trimmed.toLowerCase(Locale.ROOT);
	}

	/** Os perfis gravados na inscrição, como lista — vazia vira a lista padrão do serviço de nós. */
	private static List<NodeProfile> parseProfiles(String csv) {
		if (csv == null || csv.isBlank()) {
			return List.of();
		}
		List<NodeProfile> parsed = new java.util.ArrayList<>();
		for (String raw : csv.split(",")) {
			try {
				parsed.add(NodeProfile.valueOf(raw.strip().toUpperCase(Locale.ROOT)));
			}
			catch (IllegalArgumentException unknown) {
				// Perfil gravado por versão anterior que não existe mais: o novo pedido escolhe.
			}
		}
		return parsed;
	}

	private List<NodeProfile> normalizeProfiles(List<String> requested) {
		if (requested == null || requested.isEmpty()) {
			return defaultProfiles;
		}
		List<NodeProfile> parsed = requested.stream()
			.map(raw -> {
				try {
					return NodeProfile.valueOf(raw.strip().toUpperCase(Locale.ROOT));
				}
				catch (IllegalArgumentException unknown) {
					return null;
				}
			})
			.filter(java.util.Objects::nonNull)
			.distinct()
			.toList();
		return parsed.isEmpty() ? defaultProfiles : parsed;
	}

	/**
	 * Manda o link por e-mail e carimba {@code email_sent_at} só quando o envio deu certo. Falha de
	 * envio não desfaz a aprovação — o pedido foi decidido e a decisão é auditável —, mas fica
	 * registrada: sem o carimbo, o painel mostra "e-mail não saiu" e a administração renova o link
	 * em vez de a pessoa esperar por uma mensagem que não chega.
	 */
	private void deliverReveal(String requestId, String plainReveal, String email, String fullName) {
		boolean sent = emails.sendNodeJoinApprovedEmail(email, fullName, plainReveal);
		if (sent) {
			requests.markEmailSent(requestId, clock.instant());
			audit.record("system", "NODE_JOIN_LINK_SENT", requestId, "{}");
		}
		else {
			audit.record("system", "NODE_JOIN_LINK_FAILED", requestId, "{}");
		}
	}

	/** O token do link de e-mail — só existe em memória, do decide até o envio. */
	public record RevealToken(String requestId, String token, String email, String fullName, Instant expiresAt) {
	}

	/** O que a página do link mostra uma única vez: a chave de provisionamento crua. */
	public record EnrolledForReveal(String provisionToken, String fullName, String email, String accountName,
			String ownerUserId, String nodeName, String profiles) {
	}
}