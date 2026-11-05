package com.socialcoisa.rede.emotes.adapters.in.web;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.emotes.adapters.out.images.EmoteImageCache;
import com.socialcoisa.rede.emotes.adapters.out.images.StoredImage;
import com.socialcoisa.rede.emotes.application.EmoteCatalogService;
import com.socialcoisa.rede.emotes.application.EmotePage;
import com.socialcoisa.rede.emotes.domain.Emote;
import com.socialcoisa.rede.emotes.domain.EmoteCatalog;
import com.socialcoisa.rede.emotes.domain.EmoteImages;
import com.socialcoisa.rede.emotes.domain.EmoteImages.Variant;

/**
 * Catalogo de figurinhas do site, servido pelo backend.
 *
 * E leitura PUBLICA (sem sessao) de proposito: post publico desenha figurinha para quem nem entrou,
 * e o seletor precisa abrir antes do login terminar. O que o navegador deixa de fazer aqui e falar
 * com `api.betterttv.net` e `api.frankerfacez.com` a cada visita — quem fala com eles e este
 * backend, de 3 em 3 horas, com o resultado em memoria.
 *
 * Tres rotas, cada uma com um consumidor:
 * - `GET /emotes?page&limit&q&provider` — o seletor carrega uma pagina por vez (40 quadrados);
 * - `GET /emotes/index` — o indice compacto (codigo -> endereco da imagem) que o texto usa;
 * - `GET /emotes/image/{provider}/{id}` — a imagem em si, por cache do backend (o CDN do FFZ falha
 *   em ~30% das tentativas diretas do navegador).
 */
@RestController
@RequestMapping("/emotes")
class EmoteController {

	private static final int MAX_LIMIT = 100;

	private static final int DEFAULT_LIMIT = 40;

	private final EmoteCatalogService catalogService;

	private final EmoteImageCache imageCache;

	EmoteController(EmoteCatalogService catalogService, EmoteImageCache imageCache) {
		this.catalogService = catalogService;
		this.imageCache = imageCache;
	}

	@GetMapping
	ResponseEntity<EmotePageView> list(@RequestParam(value = "page", defaultValue = "1") int page,
			@RequestParam(value = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit,
			@RequestParam(value = "q", required = false) String query,
			@RequestParam(value = "provider", defaultValue = "ALL") String provider) {
		EmoteCatalog atual = catalogService.catalog();
		EmotePage resultado = catalogService.page(page, Math.clamp(limit, 1, MAX_LIMIT), query, provider);
		EmotePageView body = new EmotePageView(views(resultado.items()), resultado.page(), resultado.limit(),
				resultado.total(), resultado.totalPages(), atual.generatedAt().toString());
		return ResponseEntity.ok()
			.eTag(atual.etag())
			.cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
			.body(body);
	}

	/**
	 * Indice do texto: codigo -> endereco da imagem. Mapa, e nao a lista inteira, porque este e o
	 * arquivo que TODA pagina carrega para trocar `KEKW` pelo desenho — com 1.100 figurinhas, a
	 * lista completa passa de 140KB; o mapa fica na casa dos 50KB e comprime bem.
	 */
	@GetMapping("/index")
	ResponseEntity<EmoteIndexView> index() {
		EmoteCatalog atual = catalogService.catalog();
		Map<String, String> entries = new LinkedHashMap<>();
		for (Emote emote : catalogService.index()) {
			entries.put(emote.code(), EmoteImages.publicPath(emote, Variant.FULL));
		}
		return ResponseEntity.ok()
			.eTag(atual.etag())
			.cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
			.body(new EmoteIndexView(entries, atual.generatedAt().toString()));
	}

	@GetMapping("/image/{provider}/{id}")
	ResponseEntity<byte[]> image(@PathVariable("provider") String provider, @PathVariable("id") String id,
			@RequestParam(value = "variant", defaultValue = "full") String variant) {
		// Figurinha barrada nem chega ao CDN: o pedido morre aqui, antes do cache e da busca.
		if (catalogService.isBlocked(provider, id)) {
			return ResponseEntity.notFound().build();
		}
		// Quem sabe se a figurinha tem versao em movimento e o catalogo, nao a URL: pedir o arquivo
		// animado de uma figurinha estatica no FFZ nao da 404, fica pendurado ate o timeout.
		boolean animated = catalogService.isAnimated(provider, id);
		Optional<StoredImage> imagem = imageCache.find(provider, id, variant, animated);
		if (imagem.isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		StoredImage stored = imagem.get();
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(stored.contentType()))
			// Figurinha nao muda de desenho: o navegador guarda por uma semana e nao volta a pedir.
			.cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic().immutable())
			.header("X-Content-Type-Options", "nosniff")
			.body(stored.bytes());
	}

	private static List<EmoteView> views(List<Emote> emotes) {
		List<EmoteView> views = new ArrayList<>(emotes.size());
		for (Emote emote : emotes) {
			views.add(new EmoteView(emote.code(), emote.provider().name(), emote.id(), emote.animated(),
					emote.popularRank(), EmoteImages.publicPath(emote, Variant.FULL),
					EmoteImages.publicPath(emote, Variant.THUMB)));
		}
		return views;
	}

	/**
	 * Uma figurinha como o front precisa dela: o CODIGO que entra no texto e os dois caminhos de
	 * imagem (a pagina guarda a miniatura, o texto desenha a arte nitida — animada quando existe).
	 */
	record EmoteView(String code, String provider, String id, boolean animated, Integer popularRank, String image,
			String thumb) {
	}

	record EmotePageView(List<EmoteView> items, int page, int limit, int total, int totalPages,
			String generatedAt) {
	}

	record EmoteIndexView(Map<String, String> entries, String generatedAt) {
	}
}