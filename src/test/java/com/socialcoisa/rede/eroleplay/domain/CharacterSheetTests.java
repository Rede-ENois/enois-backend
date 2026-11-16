package com.socialcoisa.rede.eroleplay.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * As regras da ficha, provadas no domínio.
 *
 * <p>Estas asserções são o contrato de privacidade e de validação: se a régua de quem enxerga o quê
 * ou a validação de valor mudar, é aqui que a mudança aparece — antes da tela, antes da API.</p>
 */
class CharacterSheetTests {

	private static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");

	private static SheetTemplate template() {
		return SheetTemplate.from("tpl_1", "camp_1", new D20System(), Map.of(), NOW);
	}

	@Test
	void oModeloNasceDosAtributosDoSistema() {
		SheetTemplate template = template();

		assertThat(template.gameSystem()).isEqualTo(D20System.ID);
		assertThat(template.fields()).extracting(SheetField::key)
			.containsExactly("forca", "destreza", "constituicao", "inteligencia", "sabedoria", "carisma", "nivel",
				"pontos_de_vida", "defesa", "origem", "historia");
		assertThat(template.byKey().get("historia").required()).isFalse();
		assertThat(template.byKey().get("origem").options()).contains("Acadêmico");
		// Sem escolha do mestre, o modelo nasce fechado até o mestre: o jogador não abre nada sozinho.
		assertThat(template.fields()).allMatch(field -> field.maxVisibility() == SheetVisibility.GAME_MASTER);
	}

	@Test
	void oMestrePodeFecharUmCampoNoModelo() {
		SheetTemplate template = SheetTemplate.from("tpl_1", "camp_1", new D20System(),
			Map.of("historia", SheetVisibility.CAMPAIGN, "pontos_de_vida", SheetVisibility.PRIVATE), NOW);

		assertThat(template.byKey().get("historia").maxVisibility()).isEqualTo(SheetVisibility.CAMPAIGN);
		assertThat(template.byKey().get("pontos_de_vida").maxVisibility()).isEqualTo(SheetVisibility.PRIVATE);
		// Ajustar um campo não muda os outros.
		assertThat(template.byKey().get("forca").maxVisibility()).isEqualTo(SheetVisibility.GAME_MASTER);
	}

	@Test
	void umCampoQueOModeloNaoTemNaoViraCampoDaFicha() {
		assertThatThrownBy(() -> template().withVisibilities(Map.of("inexistente", SheetVisibility.PRIVATE), NOW))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("inexistente");
	}

	@Test
	void cadaPublicoEnxergaExatamenteOQueAVisibilidadePermite() {
		assertThat(SheetAudience.OWNER.sees(SheetVisibility.PRIVATE)).isTrue();
		assertThat(SheetAudience.GAME_MASTER.sees(SheetVisibility.PRIVATE)).isFalse();
		assertThat(SheetAudience.GAME_MASTER.sees(SheetVisibility.GAME_MASTER)).isTrue();
		assertThat(SheetAudience.GAME_MASTER.sees(SheetVisibility.CAMPAIGN)).isTrue();
		assertThat(SheetAudience.MEMBER.sees(SheetVisibility.PRIVATE)).isFalse();
		assertThat(SheetAudience.MEMBER.sees(SheetVisibility.GAME_MASTER)).isFalse();
		assertThat(SheetAudience.MEMBER.sees(SheetVisibility.CAMPAIGN)).isTrue();
	}

	@Test
	void oVisitanteNaoEnxergaCampoNenhum() {
		CharacterSheet sheet = CharacterSheet.fill("sh_1", "camp_1", "user_1", "Aranha", baseEntries(), template(), NOW);

		assertThat(sheet.visibleTo(SheetAudience.MEMBER)).isEmpty();
		assertThat(sheet.visibleTo(null)).isEmpty();
		assertThat(sheet.visibleTo(SheetAudience.OWNER)).hasSize(10);
	}

	@Test
	void oCampoFechadoNaoVoltaNemComAChave() {
		SheetTemplate template = SheetTemplate.from("tpl_1", "camp_1", new D20System(),
			Map.of("historia", SheetVisibility.PRIVATE), NOW);
		CharacterSheet sheet = CharacterSheet.fill("sh_1", "camp_1", "user_1", "Aranha",
			withEntry(baseEntries(), "historia", "segredo da familia", SheetVisibility.PRIVATE), template, NOW);

		assertThat(sheet.visibleTo(SheetAudience.MEMBER)).isEmpty();
		// Fechada a história, o mestre ainda lê os outros nove campos — o que ele perde é só o campo
		// fechado, não a ficha inteira.
		assertThat(sheet.visibleTo(SheetAudience.GAME_MASTER)).extracting(SheetEntry::key)
			.doesNotContain("historia")
			.contains("forca", "origem");
		assertThat(sheet.visibleTo(SheetAudience.OWNER)).hasSize(11);
	}

	@Test
	void oJogadorNaoAbreUmCampoAlemDoMaximoDoMestre() {
		SheetTemplate template = SheetTemplate.from("tpl_1", "camp_1", new D20System(),
			Map.of("historia", SheetVisibility.PRIVATE), NOW);

		assertThatThrownBy(() -> CharacterSheet.fill("sh_1", "camp_1", "user_1", "Aranha",
			withEntry(baseEntries(), "historia", "segredo", SheetVisibility.CAMPAIGN), template, NOW))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("História");
	}

	@Test
	void oJogadorPodeFecharMaisDoQueOMaximo() {
		CharacterSheet sheet = CharacterSheet.fill("sh_1", "camp_1", "user_1", "Aranha",
			withEntry(baseEntries(), "origem", "Acadêmico", SheetVisibility.PRIVATE), template(), NOW);

		assertThat(sheet.visibleTo(SheetAudience.GAME_MASTER)).extracting(SheetEntry::key)
			.doesNotContain("origem");
		assertThat(sheet.visibleTo(SheetAudience.OWNER)).hasSize(10);
	}

	@Test
	void campoObrigatorioAusenteNaoEntra() {
		assertThatThrownBy(() -> CharacterSheet.fill("sh_1", "camp_1", "user_1", "Aranha",
			List.of(new SheetEntry("forca", "12", SheetVisibility.GAME_MASTER)), template(), NOW))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("Destreza");
	}

	@Test
	void campoOpcionalVazioEBrancoViraAusencia() {
		CharacterSheet sheet = CharacterSheet.fill("sh_1", "camp_1", "user_1", "Aranha",
			withEntry(baseEntries(), "historia", "   ", SheetVisibility.GAME_MASTER), template(), NOW);

		assertThat(sheet.entry("historia")).isEmpty();
		assertThat(sheet.visibleTo(SheetAudience.OWNER)).hasSize(10);
	}

	@Test
	void numeroInvalidoNaoEDigitadoEmCampoNumerico() {
		assertThatThrownBy(() -> fillWith("forca", "muito forte")).isInstanceOf(ApiException.class)
			.hasMessageContaining("Força");
		assertThatThrownBy(() -> fillWith("forca", "1234567890")).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> fillWith("forca", "1.23456")).isInstanceOf(ApiException.class);
	}

	@Test
	void numeroValidoENormalizadoSemZeroInutil() {
		assertThat(fillWith("forca", "12").entry("forca").orElseThrow().value()).isEqualTo("12");
		assertThat(fillWith("forca", "12,5").entry("forca").orElseThrow().value()).isEqualTo("12.5");
		assertThat(fillWith("forca", "-3").entry("forca").orElseThrow().value()).isEqualTo("-3");
		assertThat(fillWith("forca", "012").entry("forca").orElseThrow().value()).isEqualTo("12");
	}

	@Test
	void escolhaForaDasOpcoesERecusada() {
		assertThatThrownBy(() -> fillWith("origem", "Imperador")).isInstanceOf(ApiException.class)
			.hasMessageContaining("Origem");
		assertThat(fillWith("origem", "artista").entry("origem").orElseThrow().value()).isEqualTo("Artista");
	}

	@Test
	void textoDeLinhaUnicaNaoAceitaCaractereDeControle() {
		// O D20 não declara campo TEXT de linha única; um sistema de teste declara, porque é o
		// sistema que define os campos.
		SystemAdapter anotacoes = new SystemAdapter() {
			@Override
			public String id() {
				return "sistema-de-anotacoes";
			}

			@Override
			public String label() {
				return "Sistema de Anotações";
			}

			@Override
			public GameSystemFamily family() {
				return GameSystemFamily.CUSTOM;
			}

			@Override
			public String author() {
				return "teste";
			}

			@Override
			public String license() {
				return "teste";
			}

			@Override
			public String officialUrl() {
				return null;
			}

			@Override
			public boolean builtIn() {
				return false;
			}

			@Override
			public List<SheetAttribute> attributes() {
				return List.of(new SheetAttribute("anotacao", "Anotação", AttributeType.TEXT, false, List.of()));
			}
		};
		SheetTemplate template = SheetTemplate.from("tpl_2", "camp_1", anotacoes, Map.of(), NOW);

		// Quebra de linha é de campo longo; em TEXT de linha única é caractere de controle.
		assertThatThrownBy(() -> CharacterSheet.fill("sh_2", "camp_1", "user_1", "Aranha",
			List.of(new SheetEntry("anotacao", "linha 1\nlinha 2", SheetVisibility.GAME_MASTER)), template, NOW))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("Anotação");
		// Em campo longo, a quebra de linha é legítima.
		assertThat(CharacterSheet.fill("sh_3", "camp_1", "user_1", "Aranha",
			withEntry(baseEntries(), "historia", "linha 1\nlinha 2", SheetVisibility.GAME_MASTER), template(), NOW)
			.entry("historia")).isPresent();
	}

	@Test
	void valorAcimaDoTetoDoTipoERecusado() {
		assertThatThrownBy(() -> fillWith("historia", "a".repeat(4001))).isInstanceOf(ApiException.class)
			.hasMessageContaining("4000");
	}

	@Test
	void campoDesconhecidoERecusado() {
		List<SheetEntry> entries = new ArrayList<>(baseEntries());
		entries.add(new SheetEntry("poder_oculto", "99", SheetVisibility.PRIVATE));

		assertThatThrownBy(() -> CharacterSheet.fill("sh_1", "camp_1", "user_1", "Aranha", entries, template(), NOW))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("poder_oculto");
	}

	@Test
	void oMesmoCampoDuasVezesERecusado() {
		List<SheetEntry> entries = new ArrayList<>(baseEntries());
		entries.add(new SheetEntry("forca", "99", SheetVisibility.GAME_MASTER));

		assertThatThrownBy(() -> CharacterSheet.fill("sh_1", "camp_1", "user_1", "Aranha", entries, template(), NOW))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("Força");
	}

	@Test
	void nomeDoPersonagemEObrigatorio() {
		assertThatThrownBy(() -> CharacterSheet.fill("sh_1", "camp_1", "user_1", "   ", List.of(), template(), NOW))
			.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> CharacterSheet.fill("sh_1", "camp_1", "user_1", "a".repeat(121), List.of(),
			template(), NOW)).isInstanceOf(ApiException.class);
	}

	@Test
	void oDerivadoSaiDoValorValidadoENaoEGravado() {
		CharacterSheet sheet = fillWith("forca", "18");

		assertThat(sheet.values()).containsEntry("forca", "18").doesNotContainKey("mod_forca");
		assertThat(new D20System().derivedAttributes()).extracting(DerivedAttribute::key)
			.containsExactly("mod_forca", "mod_destreza", "mod_constituicao", "mod_inteligencia", "mod_sabedoria",
				"mod_carisma");
		assertThat(sheet.derivedValues(new D20System().derivedAttributes())).containsEntry("mod_forca", "4");
	}

	@Test
	void derivadoDeAtributoDeOrigemFechadaNaoApareceParaOMestre() {
		SheetTemplate template = SheetTemplate.from("tpl_1", "camp_1", new D20System(),
			Map.of("forca", SheetVisibility.PRIVATE), NOW);
		CharacterSheet sheet = CharacterSheet.fill("sh_1", "camp_1", "user_1", "Aranha",
			withEntry(baseEntries(), "forca", "18", SheetVisibility.PRIVATE), template, NOW);

		assertThat(sheet.visibilityOf("forca")).isEqualTo(SheetVisibility.PRIVATE);
		assertThat(SheetAudience.GAME_MASTER.sees(sheet.visibilityOf("forca"))).isFalse();
	}

	private CharacterSheet fillWith(String key, String value) {
		return CharacterSheet.fill("sh_1", "camp_1", "user_1", "Aranha", withEntry(baseEntries(), key, value,
			SheetVisibility.GAME_MASTER), template(), NOW);
	}

	/** Os dez campos obrigatórios do D20 original, com valores válidos. */
	private static List<SheetEntry> baseEntries() {
		return List.of(
			new SheetEntry("forca", "12", SheetVisibility.GAME_MASTER),
			new SheetEntry("destreza", "12", SheetVisibility.GAME_MASTER),
			new SheetEntry("constituicao", "12", SheetVisibility.GAME_MASTER),
			new SheetEntry("inteligencia", "12", SheetVisibility.GAME_MASTER),
			new SheetEntry("sabedoria", "12", SheetVisibility.GAME_MASTER),
			new SheetEntry("carisma", "12", SheetVisibility.GAME_MASTER),
			new SheetEntry("nivel", "1", SheetVisibility.GAME_MASTER),
			new SheetEntry("pontos_de_vida", "8", SheetVisibility.GAME_MASTER),
			new SheetEntry("defesa", "14", SheetVisibility.GAME_MASTER),
			new SheetEntry("origem", "Acadêmico", SheetVisibility.GAME_MASTER));
	}

	/** A mesma ficha, com um campo trocado por outro valor e outra visibilidade. */
	private static List<SheetEntry> withEntry(List<SheetEntry> base, String key, String value,
			SheetVisibility visibility) {
		List<SheetEntry> entries = new ArrayList<>(base);
		entries.removeIf(entry -> entry.key().equals(key));
		entries.add(new SheetEntry(key, value, visibility));
		return entries;
	}
}
