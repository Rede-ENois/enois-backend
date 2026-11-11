package com.socialcoisa.rede.network.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.socialcoisa.rede.shared.security.Hashes;
import com.socialcoisa.rede.shared.web.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * O que um nó {@code NODE_MEDIA} recebe quando o Control Plane designa {@link TaskType#MEDIA_TRANSCODE}.
 *
 * <p>O payload é o contrato do transcode e não pode ser inventado pelo nó: ele diz <b>qual</b>
 * objeto ler, com que <b>tipo</b>, e contra que <b>tamanho e hash</b> o resultado é conferido. O nó
 * executa e devolve o que produziu; quem decide se vale é o Control Plane, comparando o que o nó
 * declarou com o que ele mesmo mediu ao receber o arquivo.</p>
 *
 * <p>A receita do transcode mora aqui, não no nó: {@link #transcodeArgs} é a mesma lista de
 * argumentos que o runner local usa (mesmo filtro de escala, mesmos encoders, mesmos limites de
 * thread). Um nó que invente a própria receita produz um arquivo que o Control Plane aceita por
 * hash — mas o hash só prova que o arquivo é o que o nó mandou, não que ele é o que pedimos. Por
 * isso a receita é parte do contrato e o teste com ffmpeg real a executa dos dois lados.</p>
 */
public record MediaTranscodeSpec(String mediaId, String sourceKey, String mimeType, long sourceBytes,
		String sourceSha256, String resultKey, long resultBudgetBytes) {

	/** Únicos tipos que esta tarefa transcodifica: os mesmos vídeos que o upload aceita. */
	public static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/quicktime", "video/webm");

	/** Alvo do transcode: 854px no lado maior (480p, issue #656), igual ao que o pod faz. */
	public static final String SCALE_FILTER =
		"scale=w='if(gt(iw,ih),min(854,iw),-2)':h='if(gt(iw,ih),-2,min(854,ih))'";

	/** Mesmos encoders do runner local: um nó não pode produzir um arquivo que o pod não produziria. */
	public static final Map<String, List<String>> ENCODERS = Map.of(
		"video/mp4", List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "28", "-pix_fmt", "yuv420p",
			"-threads", "2", "-c:a", "aac", "-b:a", "128k"),
		"video/quicktime", List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "28", "-pix_fmt",
			"yuv420p", "-threads", "2", "-c:a", "aac", "-b:a", "128k"),
		"video/webm", List.of("-c:v", "libvpx-vp9", "-preset", "veryfast", "-crf", "34", "-b:v", "0",
			"-row-mt", "1", "-threads", "2", "-pix_fmt", "yuv420p", "-c:a", "libopus", "-b:a", "96k"));

	/** Demuxer/muxer fixos por tipo, como no runner local: o conteúdo do arquivo não escolhe nada. */
	private static final Map<String, String[]> FORMATS = Map.of(
		"video/mp4", new String[] { "mov", "mp4" },
		"video/quicktime", new String[] { "mov", "mov" },
		"video/webm", new String[] { "matroska", "webm" });

	public MediaTranscodeSpec {
		if (mediaId == null || mediaId.isBlank() || sourceKey == null || sourceKey.isBlank()
				|| resultKey == null || resultKey.isBlank()) {
			throw new IllegalArgumentException("Spec de transcode sem chave de objeto.");
		}
		if (mimeType == null || !VIDEO_TYPES.contains(mimeType.trim().toLowerCase(Locale.ROOT))) {
			throw new IllegalArgumentException("Tipo de midia nao transcodificavel: " + mimeType);
		}
		if (sourceBytes <= 0 || resultBudgetBytes <= 0) {
			throw new IllegalArgumentException("Spec de transcode com tamanho invalido.");
		}
		if (sourceSha256 == null || !sourceSha256.trim().toLowerCase(Locale.ROOT).matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Spec de transcode sem hash sha256 valido.");
		}
	}

	/**
	 * O canon que o hash cobre. Objeto pequeno de propósito, em ordem fixa e sem campo opcional:
	 * qualquer coisa a mais obrigaria os dois lados a serializar igual, e qualquer coisa a menos
	 * deixaria de fora algo que muda o resultado servido.
	 */
	public static String canon(String mimeType, String sourceKey) {
		return "{\"type\":\"" + mimeType.trim().toLowerCase(Locale.ROOT) + "\",\"sourceKey\":\"" + sourceKey.trim()
			+ "\"}";
	}

	/** Hash do canon: a identidade da receita, independente de quem a executa. */
	public static String canonHash(String mimeType, String sourceKey) {
		return Hashes.sha256Hex(canon(mimeType, sourceKey));
	}

	/** Extensão do objeto produzido, por tipo: o contêiner da saída é o mesmo da entrada. */
	public static String resultExtension(String mimeType) {
		String type = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
		return switch (type) {
			case "video/mp4" -> ".mp4";
			case "video/quicktime" -> ".mov";
			case "video/webm" -> ".webm";
			default -> throw new IllegalArgumentException("Tipo de midia nao transcodificavel: " + mimeType);
		};
	}

	/** Onde o resultado é gravado. Derivado do mediaId: o nó não escolhe destino. */
	public static String resultKeyFor(String mediaId, String mimeType) {
		return mediaId + "/file.transcoded" + resultExtension(mimeType);
	}

	/**
	 * A invocação do transcode, idêntica à do runner local. {@code -protocol_whitelist file} e os
	 * caminhos absolutos são o que impede o conteúdo do arquivo de decidir o que o ffmpeg abre.
	 */
	public List<String> transcodeArgs(String ffmpegPath, String inputPath, String outputPath) {
		String type = mimeType.trim().toLowerCase(Locale.ROOT);
		String[] formats = FORMATS.get(type);
		List<String> args = new ArrayList<>(List.of(ffmpegPath, "-hide_banner", "-nostdin", "-loglevel", "error",
			"-y", "-protocol_whitelist", "file", "-f", formats[0], "-i", "file:" + inputPath, "-map", "0:v:0",
			"-map", "0:a?", "-sn", "-dn", "-map_metadata", "-1", "-map_chapters", "-1", "-vf", SCALE_FILTER));
		args.addAll(ENCODERS.get(type));
		if ("video/mp4".equals(type)) {
			// O moov na frente: o player do feed não pode baixar o arquivo inteiro para achar a duração.
			args.addAll(List.of("-movflags", "+faststart"));
		}
		args.addAll(List.of("-f", formats[1], "file:" + outputPath));
		return List.copyOf(args);
	}

	/** Lê o payload; qualquer campo ausente ou fora da faixa é {@code TASK_PAYLOAD_INVALID} (400). */
	public static MediaTranscodeSpec parse(String payload, ObjectMapper objectMapper) {
		JsonNode root;
		try {
			root = objectMapper.readTree(payload == null ? "null" : payload);
		}
		catch (RuntimeException malformed) {
			throw invalid();
		}
		if (root == null || !root.isObject()) {
			throw invalid();
		}
		String mediaId = text(root, "mediaId");
		String mimeType = text(root, "mimeType");
		String resultKey = text(root, "resultKey");
		if (!resultKeyFor(mediaId, mimeType).equals(resultKey)) {
			// O nó escreve na chave que o Control Plane decidir; um payload com destino diferente
			// do padrão do mediaId é payload adulterado, e aceitá-lo viraria escrita arbitrária.
			throw invalid();
		}
		try {
			return new MediaTranscodeSpec(mediaId, text(root, "sourceKey"), mimeType,
				root.get("sourceBytes").asLong(), text(root, "sourceSha256"), resultKey,
				root.get("resultBudgetBytes").asLong());
		}
		catch (RuntimeException invalid) {
			throw invalid();
		}
	}

	private static ApiException invalid() {
		return new ApiException(400, "TASK_PAYLOAD_INVALID", "Payload da tarefa de midia invalido.");
	}

	private static String text(JsonNode root, String field) {
		JsonNode value = root.get(field);
		if (value == null || !value.isString() || value.asString().isBlank()) {
			throw invalid();
		}
		return value.asString();
	}
}
