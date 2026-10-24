package com.socialcoisa.rede.shared.web;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * A superfície servida pelo aplicativo, lida do roteador em tempo de execução.
 *
 * <p>Nenhuma lista escrita à mão: quando um controller entra, o caminho dele entra aqui sozinho,
 * e quando sai, sai. Padrões com partes variáveis (<code>/posts/&#123;id&#125;</code>) são
 * comparados um a um; caminhos literais ficam num conjunto para resposta em tempo constante.
 *
 * <p>O <code>/v1</code> é um alias: um caminho versionado é conhecido quando o caminho de baixo é.
 * O índice é montado na primeira pergunta (não no boot) para não atrasar a inicialização, e se a
 * leitura do roteador falhar a resposta é conservadora: tudo é tratado como conhecido e passa ao
 * aplicativo, que responde do seu jeito.
 */
@Component
class RouteSurface {

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RouteSurface.class);

	private final ObjectProvider<RequestMappingHandlerMapping> mappings;

	private static final String V1_ALIAS = "/v1";

	private volatile Set<String> literais;
	private volatile List<PathPattern> variaveis;
	private volatile boolean completo;

	RouteSurface(@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mappings) {
		this.mappings = mappings;
	}

	/** O caminho é servido pelo aplicativo? Em caso de dúvida, a resposta conservadora é sim. */
	boolean conhecido(String caminho) {
		if (caminho == null || caminho.isEmpty()) {
			return true;
		}
		if (!completo) {
			carregar();
		}
		Set<String> literaisLocais = this.literais;
		if (literaisLocais == null) {
			return true;
		}
		if (literaisLocais.contains(caminho)) {
			return true;
		}
		if (caminho.startsWith(V1_ALIAS + "/")) {
			return conhecido(caminho.substring(V1_ALIAS.length()));
		}
		List<PathPattern> variaveisLocais = this.variaveis;
		if (variaveisLocais == null) {
			return false;
		}
		PathContainer contagem = PathContainer.parsePath(caminho);
		for (PathPattern padrao : variaveisLocais) {
			if (padrao.matches(contagem)) {
				return true;
			}
		}
		return false;
	}

	private synchronized void carregar() {
		if (completo) {
			return;
		}
		try {
			RequestMappingHandlerMapping roteador = mappings.getObject();
			Set<String> literaisNovos = new HashSet<>();
			List<PathPattern> variaveisNovas = new ArrayList<>();
			final boolean[] irregular = { false };
			PathPatternParser parser = PathPatternParser.defaultInstance;
			roteador.getHandlerMethods().keySet().forEach(info -> {
				for (String padrao : info.getPatternValues()) {
					if (padrao == null || padrao.isBlank()) {
						continue;
					}
					if (padrao.indexOf('{') < 0 && padrao.indexOf('*') < 0) {
						literaisNovos.add(padrao.toLowerCase(Locale.ROOT));
						continue;
					}
					try {
						variaveisNovas.add(parser.parse(padrao));
					}
					catch (RuntimeException padraoIrregular) {
						// Um padrão que este analisador não entende é tratado como conhecido:
						// nada que o roteador registrou pode ficar de fora da superfície.
						irregular[0] = true;
					}
				}
			});
			if (irregular[0]) {
				return;
			}
			this.literais = literaisNovos;
			this.variaveis = List.copyOf(variaveisNovas);
			this.completo = true;
		}
		catch (RuntimeException leituraFalhou) {
			// Sem o índice, cada caminho é considerado conhecido e o aplicativo responde.
			LOG.warn("indice_de_rotas_indisponivel", leituraFalhou);
			this.literais = null;
		}
	}

}
