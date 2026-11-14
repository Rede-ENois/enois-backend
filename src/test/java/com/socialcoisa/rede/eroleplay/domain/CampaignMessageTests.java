package com.socialcoisa.rede.eroleplay.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * As regras da mensagem de chat na borda do domínio: criação válida, recusas de fronteira,
 * fixação sem reescrita e o recorte de leitura por leitor.
 */
class CampaignMessageTests {

	private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
	private static final String CAMPAIGN = "camp-1";
	private static final String AUTHOR = "autor";

	private CampaignMessage message() {
		return CampaignMessage.create(CAMPAIGN, ChatChannel.IN_CHARACTER, AUTHOR, null, "o elfo abre a porta", NOW);
	}

	@Test
	void nasceComCanalAutorEConteudo() {
		CampaignMessage message = message();
		assertThat(message.channel()).isEqualTo(ChatChannel.IN_CHARACTER);
		assertThat(message.authorId()).isEqualTo(AUTHOR);
		assertThat(message.content()).isEqualTo("o elfo abre a porta");
		assertThat(message.isPrivate()).isFalse();
		assertThat(message.isPinned()).isFalse();
	}

	@Test
	void conteEOOCQuandoPedido() {
		assertThat(CampaignMessage.create(CAMPAIGN, ChatChannel.OUT_OF_CHARACTER, AUTHOR, null, "pausa", NOW)
			.channel()).isEqualTo(ChatChannel.OUT_OF_CHARACTER);
	}

	@Test
	void recusaCanalNulo() {
		assertThatThrownBy(() -> CampaignMessage.create(CAMPAIGN, null, AUTHOR, null, "texto", NOW))
			.isInstanceOf(ApiException.class)
			.satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("CHAT_CHANNEL_REQUIRED"));
	}

	@Test
	void recusaVaziaEBranca() {
		assertThatThrownBy(() -> CampaignMessage.create(CAMPAIGN, ChatChannel.IN_CHARACTER, AUTHOR, null, "   ",
				NOW))
			.isInstanceOf(ApiException.class)
			.satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("CHAT_MESSAGE_EMPTY"));
	}

	@Test
	void recusaAcimaDoTeto() {
		String longa = IntStream.range(0, CampaignMessage.MAX_LENGTH + 1).mapToObj(i -> "a")
			.collect(Collectors.joining());
		assertThatThrownBy(() -> CampaignMessage.create(CAMPAIGN, ChatChannel.OUT_OF_CHARACTER, AUTHOR, null, longa,
				NOW))
			.isInstanceOf(ApiException.class)
			.satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("CHAT_MESSAGE_TOO_LONG"));
	}

	@Test
	void aceitaNoTetoExatoEApara() {
		String noTeto = IntStream.range(0, CampaignMessage.MAX_LENGTH - 2).mapToObj(i -> "a")
			.collect(Collectors.joining()) + "  ";
		CampaignMessage message = CampaignMessage.create(CAMPAIGN, ChatChannel.IN_CHARACTER, AUTHOR, null, noTeto,
			NOW);
		assertThat(message.content().length()).isLessThanOrEqualTo(CampaignMessage.MAX_LENGTH);
	}

	@Test
	void apartePrivadoNasceComDestinatario() {
		CampaignMessage aparte = CampaignMessage.create(CAMPAIGN, ChatChannel.OUT_OF_CHARACTER, AUTHOR, "destino",
			"meu personagem sabe da armadilha", NOW);
		assertThat(aparte.isPrivate()).isTrue();
		assertThat(aparte.recipientId()).isEqualTo("destino");
	}

	@Test
	void recusaAparteParaSiMesmo() {
		assertThatThrownBy(() -> CampaignMessage.create(CAMPAIGN, ChatChannel.OUT_OF_CHARACTER, AUTHOR, AUTHOR,
				"eco", NOW))
			.isInstanceOf(ApiException.class)
			.satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("CHAT_RECIPIENT_SELF"));
	}

	@Test
	void publicaEVisivelParaQualquerLeitor() {
		assertThat(message().visibleTo("qualquer-um", false)).isTrue();
	}

	@Test
	void aparteEVisivelSoParaAsPontasEOmestre() {
		CampaignMessage aparte = CampaignMessage.create(CAMPAIGN, ChatChannel.OUT_OF_CHARACTER, AUTHOR, "destino",
			"armadilha", NOW);
		assertThat(aparte.visibleTo(AUTHOR, false)).isTrue();
		assertThat(aparte.visibleTo("destino", false)).isTrue();
		assertThat(aparte.visibleTo("mestre", true)).isTrue();
		assertThat(aparte.visibleTo("terceiro", false)).isFalse();
		assertThat(aparte.visibleTo("terceiro", true)).isTrue();
	}

	@Test
	void fixarPreservaConteudoEMarcaQuem() {
		CampaignMessage fixada = message().pin("mestre", NOW.plusSeconds(30));
		assertThat(fixada.isPinned()).isTrue();
		assertThat(fixada.pinnedBy()).isEqualTo("mestre");
		assertThat(fixada.pinnedAt()).isEqualTo(NOW.plusSeconds(30));
		assertThat(fixada.content()).isEqualTo("o elfo abre a porta");
		assertThat(fixada.authorId()).isEqualTo(AUTHOR);
		assertThat(fixada.createdAt()).isEqualTo(NOW);
	}

	@Test
	void fixarDuasVezesEOMesmoEstado() {
		CampaignMessage fixada = message().pin("mestre", NOW.plusSeconds(30));
		assertThat(fixada.pin("outro-mestre", NOW.plusSeconds(60))).isSameAs(fixada);
	}

	@Test
	void desfixarRestauraOEstadoOriginal() {
		CampaignMessage fixada = message().pin("mestre", NOW.plusSeconds(30));
		CampaignMessage desfixada = fixada.unpin();
		assertThat(desfixada.isPinned()).isFalse();
		assertThat(desfixada.pinnedBy()).isNull();
		assertThat(desfixada.content()).isEqualTo("o elfo abre a porta");
		assertThat(desfixada.unpin()).isSameAs(desfixada);
	}

	@Test
	void reconstituicaoPreservaTudo() {
		CampaignMessage fixada = message().pin("mestre", NOW.plusSeconds(30));
		CampaignMessage restored = CampaignMessage.restore(fixada.id(), CAMPAIGN, ChatChannel.IN_CHARACTER, AUTHOR,
			null, "o elfo abre a porta", NOW, "mestre", NOW.plusSeconds(30));
		assertThat(restored.isPinned()).isTrue();
		assertThat(restored.pinnedBy()).isEqualTo("mestre");
		assertThat(restored.visibleTo("qualquer-um", false)).isTrue();
		assertThat(restored.id()).isEqualTo(fixada.id());
		assertThat(restored.content()).isEqualTo(fixada.content());
		assertThat(restored.authorId()).isEqualTo(fixada.authorId());
		assertThat(restored.createdAt()).isEqualTo(fixada.createdAt());
	}

	@Test
	void canalTraduzWireNosDoisSentidos() {
		assertThat(ChatChannel.of("IC")).isEqualTo(ChatChannel.IN_CHARACTER);
		assertThat(ChatChannel.of("ooc")).isEqualTo(ChatChannel.OUT_OF_CHARACTER);
		assertThat(ChatChannel.IN_CHARACTER.wire()).isEqualTo("IC");
		assertThat(ChatChannel.OUT_OF_CHARACTER.wire()).isEqualTo("OOC");
		assertThatThrownBy(() -> ChatChannel.of("WHISPER")).isInstanceOf(IllegalArgumentException.class);
	}
}
