package com.socialcoisa.rede.shared.web;

import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.tags.Tag;

import org.springframework.context.annotation.Bean;

import java.util.List;

@Configuration
class OpenApiConfig {

	@Bean
	OpenAPI redeOpenAPI() {
		return new OpenAPI()
			.info(new Info()
				.title("Rede API")
				.description("API do Connect Grow - rede social profissional")
				.version("0.1.0")
				.contact(new Contact().name("Social Coisa")))
			.tags(List.of(
				new Tag().name("identity").description("Autenticacao e sessao"),
				new Tag().name("profile").description("Perfil do usuario"),
				new Tag().name("feed").description("Feed, posts e interacoes"),
				new Tag().name("network").description("Conexoes e rede de contatos"),
				new Tag().name("media").description("Upload e gerenciamento de midias"),
				new Tag().name("communities").description("Comunidades"),
				new Tag().name("jobs").description("Vagas e candidaturas"),
				new Tag().name("events").description("Eventos"),
				new Tag().name("mentoring").description("Mentoria"),
				new Tag().name("messaging").description("Mensagens e conversas"),
				new Tag().name("notifications").description("Notificacoes"),
				new Tag().name("search").description("Busca global"),
				new Tag().name("operations").description("Health check e metricas operacionais")));
	}

}
