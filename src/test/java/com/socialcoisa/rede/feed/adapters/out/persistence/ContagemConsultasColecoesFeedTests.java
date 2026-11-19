package com.socialcoisa.rede.feed.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.application.GetFeedQuery;
import com.socialcoisa.rede.feed.application.GetFeedService;
import com.socialcoisa.rede.feed.application.PostView;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.FeedTab;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.network.application.port.out.ConnectionRepository;
import com.socialcoisa.rede.network.domain.UserConnection;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@SpringBootTest(properties = {
		"spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
		"spring.jpa.properties.hibernate.cache.use_query_cache=false" })
@Import(ContagemConsultasColecoesFeedTests.ConfiguracaoInspector.class)
@Transactional
class ContagemConsultasColecoesFeedTests {

	@TestConfiguration
	static class ConfiguracaoInspector {

		@Bean
		ContadorConsultas contadorConsultas() {
			return new ContadorConsultas();
		}

		@Bean
		HibernatePropertiesCustomizer inspectorHibernate(ContadorConsultas contador) {
			return properties -> properties.put(AvailableSettings.STATEMENT_INSPECTOR, contador);
		}
	}

	static class ContadorConsultas implements StatementInspector {

		private final ThreadLocal<List<String>> consultas = new ThreadLocal<>();

		@Override
		public String inspect(String sql) {
			List<String> observadas = consultas.get();
			if (observadas != null) {
				observadas.add(sql);
			}
			return sql;
		}

		void iniciar() {
			consultas.set(new ArrayList<>());
		}

		List<String> encerrar() {
			List<String> resultado = List.copyOf(consultas.get());
			consultas.remove();
			return resultado;
		}
	}

	@Autowired private GetFeedService feed;
	@Autowired private PostRepository posts;
	@Autowired private UserProfileRepository perfis;
	@Autowired private ConnectionRepository conexoes;
	@Autowired private ContadorConsultas contador;
	@PersistenceContext private EntityManager entityManager;

	@Test
	void listagemCarregaCadaColecaoEmUmaConsulta() {
		String prefixo = "colecoes_" + UUID.randomUUID().toString().substring(0, 8);
		UserId leitor = new UserId(prefixo + "_leitor");
		Instant instante = Instant.parse("2024-01-01T00:00:00Z");
		List<String> autores = new ArrayList<>();
		List<String> ids = new ArrayList<>();

		perfis.save(UserProfile.create(leitor.value(), leitor.value(), "contato_" + leitor.value(),
			"Leitor", null, instante));
		for (int i = 0; i < 8; i++) {
			String autor = prefixo + "_autor_" + i;
			String id = prefixo + "_post_" + i;
			UserProfile perfil = UserProfile.create(autor, autor, "contato_" + autor, "Autor " + i, null, instante);
			perfil.update(null, null, null, null, null, null, null, null,
				List.of("habilidade_" + i), List.of("objetivo_" + i), null, null,
				List.of("link_" + i), null, null, null, null, instante);
			perfis.save(perfil);
			conexoes.save(UserConnection.request(leitor.value(), autor, instante).accept(instante));
			posts.save(Post.publish(new PostId(id), new UserId(autor), PostType.INSIGHT,
				"Publicação de teste", null, List.of("tag_" + i), List.of("midia_" + i),
				null, instante.minusSeconds(i)));
			autores.add(autor);
			ids.add(id);
		}

		entityManager.flush();
		entityManager.clear();

		List<PostView> pagina;
		List<String> consultas;
		contador.iniciar();
		try {
			pagina = feed.getFeed(new GetFeedQuery(leitor, Optional.empty(), 8, FeedTab.FOLLOWING)).items();
		}
		finally {
			consultas = contador.encerrar();
		}

		assertThat(pagina).extracting(PostView::id).containsExactlyElementsOf(ids);
		for (int i = 0; i < 8; i++) {
			assertThat(pagina.get(i).authorId()).isEqualTo(autores.get(i));
			assertThat(pagina.get(i).authorUsername()).isEqualTo(autores.get(i));
			assertThat(pagina.get(i).tags()).containsExactly("tag_" + i);
			assertThat(pagina.get(i).mediaIds()).containsExactly("midia_" + i);
		}
		for (String tabela : List.of("post_tags", "post_media", "user_skills", "user_goals",
				"user_external_links")) {
			long quantidade = consultas.stream()
				.map(sql -> sql.stripLeading().toLowerCase(Locale.ROOT))
				.filter(sql -> sql.startsWith("select") && sql.contains("from " + tabela))
				.count();
			assertThat(quantidade).as("consultas em %s: %s", tabela, consultas).isEqualTo(1);
		}

		List<UserProfile> carregados = perfis.findAllByIds(autores);
		assertThat(carregados).hasSize(8);
		for (int i = 0; i < 8; i++) {
			String autor = autores.get(i);
			UserProfile perfil = carregados.stream().filter(item -> item.id().equals(autor)).findFirst().orElseThrow();
			assertThat(perfil.skills()).containsExactly("habilidade_" + i);
			assertThat(perfil.goals()).containsExactly("objetivo_" + i);
			assertThat(perfil.externalLinks()).containsExactly("link_" + i);
		}
	}
}
