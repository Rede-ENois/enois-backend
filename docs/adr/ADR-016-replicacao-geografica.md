# ADR-016 — Replicação geográfica: a varredura não acusa região evacuada, e cobertura se mede por região

**Status:** aceita (Fase 6)
**Data:** 2026-11-13

## Contexto

O item "replicação geográfica" da fase 6 pede "só o que pode replicar; divergência detectada". O
que pode replicar e a detecção de divergência já existiam (V279–V283, 05 §7): cada nó confirma em
`network_content_snapshots` a versão e o hash do que guardou, o feed de mudanças (`seq`) orienta o
revalidar, e a varredura periódica confere snapshot contra origem, acusando `CONTENT_STALE`,
`CONTENT_GONE` e `CONTENT_UNREADABLE`.

O que faltava era a palavra do próprio nome: **geográfica**. Toda a medida era por nó, e a pergunta
da contingência (`06-contingencia.md` §3) é por região. Dois defeitos concretos, provados em código:

1. **A varredura acusava nó de região evacuada.** A fila (`dueForCheck`) filtrava só
   `n.status = 'ACTIVE'`. O nó da região declarada fora de operação (V289, ADR-015) continua
   `ACTIVE` — a região é que saiu —, então a varredura o conferia e gravava `CONTENT_STALE`. O nó
   não divergiu: desde a declaração o Control Plane parou de lhe servir (despacho filtra,
   `acceptTasks=false`, o `sweep` do cache o pula). Num incidente regional, o painel encheria de
   `SYNC_DIVERGENCE` acusando nós saudáveis por uma decisão do próprio Control Plane.

2. **Nenhuma medida respondia "a rede sobrevive à queda de uma região?".** O dashboard conta nós
   por região (`byRegion`), nunca regiões **com cópia**. Doze nós de borda no mesmo rack são doze
   cópias e uma região; a contagem por nó chama isso de redundância total.

## Decisão

**1. A fila da reconciliação exclui as regiões fora de operação.**
`dueForCheck` recebe as regiões evacuadas (`NetworkRegionRepository.outOfService()`) e as aplica
como guarda SQL, comparadas **normalizadas** (`NetworkRegions.normalize`: trim + lower-case) — o
instalador não normaliza antes de gravar a região do nó, e a declaração de `"sudeste"` tem de
alcançar o nó de `" Sudeste "`. A guarda é a **declaração vigente**, não uma marca no snapshot:
quando a região volta (`DELETE /admin/network/regions/{region}`), o nó volta à fila sozinho, porque
a fila ordena por "nunca conferido, depois o mais antigo" e o alcança na rodada seguinte.

Alternativa rejeitada: marcar os snapshots da região ao declarar (limpar ao voltar). Criaria estado
derivado da declaração em outra tabela, com duas escritas para manter em sincronia — o mesmo
vencimento da guarda direta, sem o custo.

Alternativa rejeitada: deixar acusar e "explicar no painel". O alerta é auditado
(`SYNC_DIVERGENCE`) na transição; produzir auditoria falsa em pleno incidente e confiar em o admin
ler a lista de regiões evacuadas para descontá-la mentalmente é o painel acusando e o humano
absolvendo, em vez do painel não acusar.

**2. Cobertura por região: `GET /admin/network/content/coverage`.**
`ContentVersionRepository.coverage` devolve, por tipo da allowlist, um `RegionCoverage(kind,
regionsWithCopy, totalRegions, copies)`, com três escolhas de contorno:

- **"Operável" é `ACTIVE` + heartbeat recente + região não evacuada**, com o mesmo corte de 180 s
  do scheduler e do painel (`STALE_SECONDS`). "Vivo" no painel é o mesmo vivo que recebe tarefa —
  uma segunda definição criaria dois incompetentes com o mesmo nome. A cópia de nó parado existe no
  banco e não atende ninguém; contá-la prometeria contingência que a rede não tem.
- **Nó sem região conta como uma região própria** (`coalesce(lower(trim(region)), '')`). Ele tem a
  cópia e atende; ignorá-lo subnotificaria a cobertura de uma rede que não usa o campo. (E região
  vazia nunca é evacuada, ADR-015 — os dois desenhos concordam.)
- **O denominador usa o mesmo corte do numerador**: região só de nós mortos não é região atendida
  nem região disponível, e "2 de 3" com uma região de mortos misturaria duas perguntas.

É **leitura de painel, não decisão de despacho**: nada entra em `choose()` nem na conta de
capacidade. O tipo sem cópia aparece com zero, em vez de sumir — omitir a linha esconderia o buraco
que o painel existe para mostrar.

## Consequências

- Nenhuma migração: a guarda é parâmetro da consulta existente, e a cobertura é consulta nova sobre
  tabelas existentes (`network_content_snapshots` × `network_nodes`). V290 continua livre.
- `ContentReconcileService` ganha `NetworkRegionRepository` no construtor; `AdminNetworkContentController`
  ganha `NetworkReplicationCoverageService`. Aditivo interno; nenhum teste constrói nenhum dos dois à mão.
- A rede evacuada reporta cobertura menor **e** universo menor na mesma resposta: `outOfServiceRegions`
  (dashboard) continua sendo a explicação do porquê.
- Fica registrado o limite: `copies` conta confirmações no Control Plane, não verificação de que o
  nó serve o corpo agora — isso é exatamente o que a reconciliação (§7.4.1 já com a guarda) faz em
  rodadas.

## Provas

- Q0 da guarda: neutralizando `normalizeRegions` (devolve lista vazia sempre),
  `NetworkReplicationGeographicTests` falha em **4** — `varreduraNaoAcusaNoDeRegiaoEvacuada`,
  `guardaRegionalComparaRegiaoNormalizada`, `regiaoDeVoltaDevolveONoAFila` e
  `regiaoEvacuadaSaiDaCobertura`; passa nos 10 com a guarda.
- Q0 da medida: trocando `count(distinct região)` por `count(distinct n.id)`,
  `coberturaContaRegioesDistintasENaoNos` falha (4 nós, 2 regiões → a medida via por nó diz 4);
  restaurada, passa.
- Q0 do heartbeat: neutralizando o corte de `last_seen_at`, `noSemHeartbeatRecenteNaoCobreRegiao`
  falha; restaurado, passa.
- Sem migração nova: nada a aplicar duas vezes nesta rodada.
- Suíte completa: 2683 testes, 0 falhas, 3 skipped (2673 do R103 + 10 novos).
