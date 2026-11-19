package com.socialcoisa.rede.shared.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;

/**
 * Versão pública mínima do pod. O detalhamento operacional (uptime, storage, Flyway) expunha
 * superfície para qualquer um (E-34) e só sai com o token de diagnóstico configurado no ambiente.
 */
@RestController
class VersionController {

	private final Instant startedAt = Instant.now();
	private final ObjectProvider<BuildProperties> build;
	private final ObjectProvider<Flyway> flyway;
	private final String mediaStorage;
	private final ObjectProvider<com.socialcoisa.rede.media.application.port.out.MediaUploadSigner> uploadSigner;
	private final String detailsToken;

	VersionController(ObjectProvider<BuildProperties> build, ObjectProvider<Flyway> flyway,
			@org.springframework.beans.factory.annotation.Value("${rede.media.storage:imagekit}") String mediaStorage,
			ObjectProvider<com.socialcoisa.rede.media.application.port.out.MediaUploadSigner> uploadSigner,
			@org.springframework.beans.factory.annotation.Value("${rede.version.details-token:}") String detailsToken) {
		this.build = build;
		this.flyway = flyway;
		this.mediaStorage = mediaStorage;
		this.uploadSigner = uploadSigner;
		this.detailsToken = detailsToken;
	}

	@GetMapping("/version")
	Map<String, Object> version(
			@RequestHeader(value = "X-Version-Details", required = false) String providedToken) {
		Map<String, Object> view = VersaoPublica.de(build.getIfAvailable());
		if (!detailsAllowed(providedToken)) {
			view.put("status", "UP");
			return view;
		}
		view.put("bootEm", startedAt.toString());
		view.put("uptimeSegundos", java.time.Duration.between(startedAt, Instant.now()).toSeconds());
		Map<String, Object> midia = new LinkedHashMap<>();
		midia.put("storage", mediaStorage);
		midia.put("uploads", uploadSigner.getIfAvailable() != null ? "s3-assinado" : "indisponivel");
		view.put("midia", midia);

		Flyway fw = flyway.getIfAvailable();
		if (fw != null) {
			Map<String, Object> estado = new LinkedHashMap<>();
			int applied = 0;
			int pending = 0;
			int failed = 0;
			String ultima = null;
			for (MigrationInfo info : fw.info().all()) {
				MigrationState state = info.getState();
				if (state == null) {
					continue;
				}
				switch (state) {
					case SUCCESS -> {
						applied++;
						ultima = info.getVersion() + " " + info.getDescription();
					}
					case PENDING -> pending++;
					case FAILED -> failed++;
					default -> {
						// IGNORED/MISSING etc não contam como saudável nem como problema de boot
					}
				}
			}
			estado.put("aplicadas", applied);
			estado.put("pendentes", pending);
			estado.put("falhadas", failed);
			estado.put("ultima", ultima);
			view.put("flyway", estado);
		}
		return view;
	}

	private boolean detailsAllowed(String provided) {
		if (!StringUtils.hasText(detailsToken) || !StringUtils.hasText(provided)) {
			return false;
		}
		return MessageDigest.isEqual(detailsToken.getBytes(StandardCharsets.UTF_8),
			provided.getBytes(StandardCharsets.UTF_8));
	}
}
