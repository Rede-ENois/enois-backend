package com.socialcoisa.rede.leitura.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.leitura.application.port.out.LeituraCache;
import com.socialcoisa.rede.leitura.application.port.out.LeitorDeTexto;
import com.socialcoisa.rede.leitura.domain.LeituraTexto;

/**
 * A leitura de intenção com cache. Um texto é lido UMA vez: a chave é o hash do conteúdo, então
 * reler o mesmo texto não paga de novo. Quando o modelo está indisponível ou falha, a leitura
 * degrada (vazia, marcada como não lida) e o chamador usa a extração determinística.
 *
 * <p>Este serviço é o único ponto de entrada da leitura — o Papo Reto e o Wrapped não falam com a
 * inferência directamente.</p>
 */
@Service
public class LeituraDeTexto {

	public static final int VERSAO = 1;

	private final LeitorDeTexto leitor;
	private final LeituraCache cache;

	public LeituraDeTexto(LeitorDeTexto leitor, LeituraCache cache) {
		this.leitor = leitor;
		this.cache = cache;
	}

	/** Hash do conteúdo (sha-256 hex), a chave do cache. */
	public static String hash(String texto) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest((texto == null ? "" : texto).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException impossivel) {
			throw new IllegalStateException("SHA-256 indisponível", impossivel);
		}
	}

	/** A leitura de um texto do cache, se já lida e vinda do modelo. */
	public Optional<LeituraTexto> doCache(String texto) {
		return Optional.ofNullable(cache.porHashes(List.of(hash(texto))).get(hash(texto)))
				.filter(LeituraTexto::lidoPeloModelo);
	}

	/**
	 * Lê um texto: do cache quando já lido pelo modelo; senão tenta a inferência e grava. Devolve
	 * a leitura (possivelmente degradada) e informa se pagou a inferência agora.
	 */
	public Resultado ler(String texto) {
		String hash = hash(texto);
		LeituraTexto emCache = cache.porHashes(List.of(hash)).get(hash);
		if (emCache != null && emCache.lidoPeloModelo()) {
			return new Resultado(emCache, false);
		}
		LeituraTexto lida = leitor.disponivel()
				? leitor.ler(texto).orElse(LeituraTexto.vazia())
				: LeituraTexto.vazia();
		cache.salvar(hash, lida, VERSAO);
		return new Resultado(lida, lida.lidoPeloModelo());
	}

	/** Lê em lote, pagando a inferência só pelo que ainda não foi lido pelo modelo. */
	public Map<String, LeituraTexto> lerEmLote(List<String> textos) {
		Map<String, LeituraTexto> porHash = new LinkedHashMap<>();
		List<String> distintos = textos.stream().filter(t -> t != null && !t.isBlank()).distinct().toList();
		Map<String, LeituraTexto> cacheado = cache.porHashes(distintos.stream().map(LeituraDeTexto::hash).toList());
		for (String texto : distintos) {
			String hash = hash(texto);
			LeituraTexto jaLida = cacheado.get(hash);
			if (jaLida != null && jaLida.lidoPeloModelo()) {
				porHash.putIfAbsent(texto, jaLida);
			}
		}
		for (String texto : distintos) {
			if (porHash.containsKey(texto)) {
				continue;
			}
			porHash.putIfAbsent(texto, ler(texto).leitura());
		}
		return porHash;
	}

	public boolean disponivel() {
		return leitor.disponivel();
	}

	/** A leitura e se ela custou inferência agora. */
	public record Resultado(LeituraTexto leitura, boolean inferido) {
	}
}
