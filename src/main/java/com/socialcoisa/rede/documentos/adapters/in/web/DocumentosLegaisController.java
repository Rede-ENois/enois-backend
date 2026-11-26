package com.socialcoisa.rede.documentos.adapters.in.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.documentos.application.DocumentosLegaisService;
import com.socialcoisa.rede.documentos.domain.AceiteDeDocumento;
import com.socialcoisa.rede.documentos.domain.DocumentoTipo;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Documentos legais: Diretrizes e Termos versionados, e o aceite de cada pessoa.
 *
 * <ul>
 * <li>{@code GET /documentos} — o que está em vigor por tipo;</li>
 * <li>{@code GET /documentos/{tipo}} e {@code GET /documentos/{tipo}/{versao}} — o texto;</li>
 * <li>{@code GET /documentos/{tipo}/historico} — versões, da mais nova para a mais antiga;</li>
 * <li>{@code GET /documentos/me} — em vigor + o que a pessoa ainda precisa aceitar;</li>
 * <li>{@code POST /documentos/{id}/aceite} — aceite explícito da versão em vigor;</li>
 * <li>{@code GET /documentos/me/aceites} — histórico de aceites da pessoa.</li>
 * </ul>
 *
 * <p>A publicação (rascunho, aprovação, entrada em vigor) é de admin e mora em
 * {@link DocumentosAdminController}, sob {@code /admin}.</p>
 */
@RestController
class DocumentosLegaisController {

	private final DocumentosLegaisService documentos;
	private final CurrentUserResolver currentUserResolver;

	DocumentosLegaisController(DocumentosLegaisService documentos, CurrentUserResolver currentUserResolver) {
		this.documentos = documentos;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping("/documentos")
	ResponseEntity<List<DocumentosLegaisService.DocumentoResumo>> vigentes() {
		// O texto atual pode mudar; o leitor confere antes de mostrar o aceite.
		return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(documentos.vigentes());
	}

	@GetMapping("/documentos/me")
	Object minhSituacao(@RequestHeader(value = "X-Session-Token", required = false) String token) {
		CurrentUser user = currentUserResolver.resolve(token);
		return documentos.situacao(user.id().value());
	}

	@GetMapping("/documentos/me/aceites")
	Object meusAceites(@RequestHeader(value = "X-Session-Token", required = false) String token) {
		CurrentUser user = currentUserResolver.resolve(token);
		return documentos.aceites(user.id().value()).stream().map(this::aceite).toList();
	}

	@GetMapping("/documentos/{tipo}")
	Object documento(@PathVariable String tipo, @RequestParam(value = "versao", required = false) Integer versao) {
		return documentos.documento(tipoDe(tipo), versao);
	}

	@GetMapping("/documentos/{tipo}/historico")
	List<DocumentosLegaisService.DocumentoResumo> historico(@PathVariable String tipo) {
		return documentos.historico(tipoDe(tipo));
	}

	/**
	 * Aceite explícito. O cliente manda o id da versão que MOSTROU à pessoa; o serviço recusa se
	 * essa não for a versão em vigor (a pessoa aceitou um texto que não é o atual) e recusa
	 * rascunho. Não há campos de "aceite" no corpo além do id: o ato é o POST em si.
	 */
	@PostMapping("/documentos/{id}/aceite")
	Object aceitar(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@PathVariable String id, HttpServletRequest request) {
		CurrentUser user = currentUserResolver.resolve(token);
		AceiteDeDocumento aceite = documentos.aceitar(user.id().value(), id, clienteIp(request),
			request.getHeader("User-Agent"));
		return aceite(aceite);
	}

	private Map<String, Object> aceite(AceiteDeDocumento aceite) {
		Map<String, Object> linha = new LinkedHashMap<>();
		linha.put("id", aceite.id());
		linha.put("documentoId", aceite.documentoId());
		linha.put("tipo", aceite.documentoTipo().name());
		linha.put("versao", aceite.documentoVersao());
		linha.put("origem", aceite.origem().name());
		linha.put("aceitoEm", aceite.aceitoEm().toString());
		return linha;
	}

	private static DocumentoTipo tipoDe(String tipo) {
		return DocumentoTipo.de(tipo)
			.orElseThrow(() -> new ApiException(404, "DOCUMENTO_NAO_ENCONTRADO", "Documento não encontrado."));
	}

	/**
	 * O IP que vale para auditoria é o da conexão; atrás do proxy, o primeiro da cadeia
	 * encaminhada. Fica só o hash (nunca o IP em claro) — é o suficiente para correlacionar.
	 */
	private static String clienteIp(HttpServletRequest request) {
		String encaminhado = request.getHeader("X-Forwarded-For");
		if (encaminhado != null && !encaminhado.isBlank()) {
			return encaminhado.split(",")[0].trim();
		}
		return request.getRemoteAddr();
	}

	record AceiteRequest() {
	}
}
