package com.socialcoisa.rede.moderation.application;

import java.util.Optional;

import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.moderation.application.TransparenciaSancaoService.Evidencia;
import com.socialcoisa.rede.moderation.application.port.out.BanContentArchiveRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentAuthorReader;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentReader;
import com.socialcoisa.rede.moderation.application.port.out.MetadadosMidiaDenunciadaReader;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.UserSanction;

import tools.jackson.databind.ObjectMapper;

@Service
public class EvidenciaSancaoService {
	private final ReportRepository denuncias;
	private final ReportedContentReader conteudo;
	private final ReportedContentAuthorReader autores;
	private final BanContentArchiveRepository arquivo;
	private final ObjectMapper json;
	private final MetadadosMidiaDenunciadaReader midia;

	public EvidenciaSancaoService(ReportRepository denuncias, ReportedContentReader conteudo,
			ReportedContentAuthorReader autores, BanContentArchiveRepository arquivo, ObjectMapper json, MetadadosMidiaDenunciadaReader midia) {
		this.denuncias = denuncias;
		this.conteudo = conteudo;
		this.autores = autores;
		this.arquivo = arquivo;
		this.json = json;
		this.midia = midia;
	}

	public Evidencia daSancao(UserSanction sancao) {
		if (origemComportamental(sancao.reportId())) {
			return new Evidencia("BEHAVIOR", "Decisao sobre a conduta da conta, sem publicacao unica associada.");
		}
		return Optional.ofNullable(sancao.reportId()).flatMap(denuncias::findById)
			.map(denuncia -> doAlvo(sancao, denuncia)).orElse(new Evidencia("UNAVAILABLE", null));
	}

	private boolean origemComportamental(String referencia) {
		return referencia != null && java.util.stream.Stream.of("admin:", "proposal:", "automod_spam_", "AUTO:HONEYPOT:", "dispute_")
			.anyMatch(referencia::startsWith);
	}

	private Evidencia doAlvo(UserSanction sancao, Report denuncia) {
		boolean proprio = autores.authorOf(denuncia).filter(sancao.userId()::equals).isPresent();
		if (proprio && midia.hasMediaOf(denuncia)) {
			return new Evidencia("MEDIA", "Midia associada a decisao. A reproducao nao esta disponivel nesta consulta.");
		}
		// O leitor por Report inclui relatos privados em chamadas. Nunca usá-lo nesta fronteira.
		Optional<String> texto = proprio ? conteudo.contentOf(denuncia.targetType().name(), denuncia.targetId()) : Optional.empty();
		return texto.or(() -> textoArquivado(sancao, denuncia)).map(this::sanitizar)
			.filter(valor -> !valor.isBlank()).map(valor -> new Evidencia("TEXT", valor))
			.orElse(new Evidencia("UNAVAILABLE", null));
	}

	private Optional<String> textoArquivado(UserSanction sancao, Report denuncia) {
		return arquivo.findSnapshot(sancao.id(), sancao.userId(), denuncia.targetType().name(), denuncia.targetId())
			.flatMap(snapshot -> {
				try {
					var texto = json.readTree(snapshot).path("content");
					return texto.isString() ? Optional.of(texto.asText()) : Optional.empty();
				}
				catch (tools.jackson.core.JacksonException invalido) {
					// Arquivos legados malformados não comprovam evidência textual.
					return Optional.empty();
				}
			});
	}

	private String sanitizar(String texto) {
		var documento = Jsoup.parse(texto);
		documento.select("script,style,noscript,iframe,object,svg").remove();
		String limpo = documento.text().replaceAll("[\\p{Cc}\\p{Cf}]", " ")
			.replaceAll("(?i)(?:https?://|www\\.)\\S+", "[link omitido]").trim();
		return limpo.substring(0, limpo.offsetByCodePoints(0, Math.min(500, limpo.codePointCount(0, limpo.length()))));
	}
}
