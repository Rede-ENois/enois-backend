# ADR-018 — Validade da config assinada: o nó deixa de valer para sempre

**Status:** aceita (Fase 6)
**Data:** 2026-11-13

## Contexto

A doc `06-contingencia.md` §6 promete, para "Control Plane fora":

> | fora por > TTL | nós param de aceitar tarefa nova e drenam as em andamento |

Esse TTL não existia. A config assinada (formato 2) carregava `issuedAt` — o instante em que a
permissão passou a valer — e `term`, mas nenhum prazo. `allowed_tasks()` no runtime devolvia sucesso
com a última config conhecida **indefinidamente**: não havia como o nó distinguir "o Control Plane
está inalcançável há 5 segundos" de "o Control Plane não existe mais há seis horas". A permissão
assinada era, na prática, eterna — o oposto do fail secure que a mesma seção exige logo abaixo
("Nó **nunca** inventa permissão quando o Control Plane está fora").

O defeito era duplo e nos dois sentidos:

1. **Permissão infinita.** Revogar um nó exige que ele fale com o Control Plane. Um nó que nunca
   fala continua executando para sempre com a última permissão que recebeu.
2. **Não havia cache — havia queda.** `allowed_tasks()` retornava `1` em qualquer falha de
   transporte. Um blip de cinco segundos num nó perfeitamente saudável o fazia drenar e, na volta,
   ele pedia config de novo. O comportamento certo (drenar) acontecia pelo motivo errado e no
   momento errado.

## Decisão

1. **Formato 3: `ttlSeconds` entra na linha canônica, imediatamente antes de `term`.** A linha passa
   a ser
   `formatVersion|configVersion|nodeId|profiles|cpuCores|memoryMb|diskMb|bandwidthMbps|allowedTasks|issuedAt|ttlSeconds|term`.
   `term` continua fechando a linha de propósito: um runtime de formato 2 recusa um documento de
   formato 3 em vez de ler o TTL no lugar do termo. O TTL vai **assinado** porque, no payload, um
   intermediário trocaria `300` por `86400` e a config continuaria "verificada".

2. **O TTL é relativo, não um prazo absoluto.** Um `expiresAt` absoluto faria o documento mudar com
   o relógio e quebraria a invariante já fixada em ADR-011 ("duas leituras sem mudança devolvem o
   mesmo documento e a mesma assinatura"). Relativo, o mesmo documento assinado serve a todos os nós,
   e cada um mede a própria frescura a partir do instante em que o verificou. O valor vem de
   `rede.network.config.ttl-seconds` (padrão 300 s); zero ou negativo cai no piso de 300 s com
   aviso, porque TTL zero venceria a config no instante em que ela nasce e o nó drenaria sem pista
   de por quê.

3. **Duas falhas, dois comportamentos.** O runtime separa o que antes era uma coisa só:
   - **transport failure** (curl sem resposta: exit 7, 28, 56…) → usa a última config **verificada**
     se `agora - verificado_em < ttlSeconds`. Dentro do prazo é blip, o nó segue; fora do prazo é
     Control Plane fora, o nó drena e não chama `/network/tasks/next`.
   - **HTTP refusal** (exit 22 do `curl --fail`) → **nunca** cai no cache. Recusa é o Control Plane
     vivo dizendo que esta config não vale mais (revogação, chave inválida, nó desconhecido).
     Ressuscitar permissão recém-negada com um arquivo local seria o oposto de fail secure.

4. **O cache é re-verificado contra o pino a cada uso.** `$NODE_HOME/config.json` guarda o documento;
   `$NODE_HOME/config-verified-at` guarda o instante da verificação. Antes de servir o cache, o
   runtime refaz `config_valida()` — assinatura contra o pino e termo não regredido. O cache vive no
   mesmo volume da identidade do nó; um arquivo trocado ali não pode virar permissão só porque o
   Control Plane está inalcançável para contradizê-lo. A escrita usa `.tmp` + `mv -f` para um
   processo interrompido não deixar meio documento.

5. **O cache fica fora do `install.sh --backup`.** A validade é medida pelo relógio da máquina.
   Restaurado noutra máquina, o cache chegaria com o instante de verificação de outra máquina —
   mentindo sobre o próprio frescor e esvaziando o TTL. Melhor voltar sem cache (o nó espera a
   primeira config verificada) do que com uma frescura emprestada.

## Alternativa rejeitada

**Perguntar ao Control Plane se o nó ainda vale.** É exatamente o que não se pode fazer quando o
Control Plane está fora — a pergunta não tem quem responda. O TTL é a resposta que o nó já carrega
consigo, assinada, e que expira sozinha quando ninguém a renova.

**Prazo absoluto (`expiresAt`).** Rejeitada por contrato: faria a assinatura mudar a cada segundo,
quebrando a leitura determinística de ADR-011 e a comparação de config entre leituras.

**Um único comportamento para queda e recusa.** Rejeitada por segurança: cobre o caso de rede com o
caso de revogação, e o cache acaba autorizando justamente o nó que o Control Plane acabou de negar.

## Consequências

- Um nó isolado continua trabalhando por até `ttlSeconds` após a última config verificada — janela
  que o operador escolhe. Depois disso ele drena e fica ocioso, e volta sozinho na próxima config
  verificada, sem intervenção.
- Revogação de um nó inalcançável deixa de ser imediata: vale no máximo até o TTL vencer. É o custo
  declarado de não depender de contato; o teto é conhecido e configurável.
- O nó passa a ter estado local derivado (`config.json`, `config-verified-at`) que não existia.
  `node-doctor.sh` ganhou a checagem 5b: WARN quando o cache está vencido (com a idade e o TTL) ou
  ilegível, para o operador ver a causa antes de o nó parar de pedir trabalho.
- A passagem 2 → 3 é conjunta, nos dois sentidos: runtime antigo recusa config de formato 3 (não
  reconstrói a linha com o TTL) e runtime novo recusa config de formato 2 (não encontra
  `ttlSeconds` e desiste antes de verificar). O `formatVersion` existe justamente para que essa
  incompatibilidade seja uma **recusa explícita**, com a mensagem certa, e não uma verificação que
  falha por motivo obscuro. Runtime novo é pré-requisito do Control Plane novo — mesma regra da
  passagem 1 → 2.

## Provas

**Q0 em quatro mutações independentes**, cada uma aplicada sozinha sobre `node-runtime.sh` e
revertida em seguida; cada uma derruba **exatamente uma** asserção nova:

| mutação | asserção que falha |
|---|---|
| tirar o fallback para o cache | "dentro do TTL o nó segue com a última config verificada" |
| `if false` na checagem de idade | "passado o TTL o nó drena e diz por quê" |
| `if false` no ramo `rc == 22` | "recusa HTTP vira drenagem, não cache" |
| tirar `config_valida` do caminho do cache | "config guardada que não confere mais é recusada" |

**Cenários no harness** (`scripts/tests/node-executor-test.sh`, 26–31), 133 asserções no total
(119 antes):

- **26** — CP fora dentro do TTL: ciclo 1 com CP no ar guarda o cache; ciclo 2 com o endpoint de
  config falhando (exit 7) e heartbeat no ar → o nó segue com a config guardada e conclui a tarefa.
- **27** — CP fora além do TTL: `config-verified-at` recuado 400 s → "config vencida (TTL de 300s) —
  drenando" e **nenhuma** chamada a `/network/tasks/next`.
- **28** — TTL 3600 aceita um intervalo de 400 s: prova que o número é lido do documento assinado,
  não constante no script.
- **29** — recusa HTTP (exit 22) não é queda de transporte: drena mesmo com cache fresco, sem poll.
- **30** — cache adulterado (`allowedTasks` trocado por `MEDIA_TRANSCODE` sem reassinar) é recusado
  contra o pino: "config guardada não confere mais com o pino — drenando", sem poll.
- **31** — sem cache nenhum e falha de transporte → drena.

**Java, alvo:** 50 testes, 0 falhas — `NetworkConfigServiceTests` 9 (dois novos: o TTL entra na
linha assinada e muda a assinatura; TTL inválido cai no piso), `NetworkConfigSignerTests` 5,
`NetworkAuthorityTests` 11, `NetworkNodesTests` 25 (novo `$.ttlSeconds` na resposta).

**Suíte completa:** 2688 testes, 0 falhas, 3 skipped (2686 anteriores + 2 novos — os dois testes novos de `NetworkConfigServiceTests`; nos demais arquivos só assinaturas e expectativas mudaram).