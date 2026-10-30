package com.socialcoisa.rede.emotes.adapters.out.providers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.emotes.domain.Emote;
import com.socialcoisa.rede.emotes.domain.EmoteProvider;

import tools.jackson.databind.ObjectMapper;

class EmoteSourcesTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Test
	void bttvLeIdCodigoEAnimacao() {
		String body = """
				[{"id":"5ff8274b8c4b6d6a6a6f4d3a","code":"KEKW","imageType":"png"},
				 {"id":"60ae1c7c6a6f4d3a","code":"catJAM","animated":true,"imageType":"gif"},
				 {"id":"x1","code":"com espaco","animated":false}]
				""";

		List<Emote> emotes = BttvEmoteSource.parse(JSON.readTree(body));

		assertThat(emotes).extracting(Emote::code).containsExactly("KEKW", "catJAM");
		assertThat(emotes).allMatch((emote) -> emote.provider() == EmoteProvider.BTTV);
		assertThat(emotes.get(1).animated()).isTrue();
	}

	@Test
	void ffzGlobalEntraSoNosDefaultSetsESemOculta() {
		String body = """
				{"default_sets":[3],
				 "sets":{"3":{"emoticons":[
					{"name":"catJAM","urls":{"1":"https://cdn.frankerfacez.com/emote/100/1","2":"https://cdn.frankerfacez.com/emote/100/2"}},
					{"name":"oculta","hidden":true,"urls":{"2":"https://cdn.frankerfacez.com/emote/101/2"}},
					{"name":"modificador","modifier":true,"urls":{"2":"https://cdn.frankerfacez.com/emote/102/2"}},
					{"name":"animada","urls":{"2":"https://cdn.frankerfacez.com/emote/103/2"},"animated":{"2":"https://cdn.frankerfacez.com/emote/103/animated/2"}}
				 ]},
				 "9":{"emoticons":[{"name":"deSetPrivado","urls":{"2":"https://cdn.frankerfacez.com/emote/200/2"}}]}}}
				""";

		List<Emote> emotes = FfzGlobalEmoteSource.parse(JSON.readTree(body));

		assertThat(emotes).extracting(Emote::code).containsExactly("catJAM", "animada");
		assertThat(emotes.get(0).id()).isEqualTo("100");
		assertThat(emotes.get(1).animated()).isTrue();
	}

	@Test
	void retratoEmbarcadoTrazFigurinhaComumEAnimadaSemRede() throws Exception {
		List<Emote> emotes = new EmbeddedCatalogSource(JSON).load();

		// O que o pod serve quando nenhum provedor responde: colecao global dos dois + populares.
		assertThat(emotes).hasSizeGreaterThan(800);
		assertThat(emotes).anyMatch((emote) -> emote.provider() == EmoteProvider.BTTV);
		assertThat(emotes).anyMatch((emote) -> emote.provider() == EmoteProvider.FFZ);
		assertThat(emotes.stream().filter(Emote::animated)).hasSizeGreaterThan(200);
		assertThat(emotes.get(0).code()).isEqualTo("OMEGALUL");
		assertThat(emotes.get(0).popularRank()).isEqualTo(1);
		// Codigo unico: o seletor pagina pelo catalogo, e codigo repetido viraria figurinha repetida.
		assertThat(emotes).extracting(Emote::code).doesNotHaveDuplicates();
		// Posicao continua e unica: o seletor depende disso para nao repetir da 1a pagina para a 2a.
		List<Integer> posicoes = emotes.stream().filter(Emote::popular).map(Emote::popularRank).toList();
		assertThat(posicoes).isNotEmpty().doesNotHaveDuplicates();
	}

	@Test
	void retratoEmbarcadoIgnoraLinhaQueNaoSeriaUsadaNoTexto() {
		String body = """
				[{"code":"KEKW","provider":"BTTV","id":"abc","animated":false},
				 {"code":"catJAM","provider":"FFZ","id":"777171","animated":true,"popularRank":7},
				 {"code":"com espaco","provider":"FFZ","id":"1"},
				 {"code":"SEMPROVEDOR","provider":"7TV","id":"1"},
				 {"code":"SEMID","provider":"FFZ"}]
				""";

		List<Emote> emotes = EmbeddedCatalogSource.parse(JSON.readTree(body));

		assertThat(emotes).extracting(Emote::code).containsExactly("KEKW", "catJAM");
		assertThat(emotes.get(1).animated()).isTrue();
		assertThat(emotes.get(1).popularRank()).isEqualTo(7);
		assertThat(EmbeddedCatalogSource.parse(JSON.readTree("{}"))).isEmpty();
		assertThat(EmbeddedCatalogSource.parse(null)).isEmpty();
	}

	@Test
	void idSaiDoCaminhoDoCdnDoFfz() {
		assertThat(FfzEmoteJson.idFromUrl("https://cdn.frankerfacez.com/emote/128054/2")).isEqualTo("128054");
		assertThat(FfzEmoteJson.idFromUrl("https://cdn.frankerfacez.com/emote/128054/animated/2")).isEqualTo("128054");
		assertThat(FfzEmoteJson.idFromUrl("https://exemplo.com/sem-id/2")).isNull();
		assertThat(FfzEmoteJson.idFromUrl(null)).isNull();
	}

	@Test
	void listagemDoFfzVemEmbrulhadaEmEmoticons() {
		// Regressao: a listagem NAO e uma lista solta — e `{"_pages":…,"_total":…,"emoticons":[…]}`.
		// Iterar o objeto dava zero figurinha, e a fonte "respondia bem" sem trazer nada.
		String body = """
				{"_pages":7,"_total":1400,
				 "emoticons":[
					{"id":128054,"name":"OMEGALUL","urls":{"2":"https://cdn.frankerfacez.com/emote/128054/2"}},
					{"id":777171,"name":"catJAM","urls":{"2":"https://cdn.frankerfacez.com/emote/777171/2"},
					 "animated":{"2":"https://cdn.frankerfacez.com/emote/777171/animated/2"}}
				 ]}
				""";

		List<Emote> emotes = FfzListingEmoteSource.emoticons(JSON.readTree(body))
			.stream()
			.map((node) -> FfzEmoteJson.toEmote(node, null))
			.toList();

		assertThat(emotes).extracting(Emote::code).containsExactly("OMEGALUL", "catJAM");
		assertThat(emotes.get(1).animated()).isTrue();
		assertThat(FfzListingEmoteSource.emoticons(JSON.readTree("{}"))).isEmpty();
		assertThat(FfzListingEmoteSource.emoticons(null)).isEmpty();
	}
}