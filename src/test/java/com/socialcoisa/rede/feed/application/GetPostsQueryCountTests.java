package com.socialcoisa.rede.feed.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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

import com.socialcoisa.rede.communities.application.port.out.CommunityRepository;
import com.socialcoisa.rede.communities.domain.Community;
import com.socialcoisa.rede.communities.domain.CommunityMember;
import com.socialcoisa.rede.communities.domain.MemberRole;
import com.socialcoisa.rede.feed.application.port.out.ArticleRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.AuthorIdentity;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.network.application.port.out.UserBlockRepository;
import com.socialcoisa.rede.network.application.port.out.UserMuteRepository;
import com.socialcoisa.rede.network.domain.UserBlock;
import com.socialcoisa.rede.network.domain.UserMute;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@SpringBootTest
@Transactional
@Import(GetPostsQueryCountTests.InspectorConfig.class)
class GetPostsQueryCountTests {

	@TestConfiguration
	static class InspectorConfig {

		@Bean
		CountingInspector countingInspector() {
			return new CountingInspector();
		}

		@Bean
		HibernatePropertiesCustomizer statementInspector(CountingInspector inspector) {
			return properties -> properties.put(AvailableSettings.STATEMENT_INSPECTOR, inspector);
		}
	}

	static class CountingInspector implements StatementInspector {

		private final ThreadLocal<List<String>> sql = new ThreadLocal<>();

		@Override
		public String inspect(String statement) {
			List<String> capturado = sql.get();
			if (capturado != null) {
				capturado.add(statement);
			}
			return statement;
		}

		void iniciar() {
			sql.set(new ArrayList<>());
		}

		List<String> encerrar() {
			try {
				return List.copyOf(sql.get());
			}
			finally {
				sql.remove();
			}
		}
	}

	private record Medicao(List<PostView> resultado, List<String> sql) {

		long consultas(String tabela) {
			return sql.stream()
				.map(statement -> statement.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "))
				.filter(statement -> statement.contains("from " + tabela + " "))
				.count();
		}
	}

	private static final Instant DATA = Instant.parse("2024-01-01T00:00:00Z");

	@Autowired
	private GetFeedService feed;

	@Autowired
	private PostRepository posts;

	@Autowired
	private ArticleRepository articles;

	@Autowired
	private CommunityRepository communities;

	@Autowired
	private UserBlockRepository blocks;

	@Autowired
	private UserMuteRepository mutes;

	@Autowired
	private CountingInspector inspector;

	@PersistenceContext
	private EntityManager entityManager;

	private final String prefixo = "getposts_" + UUID.randomUUID().toString().substring(0, 8);

	@Test
	void carregaPostsEReacoesUmaVezParaUmOuVariosIds() {
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			Post post = novo("_lote_" + i, prefixo + "_autor", PostType.INSIGHT, null, null, null);
			posts.save(post);
			ids.add(post.id().value());
		}

		Medicao um = medir(ids.subList(0, 1));
		Medicao varios = medir(ids);

		assertThat(um.resultado()).extracting(PostView::id).containsExactly(ids.get(0));
		assertThat(varios.resultado()).extracting(PostView::id).containsExactlyElementsOf(ids);
		assertThat(um.consultas("posts")).isEqualTo(1);
		assertThat(um.consultas("reactions")).isEqualTo(1);
		assertThat(varios.consultas("posts"))
			.as("consultas aos posts: %s", varios.sql())
			.isEqualTo(um.consultas("posts"));
		assertThat(varios.consultas("reactions"))
			.as("consultas às reações: %s", varios.sql())
			.isEqualTo(um.consultas("reactions"));
	}

	@Test
	void preservaOrdemVisibilidadeEConteudoDaLeituraIndividual() {
		UserId leitor = new UserId(prefixo + "_leitor");
		String comunidadeMembro = prefixo + "_membro";
		String outraComunidade = prefixo + "_outra";
		communities.save(Community.create(comunidadeMembro, "Comunidade", "Descrição", null, null, null, null,
			leitor.value(), DATA));
		communities.save(Community.create(outraComunidade, "Outra comunidade", "Descrição", null, null, null, null,
			leitor.value(), DATA));
		entityManager.flush();
		communities.saveMember(CommunityMember.join(comunidadeMembro, leitor.value(), MemberRole.MEMBER, DATA));

		String autor = prefixo + "_autor";
		String autorBloqueado = prefixo + "_bloqueado";
		String autorSilenciado = prefixo + "_silenciado";
		blocks.save(UserBlock.create(leitor.value(), autorBloqueado, DATA));
		mutes.save(UserMute.create(leitor.value(), autorSilenciado, DATA));

		Post pagina = novo("_pagina", autor, PostType.PAGE, "<p>Página</p>", null, null);
		Post antigoArtigo = novo("_antigo", autor, PostType.INSIGHT, null, null, null);
		Post artigo = novo("_artigo", autor, PostType.ARTICLE, null, null, null);
		Post vencido = novo("_vencido", autor, PostType.MOMENT, null, null, null);
		Post apagado = novo("_apagado", autor, PostType.INSIGHT, null, null, null);
		apagado.delete(DATA);
		Post quarentena = novo("_quarentena", autor, PostType.INSIGHT, null, null, null);
		quarentena.quarantine();
		Post bloqueado = novo("_post_bloqueado", autorBloqueado, PostType.INSIGHT, null, null, null);
		Post silenciado = novo("_post_silenciado", autorSilenciado, PostType.INSIGHT, null, null, null);
		Post canalMembro = novo("_canal_membro", autor, PostType.INSIGHT, null, comunidadeMembro, "canal");
		Post canalOutro = novo("_canal_outro", autor, PostType.INSIGHT, null, outraComunidade, "canal");
		Post canalSemComunidade = novo("_canal_sem_comunidade", autor, PostType.INSIGHT, null, null, "canal");
		Post publicoOutraComunidade = novo("_publico", autor, PostType.INSIGHT, null, outraComunidade, null);

		for (Post post : List.of(pagina, antigoArtigo, artigo, vencido, apagado, quarentena, bloqueado,
				silenciado, canalMembro, canalOutro, canalSemComunidade, publicoOutraComunidade)) {
			posts.save(post);
		}
		articles.save(antigoArtigo.id(), "Corpo preservado", DATA);
		articles.save(artigo.id(), "Corpo do artigo", DATA);
		entityManager.flush();
		entityManager.clear();

		List<String> ids = List.of(pagina.id().value(), antigoArtigo.id().value(), pagina.id().value(),
			" " + pagina.id().value() + " ", vencido.id().value(), apagado.id().value(), quarentena.id().value(),
			bloqueado.id().value(), silenciado.id().value(), canalMembro.id().value(), canalOutro.id().value(),
			canalSemComunidade.id().value(), publicoOutraComunidade.id().value(), artigo.id().value(),
			prefixo + "_ausente");

		List<PostView> resultado = feed.getPosts(ids, leitor);
		PostView paginaIndividual = feed.getPost(pagina.id().value(), leitor);
		assertThat(resultado).containsExactly(paginaIndividual, feed.getPost(antigoArtigo.id().value(), leitor),
			paginaIndividual, feed.getPost(canalMembro.id().value(), leitor),
			feed.getPost(publicoOutraComunidade.id().value(), leitor), feed.getPost(artigo.id().value(), leitor));
		assertThat(resultado.get(0).pageHtml()).isEqualTo("<p>Página</p>");
		assertThat(resultado.get(1).articleBody()).isEqualTo("Corpo preservado");
		assertThat(resultado.get(5).articleBody()).isEqualTo("Corpo do artigo");

		assertThat(feed.getPosts(List.of(canalMembro.id().value(), canalOutro.id().value(),
				publicoOutraComunidade.id().value()), null))
			.extracting(PostView::id).containsExactly(publicoOutraComunidade.id().value());
	}

	@Test
	void preservaEntradasVaziasEOErroDeIdInvalido() {
		assertThat(feed.getPosts(null, null)).isEmpty();
		assertThat(feed.getPosts(List.of(), null)).isEmpty();
		assertThatThrownBy(() -> feed.getPosts(List.of(" "), null))
			.isInstanceOf(IllegalArgumentException.class).hasMessage("PostId vazio.");
		assertThatThrownBy(() -> feed.getPosts(Arrays.asList(prefixo + "_ausente", null), null))
			.isInstanceOf(IllegalArgumentException.class).hasMessage("PostId vazio.");
	}

	private Medicao medir(List<String> ids) {
		entityManager.flush();
		entityManager.clear();
		List<PostView> resultado;
		List<String> sql;
		inspector.iniciar();
		try {
			resultado = feed.getPosts(ids, null);
		}
		finally {
			sql = inspector.encerrar();
		}
		return new Medicao(resultado, sql);
	}

	private Post novo(String sufixo, String autor, PostType tipo, String html, String comunidade, String canal) {
		return Post.publish(new PostId(prefixo + sufixo), new UserId(autor), tipo, "Publicação de teste", html,
			List.of(), List.of(), comunidade, null, canal, DATA, AuthorIdentity.SOCIAL);
	}
}
