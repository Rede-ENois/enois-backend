package com.socialcoisa.rede.figurinhas.adapters.in.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.figurinhas.application.FigurinhasComunidadeService;
import com.socialcoisa.rede.figurinhas.domain.FigurinhaComunidade;
import com.socialcoisa.rede.figurinhas.domain.FigurinhaComunidadeEvento;
import com.socialcoisa.rede.figurinhas.domain.FigurinhaImagem;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.ModeratorGuard;
import com.socialcoisa.rede.shared.web.StatusResponse;

/**
 * Figurinhas da Comunidade.
 *
 * <p>Sistema SEPARADO do pacote oficial "Figurinhas do Énóis" (que é do front e não passa por
 * aqui). Rotas:</p>
 * <ul>
 * <li>{@code GET /figurinhas} — catálogo paginado, com busca por nome, código ou id;</li>
 * <li>{@code GET /figurinhas/index?codigos=a,b} — mapa código → imagem das ATIVAS (o texto usa);</li>
 * <li>{@code POST /figurinhas/envios} → {@code POST /figurinhas/envios/{mediaId}} →
 *   {@code POST /figurinhas} — cadastro em três passos: declarar, enviar a imagem, publicar;</li>
 * <li>{@code GET /figurinhas/envios/pode} — se a conta pode enviar (identidade verificada e
 *   reputação mínima), com os números para a tela mostrar o que falta;</li>
 * <li>{@code DELETE /figurinhas/{id}} — o autor tira a própria figurinha;</li>
 * <li>{@code POST /moderation/figurinhas/{id}/desativar} — moderação/admin tiram do ar;</li>
 * <li>{@code GET /figurinhas/{id}/historico} — histórico, para moderação e para o autor.</li>
 * </ul>
 */
@RestController
class FigurinhaComunidadeController {

	private static final int MAX_LIMIT = 60;
	private static final int DEFAULT_LIMIT = 40;

	private final FigurinhasComunidadeService figurinhas;
	private final CurrentUserResolver currentUserResolver;
	private final ModeratorGuard moderatorGuard;

	FigurinhaComunidadeController(FigurinhasComunidadeService figurinhas, CurrentUserResolver currentUserResolver,
			ModeratorGuard moderatorGuard) {
		this.figurinhas = figurinhas;
		this.currentUserResolver = currentUserResolver;
		this.moderatorGuard = moderatorGuard;
	}

	/**
	 * Catálogo. O catálogo é para quem entrou: quem não está logado recebe 401 em vez de uma lista.
	 */
	@GetMapping("/figurinhas")
	Object pagina(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestParam(value = "page", defaultValue = "1") int page,
			@RequestParam(value = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit,
			@RequestParam(value = "q", required = false) String query) {
		exigirSessao(token);
		int tamanho = Math.max(1, Math.min(limit, MAX_LIMIT));
		FigurinhasComunidadeService.Página resultado = figurinhas.pagina(page, tamanho, query);
		List<Map<String, Object>> itens = new ArrayList<>();
		for (FigurinhaComunidade figurinha : resultado.itens()) {
			itens.add(view(figurinha));
		}
		Map<String, Object> autores = new LinkedHashMap<>();
		autoresDoCatalogo(itens, autores);
		return new PageView(itens, autores, resultado.page(), resultado.limit(), resultado.total(),
			resultado.totalPages());
	}

	private void autoresDoCatalogo(List<Map<String, Object>> itens, Map<String, Object> autores) {
		LinkedHashSet<String> ids = new LinkedHashSet<>();
		for (Map<String, Object> item : itens) {
			ids.add((String) item.get("autorId"));
		}
		figurinhas.autores(ids).forEach((id, autor) -> {
			Map<String, Object> linha = new LinkedHashMap<>();
			linha.put("username", autor.username());
			linha.put("nome", autor.nome() == null ? autor.username() : autor.nome());
			linha.put("avatarUrl", autor.avatarUrl() == null ? "" : autor.avatarUrl());
			autores.put(id, linha);
		});
	}

	/** O mapa que o texto consulta: só as ativas, no formato `codigo -> endereço da imagem`. */
	@GetMapping("/figurinhas/index")
	ResponseEntity<Map<String, String>> indice(
			@RequestParam(value = "codigos", required = false) String codigos) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noCache())
			.body(figurinhas.indice(codigos == null ? List.of() : List.of(codigos.split(","))));
	}

	/**
	 * Se a conta pode enviar figurinha (identidade verificada + reputação mínima), com os números:
	 * a tela usa isto para mostrar o que falta antes do primeiro passo.
	 */
	@GetMapping("/figurinhas/envios/pode")
	Object podeEnviar(@RequestHeader(value = "X-Session-Token", required = false) String token) {
		CurrentUser user = exigirSessao(token);
		var decisao = figurinhas.decisaoDeEnvio(user.id().value());
		Map<String, Object> resposta = new LinkedHashMap<>();
		resposta.put("pode", decisao.pode());
		resposta.put("reputacao", decisao.reputacao());
		resposta.put("reputacaoMinima", decisao.reputacaoMinima());
		resposta.put("identidadeVerificada", decisao.identidadeVerificada());
		resposta.put("motivo", decisao.motivo());
		return resposta;
	}

	/** Passo 1: declarar o envio. Responde onde escrever a imagem. */
	@PostMapping("/figurinhas/envios")
	Object criarEnvio(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestBody(required = false) EnvioRequest request) {
		CurrentUser user = exigirSessao(token);
		if (request == null) {
			throw new ApiException(400, "FIGURINHA_ENVIO_INVALIDO", "Informe o tipo e o tamanho da imagem.");
		}
		FigurinhasComunidadeService.Envio envio = figurinhas.criarUpload(user.id().value(), request.mimeType(),
			request.size() == null ? 0 : request.size());
		return new EnvioView(envio.mediaId(), envio.uploadUrl());
	}

	/** Passo 2: os bytes da imagem, conferidos antes de qualquer gravação. */
	@PostMapping("/figurinhas/envios/{mediaId}")
	Object enviarImagem(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@PathVariable String mediaId, HttpServletRequest request) {
		CurrentUser user = exigirSessao(token);
		byte[] bytes = lerCorpo(request);
		FigurinhasComunidadeService.EnvioConcluido enviado = figurinhas.enviarImagem(user.id().value(), mediaId,
			bytes);
		return new ImagemView(enviado.mediaId(), enviado.imageUrl(), enviado.largura(), enviado.altura());
	}

	/**
	 * Lê o corpo cru até o teto. Tamanho declarado (Content-Length) e tamanho lido têm de bater:
	 * um corpo truncado ou maior que o declarado é recusado em vez de conferido pela metade.
	 */
	private byte[] lerCorpo(HttpServletRequest request) {
		long tamanho = request.getContentLengthLong();
		if (tamanho <= 0) {
			throw new ApiException(411, "FIGURINHA_TAMANHO_INVALIDO", "Informe o tamanho da imagem.");
		}
		if (tamanho > FigurinhaImagem.MAX_BYTES) {
			throw new ApiException(413, "FIGURINHA_ARQUIVO_GRANDE",
				"A imagem deve ter no máximo " + (FigurinhaImagem.MAX_BYTES / 1024L) + "KB.");
		}
		byte[] bytes;
		try {
			bytes = request.getInputStream().readNBytes((int) FigurinhaImagem.MAX_BYTES + 1);
		}
		catch (IOException | IllegalStateException falha) {
			throw new ApiException(413, "FIGURINHA_ARQUIVO_GRANDE",
				"A imagem deve ter no máximo " + (FigurinhaImagem.MAX_BYTES / 1024L) + "KB.");
		}
		if (bytes.length != tamanho) {
			throw new ApiException(400, "FIGURINHA_TAMANHO_DIVERGENTE",
				"O tamanho recebido não corresponde ao envio.");
		}
		return bytes;
	}

	/** Passo 3: publicar a figurinha. O nome é obrigatório; o código é escolhido por quem cadastra. */
	@PostMapping("/figurinhas")
	Object publicar(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestBody(required = false) PublicarRequest request) {
		CurrentUser user = exigirSessao(token);
		if (request == null) {
			throw new ApiException(400, "FIGURINHA_DADOS_OBRIGATORIOS", "Informe a imagem, o código e o nome.");
		}
		FigurinhaComunidade figurinha = figurinhas.publicar(user.id().value(), request.mediaId(), request.codigo(),
			request.nome());
		Map<String, Object> view = view(figurinha);
		Map<String, Object> autores = new LinkedHashMap<>();
		autoresDoCatalogo(List.of(view), autores);
		Map<String, Object> resposta = new LinkedHashMap<>();
		resposta.put("figurinha", view);
		resposta.put("autores", autores);
		return resposta;
	}

	/** O autor tira a própria figurinha. */
	@DeleteMapping("/figurinhas/{id}")
	Object remover(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@PathVariable String id) {
		CurrentUser user = exigirSessao(token);
		FigurinhaComunidade figurinha = figurinhas.removerPropria(user.id().value(), id);
		return new StatusResponse(figurinha.id(), figurinha.ativa() ? "ATIVA" : "DESATIVADA");
	}

	/**
	 * Moderação: desativar. O motivo é obrigatório e fica no histórico. A figurinha some do índice,
	 * da busca e do seletor; posts antigos mostram o código como texto.
	 */
	@PostMapping("/moderation/figurinhas/{id}/desativar")
	Object desativar(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@PathVariable String id, @RequestBody(required = false) MotivoRequest request) {
		CurrentUser user = currentUserResolver.resolve(token);
		if (!moderatorGuard.isModerator(user)) {
			throw new ApiException(403, "MODERATION_FORBIDDEN", "Apenas a moderação pode desativar figurinhas.");
		}
		String motivo = request == null ? null : request.motivo();
		if (motivo == null || motivo.trim().length() < 10) {
			throw new ApiException(400, "FIGURINHA_MOTIVO_OBRIGATORIO",
				"Explique o motivo da desativação (pelo menos 10 caracteres).");
		}
		FigurinhaComunidade figurinha = figurinhas.desativar(user.id().value(), id, motivo.trim());
		return new StatusResponse(figurinha.id(), figurinha.ativa() ? "ATIVA" : "DESATIVADA");
	}

	/**
	 * Histórico da figurinha (cadastro, remoção pelo autor, moderação): para a auditoria e para o
	 * autor entender. Fora de {@code /moderation}, porque o autor também lê.
	 */
	@GetMapping("/figurinhas/{id}/historico")
	Object historico(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@PathVariable String id) {
		CurrentUser user = currentUserResolver.resolve(token);
		boolean moderador = moderatorGuard.isModerator(user);
		FigurinhaComunidade figurinha = figurinhas.porId(id)
			.orElseThrow(() -> new ApiException(404, "FIGURINHA_NAO_ENCONTRADA", "Figurinha não encontrada."));
		if (!moderador && !figurinha.pertenceA(user.id().value())) {
			throw new ApiException(403, "FIGURINHA_NAO_E_SUA", "Esta figurinha não é sua.");
		}
		List<Map<String, Object>> eventos = figurinhas.historico(id).stream().map(this::evento).toList();
		Map<String, Object> resposta = new LinkedHashMap<>();
		resposta.put("figurinha", view(figurinha));
		resposta.put("eventos", eventos);
		return resposta;
	}

	private Map<String, Object> evento(FigurinhaComunidadeEvento evento) {
		Map<String, Object> linha = new LinkedHashMap<>();
		linha.put("tipo", evento.tipo());
		linha.put("atorId", evento.atorId());
		linha.put("detalhe", evento.detalhe());
		linha.put("criadoEm", evento.criadoEm().toString());
		return linha;
	}

	private CurrentUser exigirSessao(String token) {
		return currentUserResolver.resolve(token);
	}

	/** O que o cliente vê de uma figurinha: origem explícita, código com dois-pontos e imagem. */
	private Map<String, Object> view(FigurinhaComunidade figurinha) {
		Map<String, Object> linha = new LinkedHashMap<>();
		linha.put("id", figurinha.id());
		linha.put("codigo", ":" + figurinha.codigo() + ":");
		linha.put("origem", "COMUNIDADE");
		linha.put("nome", figurinha.nome());
		linha.put("autorId", figurinha.autorId());
		linha.put("imagem", figurinha.imageUrl());
		linha.put("largura", figurinha.largura());
		linha.put("altura", figurinha.altura());
		linha.put("ativo", figurinha.ativa());
		linha.put("criadoEm", figurinha.criadoEm().toString());
		linha.put("desativadoEm", figurinha.desativadoEm() == null ? null : figurinha.desativadoEm().toString());
		return linha;
	}

	record EnvioRequest(String mimeType, Long size) {
	}

	record PublicarRequest(String mediaId, String codigo, String nome) {
	}

	record MotivoRequest(String motivo) {
	}

	record EnvioView(String mediaId, String uploadUrl) {
	}

	record ImagemView(String mediaId, String imagem, int largura, int altura) {
	}

	record PageView(List<Map<String, Object>> items, Map<String, Object> autores, int page, int limit, long total,
			int totalPages) {
	}
}
