package com.socialcoisa.rede.documentos.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.documentos.application.port.out.DocumentosLegaisRepository;
import com.socialcoisa.rede.documentos.domain.AceiteDeDocumento;
import com.socialcoisa.rede.documentos.domain.DocumentoLegal;
import com.socialcoisa.rede.documentos.domain.DocumentoTipo;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.text.PlainText;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Documentos legais versionados (Diretrizes e Termos) e o aceite de cada pessoa.
 *
 * <p>Regras que o serviço garante:</p>
 * <ul>
 * <li>o texto de uma versão nunca muda — publicar texto novo é sempre uma versão nova;</li>
 * <li>rascunho não fica em vigor sozinho: precisa de aprovação explícita, e só então de entrada em
 *   vigor. Enquanto isso, o texto novo é lido (para revisão), mas não é "o texto atual";</li>
 * <li>entrar em vigor fecha a vigência da versão anterior do mesmo tipo — as duas nunca valem ao
 *   mesmo tempo;</li>
 * <li>aceite é só registro de um ato real: o backend grava o que a pessoa fez, nunca inventa;</li>
 * <li>o aceite aponta para o id da versão e para o hash do texto, então o histórico prova qual
 *   texto exato foi aceito.</li>
 * </ul>
 *
 * <p>O que a pessoa tem de aceitar agora é sempre a versão EM VIGOR de cada tipo com
 * {@code exige_aceite}. Documento sem versão em vigor não bloqueia ninguém.</p>
 */
@Service
public class DocumentosLegaisService {

	private static final List<DocumentoTipo> TIPOS = List.of(DocumentoTipo.values());

	private final DocumentosLegaisRepository documentos;
	private final AuditTrail audit;
	private final Clock clock;

	public DocumentosLegaisService(DocumentosLegaisRepository documentos, AuditTrail audit, Clock clock) {
		this.documentos = documentos;
		this.audit = audit;
		this.clock = clock;
	}

	// ---------------------------------------------------------------- leitura pública

	/** O que está em vigor agora, por tipo (sem texto completo). */
	public List<DocumentoResumo> vigentes() {
		return documentos.vigentes(TIPOS).stream().map(DocumentoResumo::de).toList();
	}

	/** Documento em vigor + o que a pessoa ainda precisa aceitar. */
	public Situacao situacao(String userId) {
		// Aceite da versão em vigor por tipo: o id do documento tem de ser o da versão atual e o
		// hash do texto tem de bater — o que foi aceito tem de ser exatamente o que vale agora.
		Map<DocumentoTipo, Set<String>> aceitos = new EnumMap<>(DocumentoTipo.class);
		for (AceiteDeDocumento aceite : documentos.aceitesDoUsuario(userId)) {
			aceitos.computeIfAbsent(aceite.documentoTipo(), t -> new HashSet<>())
				.add(aceite.documentoId() + "|" + aceite.conteudoHash());
		}
		List<DocumentoVigente> atuais = new ArrayList<>();
		List<DocumentoResumo> pendentes = new ArrayList<>();
		for (DocumentoLegal documento : documentos.vigentes(TIPOS)) {
			boolean aceito = aceitos.getOrDefault(documento.tipo(), Set.of())
				.contains(documento.id() + "|" + documento.conteudoHash());
			atuais.add(new DocumentoVigente(DocumentoResumo.de(documento), documento.conteudo(), aceito));
			if (documento.exigeAceite() && !aceito) {
				pendentes.add(DocumentoResumo.de(documento));
			}
		}
		return new Situacao(atuais, pendentes);
	}

	/** Texto completo de uma versão específica (para a página do documento e o histórico). */
	public DocumentoVigente documento(DocumentoTipo tipo, Integer versao) {
		DocumentoLegal documento = versao == null ? documentos.vigente(tipo).orElse(null)
			: documentos.versao(tipo, versao).orElse(null);
		if (documento == null) {
			throw new ApiException(404, "DOCUMENTO_NAO_ENCONTRADO", "Documento não encontrado.");
		}
		// Versão aprovada e ainda sem vigência é "em revisão": pode ser lida, mas não é a atual.
		return new DocumentoVigente(DocumentoResumo.de(documento), documento.conteudo(), false);
	}

	public List<DocumentoResumo> historico(DocumentoTipo tipo) {
		return documentos.historico(tipo).stream().map(DocumentoResumo::de).toList();
	}

	public List<AceiteDeDocumento> aceites(String userId) {
		return documentos.aceitesDoUsuario(userId);
	}

	// ---------------------------------------------------------------- aceite

	/**
	 * Registra o aceite explícito da versão em vigor. Recusa aceitar texto que não está em vigor
	 * (não existe aceite por antecipação de rascunho) e é idempotente para a mesma versão.
	 *
	 * @param documentoId id da versão mostrada à pessoa; se não bater com a versão em vigor, o
	 *   aceite é recusado — a pessoa tem de aceitar o texto que está valendo, não outro.
	 */
	@Transactional
	public AceiteDeDocumento aceitar(String userId, String documentoId, String ip, String userAgent) {
		DocumentoLegal vigente = documentos.porId(documentoId)
			.orElseThrow(() -> new ApiException(404, "DOCUMENTO_NAO_ENCONTRADO", "Documento não encontrado."));
		if (!vigente.vigente()) {
			throw new ApiException(409, "DOCUMENTO_NAO_VIGENTE",
				"Este texto ainda não está em vigor; o aceite vale quando ele entrar em vigor.");
		}
		Optional<AceiteDeDocumento> existente = documentos.aceite(userId, documentoId);
		if (existente.isPresent() && vigente.conteudoHash().equals(existente.get().conteudoHash())) {
			return existente.get();
		}
		Instant agora = Instant.now(clock);
		AceiteDeDocumento aceite = documentos.saveAceite(new AceiteDeDocumento(novoId("dlaceite"), documentoId,
			userId, vigente.tipo(), vigente.versao(), vigente.conteudoHash(), AceiteDeDocumento.Origem.AceiteExplicito,
			agora, hashIp(ip), truncar(userAgent, 255)));
		audit.record(userId, "DOCUMENTO_ACEITO", documentoId,
			"{\"tipo\":\"" + vigente.tipo() + "\",\"versao\":" + vigente.versao() + "}");
		return aceite;
	}

	/**
	 * O aceite feito no cadastro: grava o texto que estava em vigor no instante em que a conta
	 * nasceu. É o mesmo ato que a caixa do cadastro representa; sem versão em vigor, não grava
	 * nada (e não inventa aceite).
	 */
	@Transactional
	public void registrarAceiteDoCadastro(String userId, DocumentoTipo tipo) {
		Optional<DocumentoLegal> vigente = documentos.vigente(tipo);
		if (vigente.isEmpty()) {
			return;
		}
		DocumentoLegal documento = vigente.get();
		if (documentos.aceite(userId, documento.id()).isPresent()) {
			return;
		}
		documentos.saveAceite(new AceiteDeDocumento(novoId("dlaceite"), documento.id(), userId, tipo,
			documento.versao(), documento.conteudoHash(), AceiteDeDocumento.Origem.Cadastro, Instant.now(clock), null,
			null));
	}

	// ---------------------------------------------------------------- publicação (admin)

	/** Cria um rascunho: versão nova, ainda não aprovada e não em vigor. */
	@Transactional
	public DocumentoLegal criarRascunho(String adminId, DocumentoTipo tipo, String titulo, String resumo,
			String conteudo) {
		String texto = conteudo == null ? "" : conteudo;
		if (texto.strip().length() < DocumentoLegal.CONTEUDO_MINIMO) {
			throw new ApiException(400, "DOCUMENTO_CONTEUDO_CURTO",
				"O texto precisa ter pelo menos " + DocumentoLegal.CONTEUDO_MINIMO + " caracteres.");
		}
		if (texto.length() > DocumentoLegal.CONTEUDO_MAXIMO) {
			throw new ApiException(400, "DOCUMENTO_CONTEUDO_LONGO", "Texto grande demais para um documento.");
		}
		String tituloFinal = PlainText.defuse(titulo == null ? "" : titulo.trim());
		if (tituloFinal.isEmpty() || tituloFinal.length() > DocumentoLegal.TITULO_MAXIMO) {
			throw new ApiException(400, "DOCUMENTO_TITULO_INVALIDO",
				"Informe um título de até " + DocumentoLegal.TITULO_MAXIMO + " caracteres.");
		}
		String resumoFinal = resumo == null ? null : PlainText.defuse(resumo.trim());
		if (resumoFinal != null && resumoFinal.length() > DocumentoLegal.RESUMO_MAXIMO) {
			throw new ApiException(400, "DOCUMENTO_RESUMO_LONGO",
				"O resumo pode ter no máximo " + DocumentoLegal.RESUMO_MAXIMO + " caracteres.");
		}
		Instant agora = Instant.now(clock);
		DocumentoLegal rascunho = new DocumentoLegal(novoId("doc"), tipo, documentos.proximaVersao(tipo), tituloFinal,
			resumoFinal, texto, DocumentoLegal.hash(texto), true, false, null, agora, adminId, null, null);
		DocumentoLegal salvo = documentos.save(rascunho);
		audit.record(adminId, "DOCUMENTO_RASCUNHO_CRIADO", salvo.id(),
			"{\"tipo\":\"" + tipo + "\",\"versao\":" + salvo.versao() + "}");
		return salvo;
	}

	/**
	 * Aprova o rascunho e o coloca em vigor no mesmo instante: a versão anterior do tipo deixa de
	 * valer (fica no histórico, aprovada, sem vigência). É a única operação que troca o texto
	 * atual — e é explícita, de admin, auditada.
	 */
	@Transactional
	public DocumentoLegal publicar(String adminId, String documentoId) {
		DocumentoLegal documento = documentos.porId(documentoId)
			.orElseThrow(() -> new ApiException(404, "DOCUMENTO_NAO_ENCONTRADO", "Documento não encontrado."));
		if (documento.aprovado()) {
			throw new ApiException(409, "DOCUMENTO_JA_APROVADO", "Esta versão já foi aprovada.");
		}
		Instant agora = Instant.now(clock);
		documentos.encerrarVigencia(documento.tipo(), agora);
		DocumentoLegal publicado = new DocumentoLegal(documento.id(), documento.tipo(), documento.versao(),
			documento.titulo(), documento.resumo(), documento.conteudo(), documento.conteudoHash(),
			documento.exigeAceite(), true, agora, documento.criadoEm(), documento.criadoPor(), agora, adminId);
		DocumentoLegal salvo = documentos.save(publicado);
		audit.record(adminId, "DOCUMENTO_PUBLICADO", salvo.id(),
			"{\"tipo\":\"" + salvo.tipo() + "\",\"versao\":" + salvo.versao() + "}");
		return salvo;
	}

	/** O que o painel mostra: versões pendentes de aprovação e o histórico, por tipo. */
	public Map<String, Object> painel() {
		Map<String, Object> tipos = new LinkedHashMap<>();
		for (DocumentoTipo tipo : TIPOS) {
			List<DocumentoResumo> versoes = resumos(documentos.historico(tipo));
			tipos.put(tipo.name(), versoes);
		}
		return tipos;
	}

	// ---------------------------------------------------------------- auxiliares

	private static List<DocumentoResumo> resumos(List<DocumentoLegal> lista) {
		return lista.stream().map(DocumentoResumo::de).toList();
	}

	private static String hashIp(String ip) {
		if (!StringUtils.hasText(ip)) {
			return null;
		}
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(ip.trim().getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException impossivel) {
			return null;
		}
	}

	private static String truncar(String valor, int tamanho) {
		if (valor == null) {
			return null;
		}
		return valor.length() <= tamanho ? valor : valor.substring(0, tamanho);
	}

	private static String novoId(String prefixo) {
		return prefixo + "_" + UUID.randomUUID().toString().replace("-", "");
	}

	/** Resumo de uma versão (sem o texto), para listas. */
	public record DocumentoResumo(String id, String tipo, int versao, String titulo, String resumo,
			boolean exigeAceite, boolean aprovado, boolean vigente, String vigenteDesde) {

		static DocumentoResumo de(DocumentoLegal documento) {
			return new DocumentoResumo(documento.id(), documento.tipo().name(), documento.versao(), documento.titulo(),
				documento.resumo(), documento.exigeAceite(), documento.aprovado(), documento.vigente(),
				documento.vigenteDesde() == null ? null : documento.vigenteDesde().toString());
		}
	}

	/** Versão com texto, e se a pessoa já aceitou ESSA versão. */
	public record DocumentoVigente(DocumentoResumo documento, String conteudo, boolean aceito) {
	}

	/** O que está em vigor e o que a pessoa ainda precisa aceitar. */
	public record Situacao(List<DocumentoVigente> documentos, List<DocumentoResumo> pendentes) {
	}
}
