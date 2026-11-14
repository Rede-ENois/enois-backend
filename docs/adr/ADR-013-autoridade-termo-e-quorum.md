# ADR-013 — Autoridade do Control Plane: termo monotônico e quórum do eleitorado declarado

**Status:** aceita (Fase 6)
**Data:** 2026-11-13

## Contexto

O plano da fase 6 promete "Control Plane redundante: promoção controlada com term; sem quórum,
ninguém promove" (doc 06 §4, regras 1–4). A evidência de código até esta rodada: o Control Plane era
ponto único sem nada que o dissesse. `network_state` guardava o modo e o motivo de **uma** instância;
dois Control Planes subindo ao mesmo tempo no mesmo banco escreviam a mesma linha sem que nenhum dos
dois percebesse que havia outro. Não havia como responder "qual autoridade vale agora?" nem "desde
quando?" — e sem essa pergunta uma promoção é indistinguível de um segundo pod sobrescrevendo o
estado.

Quatro perguntas de desenho, cada uma com alternativa rejeitada:

1. **Onde o termo mora?** Tabela nova de autoridade seria uma segunda fonte de verdade ao lado da
   linha que já diz o modo, e as duas precisariam concordar em cada transação. O termo vai nas
   colunas novas de `network_state` (`term`, `authority_id`, `promoted_at`): é a mesma linha que o
   modo usa — "a autoridade" já morava ali, só não se identificava.

2. **Quem vota?** **Derivar de "quem está respondendo" rejeitado.** Numa partição de rede, a
   metade isolada considera que os únicos vivos são ela mesma: quórum sobre vivos dá 100% a
   qualquer fragmento e cada metade se promove. O eleitorado é o **declarado** — `network_authorities`,
   lista humana e auditada — e a maioria é contada sobre o conjunto **inteiro**, vivos ou não.

3. **Como a corrida é resolvida?** **Checar antes de escrever rejeitado.** Um `select` seguido de
   `update` tem a janela entre leitura e escrita exatamente onde as duas promoções se encontram. A
   gravação é compare-and-set (`update ... where term = <lido>`): quem perder atualiza zero linhas e
   recebe `NETWORK_AUTHORITY_PROMOTION_LOST`. Dois controladores com o mesmo termo não existem por
   construção, não por vigilância.

4. **A vivacidade vem de onde?** De uma noção nova de "está no ar" seria uma segunda verdade ao lado
   da que o painel já usa. Vem de `network_instance_readers.last_seen_at` (V293), a mesma tabela que
   responde quantas instâncias estão lendo de verdade; a janela é
   `rede.network.authority.liveness-seconds` (padrão 30 s).

## Decisão

O termo é um contador monotônico na linha global de `network_state`, com dono (`authority_id`) e
instante (`promoted_at`). A promoção (`POST /admin/network/authority/promote`, sessão de admin,
motivo obrigatório) só acontece quando a instância pedinte está **declarada** e **viva**, e a
maioria do eleitorado inteiro está viva (`vivos * 2 > declarados`); então o termo sobe por CAS.

Cinco propriedades:

1. **Termo 0 é a autoridade original.** Antes da primeira promoção não há dono nem `promoted_at`; a
   V303 não inventa número para bancos existentes.

2. **A linha global é invariante do deploy.** Até aqui ela nascia preguiçosamente na primeira
   transição de modo (`NetworkStatePersistenceAdapter`). Um banco que nunca mudou de modo teria o
   CAS da primeira promoção atualizando zero linhas — a promoção falharia sempre, com a mensagem de
   corrida perdida, que é o diagnóstico errado. A V303 faz seed da linha (`term = 0`) e um teste
   mantém o mesmo invariante. Um teste separado cobre a ausência deliberada, para o serviço não
   quebrar se alguém apagar a linha.

3. **Empate não é maioria.** Com 2 declaradas, 1 viva não promove. Fail secure: melhor ficar sem
   controlador novo do que ter dois — a autoridade de pé continua valendo.

4. **O termo entra na assinatura, não só no payload.** `formatVersion` sobe para 2 e o termo fecha a
   linha canônica (por último, porque o formato 1 não o tinha). O executor reconstrói a linha com o
   termo e recusa config de termo menor que o último aceito, gravado em `$NODE_HOME/term` — o mesmo
   lugar do pino, e vai no `--backup`/`--restore` junto com ele. Recusar termo que regride conferindo
   contra o pino é diferente de confiar no transporte: quem forja a resposta não forja a assinatura.

5. **Revogar tira do eleitorado na hora.** A revogada perde a condição de viva (o `join` de
   `aliveSince` passa por `network_authorities`) e não pode mais receber termo.

## Consequências

- `network_state` ganha três colunas e `network_authorities` nasce na V303. Nenhum nó externo escreve
  em nenhuma das duas: a promoção é operação administrativa auditada
  (`NETWORK_AUTHORITY_DECLARED`/`REVOKED`/`PROMOTED`).
- A config assinada muda de formato (`formatVersion` 2). Runtime antigo (formato 1) recusa a config
  nova pelas regras que já tinha — não é fail-open: ele verifica a assinatura da linha que sabe montar
  e a assinatura não confere, então para. A troca de runtime é passo de instalação documentado, não
  efeito colateral.
- Promoção não toca o modo: `EMERGENCY` declarado continua valendo depois da troca de autoridade,
  porque `since` é do modo e `promoted_at` é da autoridade.
- A regra 5 do doc 06 (nó que vê dois controladores entra em `QUARANTINED`) ficou **fechada na
  R102**: ver ADR-014, que usa o termo aceito reportado no heartbeat como canal de detecção.

## Provas

- Q0 do runtime: `config de termo menor é recusada como autoridade deposta` falha quando a checagem
  `term_regressao` é removida (o ciclo segue e chega a `/network/tasks/next`) e passa com ela;
  `termo recusado não rebaixa o último aceito` prova que o estado local não anda para trás.
  `config de termo maior é aceita` prova que a regra não bloqueia promoção legítima.
- Q0 do banco: `promoteSoGravaComOTermoEsperadoEPerdedoraAtualizaZeroLinhas` — duas promoções leem o
  mesmo termo, a primeira grava, a segunda recebe `false` e o estado fica com a vencedora. Também
  conferido no MariaDB 11.4 real com `row_count()`: 1 e 0.
- Fail secure: `semEleitoradoNinguemPromove`, `instanciaDeclaradaMasSemSinalNaoPromove`,
  `semQuorumNinguemPromove`, `autoridadeRevogadaSaiDoEleitoradoENaoPromove`, cada um provando que o
  termo **não** andou.
- Assinatura: `termoEntraNaLinhaAssinadaENaoSoNoPayload` (mesmo carimbo e mesmas permissões, só o
  termo muda, e a assinatura muda) e `configDoNoCarregaOTermoVigenteEDeclaraOFormatoNovo`.
- Migração V303 aplicada duas vezes em MariaDB 11.4.13 real em três bancos: linha de modo antiga
  preservada (`EMERGENCY` com `term = 0`), nó e tarefas antigos intactos, e o seed criando a global
  onde não havia.