package com.socialcoisa.rede.media.adapters.out.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;

import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;

/**
 * Objeto em disco no modo local de dev.
 *
 * <p>Mesmo contrato do {@code S3MediaObjectStore}: leitura com `Range` (para o player arrastar a
 * barra) e escrita do arquivo já sanitizado. A raiz é temporária por padrão e o banco do dev é
 * H2 em memória — não há promessa de persistência entre reinícios.</p>
 */
class LocalMediaObjectStore implements MediaObjectStore {

	private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
		Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"), Map.entry("png", "image/png"),
		Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"), Map.entry("avif", "image/avif"),
		Map.entry("mp4", "video/mp4"), Map.entry("webm", "video/webm"), Map.entry("mov", "video/quicktime"),
		Map.entry("mp3", "audio/mpeg"), Map.entry("ogg", "audio/ogg"), Map.entry("wav", "audio/wav"),
		Map.entry("weba", "audio/webm"), Map.entry("pdf", "application/pdf"));

	private final Path root;

	LocalMediaObjectStore(Path root) {
		this.root = root;
		try {
			Files.createDirectories(root);
		}
		catch (IOException failure) {
			throw new IllegalStateException("Nao foi possivel criar o diretorio de midia local", failure);
		}
	}

	@Override
	public StoredObject read(String key, String range) {
		Path file = resolve(key);
		if (!Files.isRegularFile(file)) {
			// O controller traduz qualquer falha de leitura em 404 MEDIA_NOT_FOUND.
			throw new IllegalStateException("Objeto de midia nao encontrado: " + key);
		}
		try {
			long total = Files.size(file);
			String type = contentType(file);
			String eTag = "\"" + total + "-" + Files.getLastModifiedTime(file).toMillis() + "\"";
			if (range == null || range.isBlank()) {
				return new StoredObject(Files.newInputStream(file), total, type, null, eTag);
			}
			ByteRange slice = ByteRange.parse(range, total);
			FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
			channel.position(slice.start());
			InputStream body = new BoundedInputStream(Channels.newInputStream(channel), slice.length());
			return new StoredObject(body, slice.length(), type,
				"bytes " + slice.start() + "-" + slice.end() + "/" + total, eTag);
		}
		catch (IOException failure) {
			throw new IllegalStateException("Falha ao ler a midia local: " + key, failure);
		}
	}

	@Override
	public void write(String key, String contentType, long length, InputStream body) {
		Path file = resolve(key);
		try {
			Files.createDirectories(file.getParent());
			// Grava em temporario e move: nunca deixa um objeto pela metade servido publicamente.
			Path temp = Files.createTempFile(file.getParent(), ".upload-", ".tmp");
			try {
				long copied = Files.copy(body, temp, StandardCopyOption.REPLACE_EXISTING);
				if (copied != length) {
					throw new IllegalStateException("Tamanho recebido (" + copied
						+ ") difere do informado (" + length + ") na midia local.");
				}
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			finally {
				Files.deleteIfExists(temp);
			}
		}
		catch (IOException failure) {
			throw new IllegalStateException("Falha ao gravar a midia local: " + key, failure);
		}
	}

	/** Remove o objeto do disco (chamado pelo worker de exclusão via gateway). */
	void delete(String key) {
		Path file = resolve(key);
		try {
			Files.deleteIfExists(file);
		}
		catch (IOException failure) {
			throw new IllegalStateException("Falha ao apagar a midia local: " + key, failure);
		}
	}

	/** Chave fora da raiz (absoluta ou com travessia) nunca vira caminho de arquivo. */
	private Path resolve(String key) {
		if (key == null || key.isBlank() || key.contains("..") || key.contains("\\")) {
			throw new IllegalArgumentException("Chave de midia invalida.");
		}
		Path file = root.resolve(key).normalize();
		if (!file.startsWith(root)) {
			throw new IllegalArgumentException("Chave de midia invalida.");
		}
		return file;
	}

	private static String contentType(Path file) {
		String name = file.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
		return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
	}

	/** Limita a leitura ao intervalo pedido: o FileChannel sozinho iria até o fim do arquivo. */
	private static final class BoundedInputStream extends java.io.FilterInputStream {

		private long remaining;

		BoundedInputStream(InputStream in, long limit) {
			super(in);
			this.remaining = limit;
		}

		@Override
		public int read() throws IOException {
			if (remaining <= 0) {
				return -1;
			}
			int value = super.read();
			if (value >= 0) {
				remaining--;
			}
			return value;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			if (remaining <= 0) {
				return -1;
			}
			int read = super.read(buffer, offset, (int) Math.min(length, remaining));
			if (read > 0) {
				remaining -= read;
			}
			return read;
		}

		@Override
		public long skip(long count) throws IOException {
			long skipped = super.skip(Math.min(count, remaining));
			remaining -= skipped;
			return skipped;
		}

		@Override
		public int available() throws IOException {
			return (int) Math.min(super.available(), remaining);
		}
	}

	/** `bytes=inicio-fim`, `bytes=inicio-` e `bytes=-tamanho` (um intervalo só, como o app pede). */
	record ByteRange(long start, long end) {
		long length() {
			return end - start + 1;
		}

		static ByteRange parse(String header, long total) {
			String value = header.trim();
			if (!value.startsWith("bytes=") || value.indexOf(',') >= 0) {
				throw new IllegalArgumentException("Range de midia invalido.");
			}
			String spec = value.substring("bytes=".length()).trim();
			int dash = spec.indexOf('-');
			if (dash < 0) {
				throw new IllegalArgumentException("Range de midia invalido.");
			}
			String first = spec.substring(0, dash).trim();
			String last = spec.substring(dash + 1).trim();
			long start;
			long end;
			try {
				if (first.isEmpty()) {
					if (last.isEmpty()) {
						throw new IllegalArgumentException("Range de midia invalido.");
					}
					long suffix = Long.parseLong(last);
					if (suffix <= 0) {
						throw new IllegalArgumentException("Range de midia invalido.");
					}
					start = Math.max(0, total - suffix);
					end = total - 1;
				}
				else {
					start = Long.parseLong(first);
					end = last.isEmpty() ? total - 1 : Math.min(Long.parseLong(last), total - 1);
				}
			}
			catch (NumberFormatException failure) {
				throw new IllegalArgumentException("Range de midia invalido.", failure);
			}
			if (start < 0 || start >= total || end < start) {
				throw new IllegalArgumentException("Range de midia invalido.");
			}
			return new ByteRange(start, end);
		}
	}
}
