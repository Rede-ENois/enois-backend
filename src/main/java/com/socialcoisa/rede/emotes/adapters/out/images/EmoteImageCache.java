package com.socialcoisa.rede.emotes.adapters.out.images;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.socialcoisa.rede.emotes.domain.EmoteImages;
import com.socialcoisa.rede.emotes.domain.EmoteImages.Variant;
import com.socialcoisa.rede.emotes.domain.EmoteProvider;

/**
 * Cache das imagens de figurinha servidas pelo backend.
 *
 * Motivo: o `cdn.frankerfacez.com` falha em ~30% das tentativas daqui (timeout de 12s) — o seletor
 * ficava cheio de quadrado vazio. Passando pelo backend, a primeira pessoa que abre paga (com uma
 * segunda tentativa); as outras recebem da memoria, e o navegador guarda por dias porque figurinha
 * nao muda de desenho.
 *
 * A animacao chega do catalogo (`animated`), que e quem sabe qual figurinha tem versao em movimento.
 * A chave do cache inclui esse bit: quando um refresh descobre animacao, o endereco publico muda
 * (`&a=1`) e a entrada antiga nao e reaproveitada por engano.
 *
 * Guardas: o endereco de origem e sempre montado com constante + identificador validado
 * ({@link EmoteImages#upstream}); so provedor conhecido entra; so resposta `image/*` de ate 1MB e
 * guardada. Nada do que o chamador manda vira host de destino.
 */
public class EmoteImageCache {

	/** Limite por imagem: figurinha de chat e arquivo pequeno mesmo animada. */
	static final int MAX_BYTES = 1024 * 1024;

	/** Orcamento total do cache: o retrato inteiro (737) cabe com sobra, varias vezes. */
	private static final long MAX_WEIGHT = 48L * 1024 * 1024;

	private static final Pattern IDENTIFICADOR = Pattern.compile("[A-Za-z0-9]{1,40}");

	private final ImageFetcher fetcher;

	private final Cache<String, StoredImage> cache = Caffeine.newBuilder()
		.maximumWeight(MAX_WEIGHT)
		.weigher((String key, StoredImage image) -> image.bytes().length)
		.expireAfterWrite(Duration.ofDays(7))
		.build();

	public EmoteImageCache(ImageFetcher fetcher) {
		this.fetcher = fetcher;
	}

	/** A imagem, do cache ou do CDN. Vazio quando o provedor/id nao existe ou o CDN nao respondeu. */
	public Optional<StoredImage> find(String providerParam, String id, String variantParam, boolean animated) {
		EmoteProvider provider = EmoteProvider.parse(providerParam);
		if (provider == null || id == null || !IDENTIFICADOR.matcher(id).matches()) {
			return Optional.empty();
		}
		Variant variant = Variant.parse(variantParam);
		String key = provider.name() + "/" + id + "/" + variant.value() + (animated ? "/a" : "/s");

		StoredImage cached = cache.getIfPresent(key);
		if (cached != null) {
			return Optional.of(cached);
		}

		StoredImage fetched = download(provider, id, variant, animated);
		if (fetched != null) {
			cache.put(key, fetched);
		}
		return Optional.ofNullable(fetched);
	}

	/** O endereco certo primeiro; o estatico so quando o CDN recusa um arquivo que o catalogo diz existir. */
	private StoredImage download(EmoteProvider provider, String id, Variant variant, boolean animated) {
		StoredImage preferida = attempt(EmoteImages.upstream(provider, id, variant, animated));
		if (preferida != null) {
			return preferida;
		}
		String estatica = EmoteImages.fallback(provider, id, variant, animated);
		return estatica == null ? null : attempt(estatica);
	}

	/** Duas tentativas por endereco: o CDN do FFZ falha em rajada, e na segunda costuma responder. */
	private StoredImage attempt(String url) {
		for (int tentativa = 1; tentativa <= 2; tentativa++) {
			try {
				return fetcher.fetch(url);
			}
			catch (ImageNotFoundException naoExiste) {
				// Resposta definitiva: nao adianta repetir, o proximo passo e o arquivo estatico.
				return null;
			}
			catch (Exception falhaTransitoria) {
				if (tentativa == 2) {
					return null;
				}
			}
		}
		return null;
	}

	long cachedEntries() {
		return cache.estimatedSize();
	}
}