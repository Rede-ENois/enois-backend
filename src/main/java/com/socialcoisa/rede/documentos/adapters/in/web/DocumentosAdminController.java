package com.socialcoisa.rede.documentos.adapters.in.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.documentos.application.DocumentosLegaisService;
import com.socialcoisa.rede.documentos.domain.DocumentoLegal;
import com.socialcoisa.rede.documentos.domain.DocumentoTipo;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Publicação de documentos legais: criar rascunho, aprovar e pôr em vigor. Só admin — a trava
 * central de {@code /admin/**} já recusa o resto antes de chegar aqui.
 *
 * <p>Nada aqui roda sozinho: nenhum texto entra em vigor sem um POST explícito de admin.</p>
 */
@RestController
class DocumentosAdminController {

	private final DocumentosLegaisService documentos;
	private final CurrentUserResolver currentUserResolver;
	private final AuditTrail audit;

	DocumentosAdminController(DocumentosLegaisService documentos, CurrentUserResolver currentUserResolver,
			AuditTrail audit) {
		this.documentos = documentos;
		this.currentUserResolver = currentUserResolver;
		this.audit = audit;
	}

	@GetMapping("/admin/documentos")
	Object painel() {
		return documentos.painel();
	}

	@PostMapping("/admin/documentos")
	Object criarRascunho(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestBody(required = false) RascunhoRequest request) {
		CurrentUser user = currentUserResolver.resolve(token);
		if (request == null) {
			throw new ApiException(400, "DOCUMENTO_DADOS_OBRIGATORIOS", "Informe tipo, título e texto.");
		}
		DocumentoTipo tipo = DocumentoTipo.de(request.tipo())
			.orElseThrow(() -> new ApiException(400, "DOCUMENTO_TIPO_INVALIDO", "Tipo de documento inválido."));
		return view(documentos.criarRascunho(user.id().value(), tipo, request.titulo(), request.resumo(),
			request.conteudo()));
	}

	@PostMapping("/admin/documentos/{id}/publicar")
	Object publicar(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@PathVariable String id) {
		CurrentUser user = currentUserResolver.resolve(token);
		return view(documentos.publicar(user.id().value(), id));
	}

	private Map<String, Object> view(DocumentoLegal documento) {
		Map<String, Object> linha = new LinkedHashMap<>();
		linha.put("id", documento.id());
		linha.put("tipo", documento.tipo().name());
		linha.put("versao", documento.versao());
		linha.put("titulo", documento.titulo());
		linha.put("aprovado", documento.aprovado());
		linha.put("vigente", documento.vigente());
		linha.put("conteudoHash", documento.conteudoHash());
		return linha;
	}

	record RascunhoRequest(String tipo, String titulo, String resumo, String conteudo) {
	}
}
