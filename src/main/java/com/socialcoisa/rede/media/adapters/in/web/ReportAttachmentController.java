package com.socialcoisa.rede.media.adapters.in.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.socialcoisa.rede.media.application.MediaService;
import com.socialcoisa.rede.media.application.ReportAttachments;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.ModeratorGuard;

/**
 * Leitura da <b>cópia de uma denúncia</b>.
 *
 * <p>A cópia fica no bucket em chave própria, fora do prefixo público (ver {@link ReportAttachments}).
 * Quem abre é uma de três pessoas: quem enviou (baixando o próprio arquivo), um moderador (a prova
 * da análise) ou quem apresenta o endereço assinado que o painel recebeu — com prazo curto. O
 * cliente nunca vê endereço do bucket, e o id do asset publicado não leva a ela.</p>
 *
 * <p>O conteúdo nunca renderiza: sai como download opaco ({@code application/octet-stream},
 * {@code attachment}), porque é exatamente o arquivo enviado e ainda não passou por revisão de
 * imagem. O original de um vídeo transcrito, que é a prova da denúncia, sai pelo mesmo caminho.</p>
 */
@RestController
class ReportAttachmentController {

	private static final String NOT_FOUND = "Midia nao encontrada.";

	private final ReportAttachments attachments;
	private final MediaAssetRepository assets;
	/** Nulo sem bucket (storage do ImageKit): não há cópia para servir e a rota responde 503. */
	private final MediaObjectStore store;
	private final CurrentUserResolver users;
	private final ModeratorGuard moderators;

	ReportAttachmentController(ReportAttachments attachments, MediaAssetRepository assets,
			ObjectProvider<MediaObjectStore> storeProvider, CurrentUserResolver users, ModeratorGuard moderators) {
		this.attachments = attachments;
		this.assets = assets;
		this.store = storeProvider.getIfAvailable();
		this.users = users;
		this.moderators = moderators;
	}

	@GetMapping("/media/attachments/{mediaId}")
	ResponseEntity<StreamingResponseBody> read(@PathVariable String mediaId,
			@RequestParam(value = "scope", required = false) String scope,
			@RequestParam(value = "expires", required = false) String expires,
			@RequestParam(value = "signature", required = false) String signature,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		var asset = assets.findById(mediaId)
			.filter(found -> MediaService.REPORT_CAPTURE_PURPOSE.equals(found.purpose()))
			.orElseThrow(() -> new ApiException(404, "MEDIA_NOT_FOUND", NOT_FOUND));
		authorize(mediaId, asset.ownerId().value(), scope, expires, signature, sessionToken);
		if (store == null) {
			throw new ApiException(503, "MEDIA_STORAGE_DISABLED", "Armazenamento de midia indisponivel.");
		}
		MediaObjectStore.StoredObject object;
		try {
			object = store.read(attachments.keyFor(mediaId, asset.objectKey()), null);
		}
		catch (RuntimeException missing) {
			throw new ApiException(404, "MEDIA_NOT_FOUND", NOT_FOUND);
		}
		return ResponseEntity.status(HttpStatus.OK)
			.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
			.header(HttpHeaders.CONTENT_DISPOSITION, "attachment")
			.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
			.contentLength(object.length())
			.body(output -> {
				try (var body = object.body()) {
					body.transferTo(output);
				}
			});
	}

	/**
	 * Quem pode abrir o arquivo daquela denúncia. A sessão só é lida quando não há assinatura — a
	 * maior parte das leituras vem do painel, com o endereço curto.
	 */
	private void authorize(String mediaId, String ownerId, String scope, String expires, String signature,
			String sessionToken) {
		// O escopo assinado é só o carimbo de quem emitiu o endereço (painel de moderação ou o link
		// anexado à denúncia de quem enviou); o conteúdo é o mesmo arquivo nos dois casos.
		if (attachments.verify(mediaId, scope, expires, signature).isPresent()) {
			return;
		}
		if (sessionToken == null || sessionToken.isBlank()) {
			// Sem assinatura e sem sessão: a resposta é a mesma de mídia inexistente, para que a rota
			// não confirme a existência da prova.
			throw new ApiException(404, "MEDIA_NOT_FOUND", NOT_FOUND);
		}
		CurrentUser user = users.resolve(sessionToken);
		if (user.id().value().equals(ownerId)) {
			return;
		}
		moderators.requireModerator(user);
	}
}