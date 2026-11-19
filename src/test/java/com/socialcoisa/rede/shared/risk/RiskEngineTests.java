package com.socialcoisa.rede.shared.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.shared.security.BehavioralTimingGuard;
import com.socialcoisa.rede.shared.security.ContentDuplicationGuard;

/**
 * O motor central de risco (plano mestre de segurança humana, item 7).
 *
 * <p>O motor começa em <b>modo de observação</b>: ele avalia e registra, e não barra ninguém. Estes
 * testes travam esse comportamento — a avaliação só acontece quando há sinal de verdade, o
 * registro carrega o nível, os sinais e a versão da regra (para reconstruir a decisão depois), e
 * uma escrita normal não suja o histórico.
 */
@SpringBootTest(properties = {
	// O perfil de teste desliga o guard de duplicidade (para as outras suites nao brigarem com
	// ele); aqui ele precisa estar ligado, porque o sinal de conteudo repetido e um dos que o
	// motor combina.
	"rede.rate.duplicate-window-minutes=60"
})
class RiskEngineTests {

	@Autowired
	private RiskEngine riskEngine;

	@Autowired
	private BehavioralTimingGuard timingGuard;

	@Autowired
	private ContentDuplicationGuard duplicationGuard;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void escritaComSinalDeCadenciaRegistraAvaliacao() {
		String userId = "risco-cadencia";
		// Dez ações em cadência quase idêntica: é assim que a automação se denuncia.
		for (int i = 0; i < 12; i++) {
			timingGuard.record(userId);
		}

		RiskAssessment avaliacao = riskEngine.assess(userId, "publish", "conteudo qualquer");

		assertThat(avaliacao).isNotNull();
		assertThat(avaliacao.signals()).contains(RiskSignal.ROBOTIC_CADENCE);
		assertThat(avaliacao.level()).isNotEqualTo(RiskLevel.LOW);
		assertThat(avaliacao.ruleVersion()).isNotBlank();
		assertThat(avaliacao.action()).isEqualTo("publish");

		// E o registro está na base, com o nível e os sinais (reconstruível depois).
		List<Map<String, Object>> gravados = jdbc.queryForList(
			"select level, score, signals, rule_version, decision, action_type from risk_assessments where user_id = ?",
			userId);
		assertThat(gravados).hasSize(1);
		assertThat(gravados.get(0).get("level")).isEqualTo(avaliacao.level().name());
		assertThat(gravados.get(0).get("signals").toString()).contains(RiskSignal.ROBOTIC_CADENCE.name());
		assertThat(gravados.get(0).get("rule_version")).isEqualTo(avaliacao.ruleVersion());
		assertThat(gravados.get(0).get("action_type")).isEqualTo("publish");
	}

	@Test
	void escritaSemSinalNaoGeraAvaliacaoNemRegistro() {
		String userId = "risco-sem-sinal";

		RiskAssessment avaliacao = riskEngine.assess(userId, "publish", "conteudo tranquilo");

		assertThat(avaliacao).isNull();
		assertThat(jdbc.queryForList("select id from risk_assessments where user_id = ?", userId)).isEmpty();
	}

	@Test
	void conteudoRepetidoEntraNoRegistroComoSinal() {
		String userId = "risco-duplicado";
		// O guard de duplicidade guarda o texto publicado; na repetição, o sinal aparece.
		duplicationGuard.require(userId, "texto para repetir");

		RiskAssessment avaliacao = riskEngine.assess(userId, "publish", "texto para repetir");

		assertThat(avaliacao).isNotNull();
		assertThat(avaliacao.signals()).contains(RiskSignal.DUPLICATE_CONTENT);
	}

	@Test
	void nivelGraveViraOcorrenciaParaAModeracao() {
		String userId = "risco-escalada";
		// Cadencia de robo (3 pontos) + conteudo repetido (2) = 5, o limiar de HIGH.
		for (int i = 0; i < 12; i++) {
			timingGuard.record(userId);
		}
		duplicationGuard.require(userId, "o mesmo texto do robo");

		RiskAssessment avaliacao = riskEngine.assess(userId, "publish", "o mesmo texto do robo");

		assertThat(avaliacao).isNotNull();
		assertThat(avaliacao.level()).isEqualTo(RiskLevel.HIGH);
		// A moderacao recebeu a ocorrencia: denuncia automatica, com o nivel e os sinais.
		// O guarda de cadencia tambem denuncia (descricao "[auto]"); a do MOTOR tem o prefixo
		// "[risco automatico]" e o nivel — e essa que tem de existir.
		List<Map<String, Object>> doMotor = jdbc.queryForList(
			"select description from reports where target_id = ? and description like '%risco automatico%'",
			userId);
		assertThat(doMotor).hasSize(1);
		assertThat(doMotor.get(0).get("description").toString()).contains("HIGH").contains("ROBOTIC_CADENCE");
	}

	@Test
	void cadenciaSozinhaTambemViraOcorrenciaComONivel() {
		String userId = "risco-medio";
		// So a cadencia (3 pontos): MEDIUM. O guarda nao denuncia mais sozinho — quem entrega a
		// ocorrencia (com o nivel e os sinais) e o motor; e por isso o comportamento antigo
		// (cadencia detectada -> moderacao avisada) continua de pe.
		for (int i = 0; i < 12; i++) {
			timingGuard.record(userId);
		}

		RiskAssessment avaliacao = riskEngine.assess(userId, "publish", "conteudo");

		assertThat(avaliacao).isNotNull();
		assertThat(avaliacao.level()).isEqualTo(RiskLevel.MEDIUM);
		List<Map<String, Object>> doMotor = jdbc.queryForList(
			"select description from reports where target_id = ? and description like '%risco automatico%'",
			userId);
		assertThat(doMotor).hasSize(1);
		assertThat(doMotor.get(0).get("description").toString()).contains("MEDIUM");
	}

	@Test
	void contextoDiferenteNaoGeraSinalDeDuplicacao() {
		String userId = "risco-contextual";
		duplicationGuard.require(userId, "Parabens!", "comment", "post-a");
		assertThat(riskEngine.assess(userId, "comment", "Parabens!", "comment", "post-b")).isNull();
		assertThat(jdbc.queryForList("select id from risk_assessments where user_id = ?", userId)).isEmpty();
		RiskAssessment repetida = riskEngine.assess(userId, "comment", "Parabens!", "comment", "post-a");
		assertThat(repetida).isNotNull();
		assertThat(repetida.signals()).containsExactly(RiskSignal.DUPLICATE_CONTENT);
		assertThat(repetida.ruleVersion()).isEqualTo("v2");
	}

	@Test
	void modoDeObservacaoNaoBarraNinguem() {
		String userId = "risco-observacao";
		for (int i = 0; i < 12; i++) {
			timingGuard.record(userId);
		}

		RiskAssessment avaliacao = riskEngine.assess(userId, "publish", "conteudo");

		assertThat(avaliacao).isNotNull();
		// Com o enforcement desligado (o padrão), o nível mais alto ainda não restringe ninguém.
		assertThat(avaliacao.decision()).isNotEqualTo(RiskDecision.RESTRICT);
	}
}
