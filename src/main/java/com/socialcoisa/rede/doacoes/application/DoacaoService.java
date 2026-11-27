package com.socialcoisa.rede.doacoes.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.doacoes.application.port.out.DoacaoRepository;
import com.socialcoisa.rede.doacoes.domain.BrCode;
import com.socialcoisa.rede.doacoes.domain.ComprovanteTexto;
import com.socialcoisa.rede.doacoes.domain.Doacao;
import com.socialcoisa.rede.doacoes.domain.DoacaoStatus;
import com.socialcoisa.rede.doacoes.domain.EndToEndId;
import com.socialcoisa.rede.doacoes.domain.PixStatement;
import com.socialcoisa.rede.moderation.domain.UserWarning;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Doações via Pix pessoal: cada conta recebe um copia e cola com o próprio txid, e o selo de
 * apoiador nasce da confirmação — do comprovante (do doador) ou do extrato da conta (de quem
 * recebe), nunca do txid.
 *
 * <p>Decisões e limites em {@code docs/DOACOES.md}.</p>
 */
@Service
public class DoacaoService {

	/** Janela de cada código: o extrato do recebedor sai no dia seguinte, então um dia e meio cobre. */
	static final int QR_WINDOW_HOURS = 36;

	/** Fuso do Brasil conta no extrato; o pod roda em UTC. */
	private static final ZoneId BR = ZoneId.of("America/Sao_Paulo");

	private final DoacaoRepository doacoes;
	private final UserProfileRepository profiles;
	private final BrCode brCode;
	private final Clock clock;

	public DoacaoService(DoacaoRepository doacoes, UserProfileRepository profiles, Clock clock,
			@Value("${rede.doacoes.pix.key-digits}") String chave,
			@Value("${rede.doacoes.pix.name:Apoie o Enois}") String nome,
			@Value("${rede.doacoes.pix.city:SAO PAULO}") String cidade) {
		this.doacoes = doacoes;
		this.profiles = profiles;
		this.clock = clock;
		this.brCode = new BrCode(new BrCode.Pix(chave, nome, cidade));
	}

	/** O copia e cola da pessoa, ou nulo se não houver doação aberta dela. */
	public Optional<CopiaECola> currentQr(String userId) {
		return doacoes.findOpenByUser(userId).map(this::view);
	}

	/**
	 * Cria a doação e o código. A doação aberta anterior é recusada: o código antigo continua
	 * deixando de valer, e deixar dois abertos multiplicaria os comprovantes a conferir.
	 */
	@Transactional
	public CopiaECola openFor(String userId, long amountCents) {
		Instant now = clock.instant();
		doacoes.findOpenByUser(userId).ifPresent(anterior -> doacoes.save(anterior.reject(userId)));
		Doacao doacao = Doacao.open(userId, Doacao.newTxid(), amountCents, now);
		return view(doacoes.save(doacao));
	}

	private CopiaECola view(Doacao doacao) {
		return new CopiaECola(doacao.id(), doacao.txid(), doacao.amountCents(),
			brCode.copyAndPaste(doacao.txid()), doacao.createdAt(),
			doacao.createdAt().plusSeconds(QR_WINDOW_HOURS * 3600L));
	}

	/** Doações da pessoa, da mais nova para a mais velha — é o histórico do perfil e da tela. */
	public List<Doacao> byUser(String userId) {
		return doacoes.byUser(userId);
	}

	/**
	 * O doador cola o comprovante. O texto é só LIDO: o que interessa é o valor (tem de ser o do
	 * código gerado) e os identificadores fim a fim dos pix que ele mostra. O texto não é
	 * armazenado — fica a decisão e os identificadores, para o mesmo comprovante não pagar o selo
	 * de duas contas.
	 */
	@Transactional
	public Resultado confirmWithProof(String userId, String doacaoId, String comprovante) {
		Doacao doacao = own(userId, doacaoId);
		if (doacao.status() != DoacaoStatus.ABERTA) {
			return new Resultado(doacao, "DECIDIDA");
		}
		var lido = lerComprovante(comprovante, doacao.amountCents());
		if (lido == null || lido.amountCents() != doacao.amountCents()) {
			throw new ApiException(422, "DONATION_AMOUNT_MISMATCH",
				"O comprovante não bate com o valor desta doação (R$ "
					+ String.format(Locale.ROOT, "%.2f", doacao.amountCents() / 100.0) + ").");
		}
		if (lido.ids().isEmpty()) {
			throw new ApiException(422, "DONATION_PROOF_MISSING",
				"O comprovante precisa trazer o identificador fim a fim do Pix. "
					+ "No extrato, ele aparece como \"ID\" ou \"ID fim a fim\". "
					+ "Se você digitou, confira se o identificador foi copiado inteiro.");
		}
		if (doacoes.anyPixIdUsed(lido.ids())) {
			throw new ApiException(409, "DONATION_PROOF_REUSED", "Esse comprovante já foi usado em outra doação.");
		}
		return new Resultado(doacoes.save(doacao.confirmByDonor(lido.ids(), clock.instant())), "CONFIRMADA");
	}

	private Doacao own(String userId, String doacaoId) {
		Doacao doacao = doacoes.findById(doacaoId)
			.orElseThrow(() -> new ApiException(404, "DONATION_NOT_FOUND", "Doação não encontrada."));
		if (!doacao.userId().equals(userId)) {
			// 404 em vez de 403: o id de outra pessoa não precisa existir para quem pergunta.
			throw new ApiException(404, "DONATION_NOT_FOUND", "Doação não encontrada.");
		}
		return doacao;
	}

	/**
	 * Lê o comprovante — texto do extrato do doador ou a linha digitada — e devolve valor + os
	 * identificadores de pix que ele mostra. O texto sai daqui e não é guardado.
	 */
	private ComprovanteTexto.Parsed lerComprovante(String comprovante, long valorEsperado) {
		if (comprovante == null || comprovante.isBlank()) {
			return null;
		}
		String texto = comprovante.trim();
		List<PixStatement.Entry> entradas = PixStatement.received(texto);
		// O comprovante de uma doação mostra o valor UMA vez (recebeu + o próprio pix vêm juntos, no
		// cartão, e o parser não duplica a linha). Mais de uma entrada com o valor da doação é o
		// extrato colado inteiro: dá para separar só as linhas do valor.
		List<PixStatement.Entry> daDoacao = entradas.stream()
			.filter(e -> e.amountCents() == valorEsperado).toList();
		if (daDoacao.size() > 1) {
			throw new ApiException(422, "DONATION_PROOF_MULTIPLE",
				"O comprovante tem mais de um Pix com o valor desta doação. Mande só o comprovante dela.");
		}
		if (!entradas.isEmpty() && daDoacao.isEmpty()) {
			// O extrato tem entrada, mas nenhuma com o valor gerado: é este o desencontro a acusar.
			return null;
		}
		if (daDoacao.size() == 1) {
			var e = daDoacao.get(0);
			return new ComprovanteTexto.Parsed(e.amountCents(), ids(e.detail()), e.date());
		}
		return ComprovanteTexto.parse(texto);
	}

	private static List<String> ids(String detalhe) {
		String id = EndToEndId.fromReceipt(detalhe);
		return id == null ? List.of() : List.of(id);
	}

	// ---- conferência (quem recebe) ----

	public List<Doacao> byStatus(DoacaoStatus status, int offset, int limit) {
		return doacoes.byStatus(status, offset, limit);
	}

	public long countByStatus(DoacaoStatus status) {
		return doacoes.countByStatus(status);
	}

	/**
	 * Confere o extrato Pix da conta: cada entrada reconhecida confirma a doação aberta de mesmo
	 * valor (e mesma data, quando o extrato traz). {@code dryRun} devolve o que faria sem gravar.
	 */
	@Transactional
	public Reconcile reconcile(String reviewerId, String textoDoExtrato, boolean dryRun) {
		if (textoDoExtrato == null || textoDoExtrato.isBlank()) {
			throw new ApiException(400, "DONATION_STATEMENT_REQUIRED", "Cole o extrato Pix da conta.");
		}
		var linhas = PixStatement.received(textoDoExtrato);
		if (linhas.isEmpty()) {
			throw new ApiException(422, "DONATION_STATEMENT_EMPTY",
				"Nenhuma entrada de Pix foi reconhecida no extrato. Cole o extrato como a instituição mostra.");
		}
		List<String> confirmadas = new ArrayList<>();
		List<String> naoAchadas = new ArrayList<>();
		for (PixStatement.Entry linha : linhas) {
			Optional<Doacao> match = candidata(linha);
			if (match.isEmpty()) {
				naoAchadas.add(linha.raw());
				continue;
			}
			Doacao doacao = match.get();
			if (!dryRun) {
				confirmarPeloExtrato(doacao, linha, reviewerId);
			}
			confirmadas.add(doacao.id());
		}
		return new Reconcile(confirmadas.size(), confirmadas, naoAchadas, dryRun);
	}

	private void confirmarPeloExtrato(Doacao doacao, PixStatement.Entry linha, String reviewerId) {
		doacoes.save(doacao.confirmByAccount(EndToEndId.fromReceipt(linha.detail()), clock.instant(), reviewerId));
	}

	/**
	 * A doação aberta que casa com a entrada: mesmo valor e mesma data (quando o extrato traz uma).
	 * Quando o extrato traz o identificador, ele não pode ser de outra doação — um pix já usado não
	 * confirma uma segunda.
	 */
	private Optional<Doacao> candidata(PixStatement.Entry linha) {
		String e2e = EndToEndId.fromReceipt(linha.detail());
		LocalDate data = dataDoExtrato(linha.date());
		boolean pixJaUsado = e2e != null && doacoes.anyPixIdUsed(List.of(e2e));
		return doacoes.byStatus(DoacaoStatus.ABERTA, 0, 200).stream()
			.filter(d -> d.amountCents() == linha.amountCents())
			.filter(d -> !pixJaUsado)
			.filter(d -> data == null || LocalDate.ofInstant(d.createdAt(), BR).equals(data)
				|| LocalDate.ofInstant(d.createdAt(), BR).plusDays(1).equals(data))
			.findFirst();
	}

	/**
	 * A data do extrato. As instituições escrevem "dd/MM", "dd/MM/yy" ou "dd/MM/yyyy"; só a que traz
	 * ano vira filtro ("13/11/26" é 2026). "dd/MM" sozinho não filtra, para não errar o ano na
	 * virada.
	 */
	static LocalDate dataDoExtrato(String data) {
		if (data == null || data.isBlank()) {
			return null;
		}
		String[] partes = data.split("/");
		if (partes.length != 3) {
			return null;
		}
		String ano = partes[2].length() == 2 ? "20" + partes[2] : partes[2];
		try {
			return LocalDate.parse(partes[0] + "/" + partes[1] + "/" + ano, DateTimeFormatter.ofPattern("dd/MM/yyyy"));
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	@Transactional
	public Doacao decide(Boolean aprovar, String reviewerId, String doacaoId) {
		Doacao doacao = doacoes.findById(doacaoId)
			.orElseThrow(() -> new ApiException(404, "DONATION_NOT_FOUND", "Doação não encontrada."));
		if (Boolean.TRUE.equals(aprovar)) {
			return doacoes.save(doacao.confirmByAccount(null, clock.instant(), reviewerId));
		}
		return doacoes.save(doacao.reject(reviewerId));
	}

	// ---- selo ----

	/** Quem, entre estes ids, já tem doação confirmada — carimba o selo sem uma query por autor. */
	public Set<String> apoiadores(Collection<String> userIds) {
		return doacoes.confirmedUserIds(userIds);
	}

	public boolean isApoiador(String userId) {
		return userId != null && doacoes.confirmedUserIds(Set.of(userId)).contains(userId);
	}

	/**
	 * Um administrador registra o selo à mão. Existe para o caso em que o dinheiro chegou mas o
	 * sistema não reconheceu o comprovante: sem isto a pessoa ficaria sem o selo que o gesto dela
	 * merece.
	 *
	 * <p>Regras, todas para o selo continuar significando algo:</p>
	 * <ul>
	 * <li>só há <b>um</b> selo registrado por pessoa — pedir o segundo responde {@code 409}, então a
	 * tela mostra "já é apoiador" em vez de acumular cliques;</li>
	 * <li>quem tem doação confirmada de verdade não precisa dele ({@code 409}: já é apoiador);</li>
	 * <li>a conta precisa existir (404 para perfil inexistente);</li>
	 * <li>a linha nasce com valor zero e origem {@code MANUAL} — não soma em nenhum total de
	 * dinheiro;</li>
	 * <li>o motivo é obrigatório e o nome de quem registrou fica na linha.</li>
	 * </ul>
	 */
	@Transactional
	public Doacao grantSeal(String adminId, String username, String reason) {
		String usuario = username == null ? "" : username.trim();
		String motivo = UserWarning.requireReason(reason, "selo de apoiador");
		String userId = profiles.findByUsername(usuario)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuário não encontrado."))
			.id();
		Optional<Doacao> confirmada = doacoes.byUser(userId).stream()
			.filter(d -> d.status() == DoacaoStatus.CONFIRMADA)
			.findFirst();
		if (confirmada.isPresent()) {
			if (confirmada.get().origem() == DoacaoStatus.Origem.MANUAL) {
				throw new ApiException(409, "SUPPORTER_SEAL_ALREADY_GRANTED",
					"O selo de apoiador desta conta já foi registrado à mão.");
			}
			throw new ApiException(409, "SUPPORTER_ALREADY_CONFIRMED",
				"Esta conta já é apoiadora por doação confirmada; não há o que registrar à mão.");
		}
		return doacoes.save(Doacao.manualSeal(userId, clock.instant(), adminId));
	}

	/** O estado do selo para a tela da pessoa: apoiador e quantas doações confirmadas. */
	public Map<String, Object> sealState(String userId) {
		List<Doacao> confirmadas = doacoes.byUser(userId).stream()
			.filter(d -> d.status() == DoacaoStatus.CONFIRMADA).toList();
		return Map.of(
			"apoiador", !confirmadas.isEmpty(),
			"confirmedCount", confirmadas.size(),
			"lastConfirmedAt", confirmadas.isEmpty() ? "" : confirmadas.get(0).confirmedAt().toString());
	}

	// ---- visões ----

	public record CopiaECola(String id, String txid, long amountCents, String pixCopyAndPaste, Instant createdAt,
			Instant expiresAt) {
	}

	public record Resultado(Doacao doacao, String situacao) {
	}

	public record Reconcile(int confirmed, List<String> doacaoIds, List<String> unmatchedLines, boolean dryRun) {
	}
}
