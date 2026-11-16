# Rede Énois — observabilidade por nó

Documento do que **existe** no código. Complementa
[`03-control-plane-e-modo-de-rede.md`](03-control-plane-e-modo-de-rede.md) e
[`13-painel-de-monitoracao.md`](13-painel-de-monitoracao.md): aqueles descrevem o painel do
**Control Plane**; este descreve o painel **de cada nó**, dentro da página do nó.

## 1. O que o painel mostra

`GET /admin/network/nodes/{id}/observability` (admin) devolve quatro blocos, os mesmos quadros do
topo do painel de monitoração, calculados sobre a janela do nó:

| bloco | conteúdo |
|---|---|
| `series` | até **60** pontos, um por minuto, do mais antigo ao mais novo: `cpuPercent`, `memoryMb`, `diskFreeMb`, `runningTasks`, `mode` |
| `summary` | `cpuAvgPercent`, `cpuMaxPercent`, `memoryMbLatest`, `diskFreeMbLatest`, `runningTasksLatest`, `sampleCount` |
| `console` | até **200** linhas, da mais nova à mais antiga: `id`, `at`, `level`, `message` |
| `consoleLevels` | contagem por nível (`INFO`, `WARN`, `ERROR`) na janela |

`generatedAt` carimba o instante do quadro. Um ponto sem medida tem o campo **nulo**, não zero: o
gráfico desenha lacuna onde o nó não mediu — o mesmo princípio de `observedDiskFreeMb`.

## 2. De onde vem o dado — e por que não é um pull direto

O runtime do nó **não abre porta** (brief §17: o nó nunca é endereço de entrada; o Control Plane
fala com ele pelo caminho de saída que já existe). "Ler direto do nó" exigiria o backend abrir
conexão para dentro da máquina de um participante — exatamente o que o modelo pull impede.

Então o nó **empurra** a observação no heartbeat que ele já faz a cada ciclo:

```json
POST /network/nodes/heartbeat
{ "runtimeVersion": "…",
  "observed": { "cpuPercent": 12.5, "memoryMb": 2048, "runningTasks": 0, "diskFreeMb": 9000 },
  "logs": [ { "level": "INFO", "message": "tarefa tk_1 concluida", "at": "2026-11-12T12:00:00Z" } ] }
```

O efeito visível é o de um quadro ao vivo (atualizado a cada heartbeat, e a tela recarrega a rota);
o caminho é o que a arquitetura permite. `logs` é opcional: um runtime anterior a esta fase
continua funcionando e simplesmente não alimenta o console.

**O console do nó não é auditoria.** É uma janela curta, apagada por teto na escrita (abaixo). O
registro de auditoria (`audit_*`) tem outra promessa e não recebe estas linhas.

**Dois painéis, com papéis diferentes.** O painel local
([`09-instalacao.md`](09-instalacao.md) §3.2, `painel.py`) roda **na máquina**, lê os arquivos do nó
e vê o log completo — é do operador que tem acesso ao host. O painel descrito aqui é **remoto**,
pelo Control Plane, e vê a janela curta que o nó empurrou: é para o administrador que acompanha
muitos nós pela tela, sem entrar em cada máquina. Nenhum substitui o outro.

## 3. Teto e retenção

| tabela | conteúdo | teto por nó | como é aplicado |
|---|---|---|---|
| `network_node_samples` | um ponto por **minuto** | 60 | `trimSamples` depois de cada gravação |
| `network_node_logs` | linha do runtime | 200 | `trimLogs` depois de cada gravação |

O teto é aplicado **na escrita**, não por job: um nó que some não deixa lixo acumulando, e um que
volta encontra o histórico recente. Duas consequências que os testes fixam:

- **dois heartbeats no mesmo minuto trocam o ponto**, não abrem dois — a chave é
  `(node_id, sampled_at)` e a gravação é um upsert (`on duplicate key update`);
- apagar o nó (cascade) leva junto a janela dele.

A mensagem do console é normalizada no serviço: nível restrito a `DEBUG|INFO|WARN|ERROR` (o resto
vira `INFO`) e tamanho máximo de 500 caracteres — é uma linha de terminal, não um stack trace.

## 4. Do lado do nó

`deploy/node-runtime/node-runtime.sh` mantém um buffer curto e **em arquivo**
(`$NODE_HOME/pending-events`, modo `600`), porque o `cycle` roda num processo por volta e um
reinício não pode apagar a linha que ainda não subiu:

- `event <nível> <mensagem>` grava uma linha no buffer (até 20 pendentes);
- `notice_once` — que já avisa "aceito pela rede", "drenando", "recusada" — passa a alimentar o
  buffer **também**;
- o heartbeat anexa `"logs":[ … ]` ao corpo e **esvazia** o buffer só depois de o envio dar certo;
  se o heartbeat falhar, as linhas ficam para a próxima volta.

A linha de aceite nasce da **resposta** do heartbeat, então o primeiro heartbeat de um nó novo não
carrega linha nenhuma — o segundo é o que a leva. O harness fixa os dois lados.

## 5. Verificação

| verificação | resultado |
|---|---|
| `NetworkNodeObservabilityTests` | 5 — heartbeat alimenta série e console; dois no mesmo minuto = um ponto; teto de 200 na escrita; janela de um nó não vaza para outro; admin exigido e nó desconhecido é 404 |
| `node-executor-test` | **177** asserções (172 + 5 do painel): primeiro heartbeat sem linha, segundo com `"logs":[`, nível, mensagem e instante |
| `node-observability-migration-test` | **26** asserções em MariaDB 11.4 real, migrações 2× com dados |

Mutações conferidas: remover a gravação da amostra/linha no heartbeat derruba 4 dos 5 testes;
remover `trimLogs` derruba `oTetoDoConsoleValeNaEscrita…`; tirar `logs_part` do corpo do heartbeat
derruba `heartbeat carrega as linhas do painel`.

## 6. Defeitos encontrados no caminho

- **Buffer em memória não sobrevivia ao ciclo.** O loop roda um processo por volta, então a linha
  gravada no 1º ciclo sumia antes do 2º. Virou arquivo no `NODE_HOME` — e isso também é o correto
  em produção, onde um reinício não pode perder a linha pendente.
- **`event()` usava `cut`.** O runtime roda com PATH mínimo, e no harness sem `cut` a linha era
  descartada em silêncio (`2>/dev/null`, `|| return 0`). Agora normaliza com expansão de parâmetro
  do bash, sem dependência externa.
- **`network_node_samples` sem FK.** O harness de MariaDB real mostrou que apagar o nó deixava
  amostras órfãs: a FK com `on delete cascade` entrou na V325.
- **As asserções do harness procuravam `\"logs\"`** (com barra invertida literal) enquanto o stub
  registra o corpo sem escape — passariam ou falhariam por acidente. E a asserção do "primeiro
  ciclo" rodava depois de um segundo ciclo; foi movida para o instante certo.
- **Harness intermitente.** O `mariadb:11.4` reinicia o mysqld ao fim do init; "respondeu uma vez"
  não é "ficou no ar". A prontidão passou a exigir um create/drop de tabela após uma pausa.

## 6.5 A idade da série: `freshness`

O brief §8.7 exige que o painel **distinga ausência de dado, nó offline e métrica efetivamente
zerada**. O quadro devolvia a série e nada mais — uma janela com um ponto de duas horas atrás
desenhava igual a uma com pontos deste minuto, e "0% de CPU" era indistinguível de "não mediu".

`GET /admin/network/nodes/{id}/observability` ganhou `freshness`:

| campo | o que diz |
|---|---|
| `state` | `NO_SAMPLES` (nunca empurrou amostra), `FRESH` (último ponto dentro da janela de vivo), `STALE` (há ponto, mas mais velho que a janela) |
| `lastSampleAt` | o instante do **último** ponto medido |
| `ageSeconds` | idade desse ponto, em segundos |

Decisões que o contrato carrega:

- **`ageSeconds` é nulo sem amostra.** Zero diria "mediu agora", que é o oposto do caso vazio — a
  mesma regra que já vale para `observedDiskFreeMb` nulo.
- **`FRESH` é `age ≤ 180 s`**, a mesma janela de "vivo" do scheduler, do painel e da cobertura. Um
  ponto na fronteira saindo como `STALE` faria o painel acusar nó que o balanceamento ainda despacha.
- **`STALE` não esconde o número**: a tela mostra a medida **com a idade** (`lastSampleAt`), porque
  omitir o instante faria um dado de duas horas atrás parecer atual.
- **A idade é calculada no servidor**, do relógio dele. O runtime do nó não decide quão velho é o
  próprio dado — seria confiar numa máquina de terceiro para julgar a própria honestidade.

`NO_SAMPLES` é resposta para nó que existe no catálogo mas não empurrou nada; nó inexistente continua
`404`, e "não mediu" e "nó não existe" não podem ser o mesmo 404.

**Verificação:** `NodeObservabilityFreshnessTests` **5** (sem amostra é `NO_SAMPLES` com idade nula;
ponto recente é `FRESH` com a idade real; ponto velho é `STALE` preservando o instante; a fronteira de
180 s é inclusiva; a série é lida do mais novo, não do mais antigo) e `NetworkNodeObservabilityTests`
**7** (2 novos pela rota: `freshness` chega ao JSON com `lastSampleAt`/`ageSeconds`; nó sem amostra
responde `NO_SAMPLES` em vez de métricas zeradas). Mutações: idade 0 no caso vazio, qualquer série
como `FRESH`, último ponto tomado como o mais antigo, fronteira exclusiva — **4 de 4 derrubam**.

## 6.6 Histórico de conexão: a volta do nó

O brief §8.6 pede o **histórico de conexão** de cada réplica. Até aqui o heartbeat só tocava
`lastSeenAt`: o painel mostrava "conectado agora" e não contava que a máquina ficou fora.

Agora o heartbeat compara o último contato com a janela de "vivo" (180 s, a mesma do scheduler, do
painel e da série) **antes** de atualizar o `lastSeenAt`. Se o último contato é mais velho que a
janela, a volta vira evento auditado:

| ação | metadata |
|---|---|
| `NETWORK_NODE_RECONNECTED` | `offlineForSeconds` (tempo fora) e `lastSeenAt` (o contato anterior) |

Ele aparece na rota que a página do nó já usa, `GET /admin/network/nodes/{id}/history` — sem rota
nem tabela própria: a trilha que prova as ações é a mesma que as conta.

Decisões:

- **É a transição, não o heartbeat.** Uma linha por ciclo por nó afogaria a trilha; o que se guarda é
  "esteve fora por X e voltou agora".
- **Primeiro contato não é reconexão.** Nó que nunca bateu coração não "voltou"; registrá-lo sombrearia
  um incidente que não houve.
- **`offlineForSeconds` é o dado que importa.** "Voltou" sem "por quanto tempo" diz metade do que o
  operador precisa para decidir se a máquina é confiável.

**Verificação:** `NetworkNodeReconnectionTests` **4** (nó que volta depois da janela vira evento com o
tempo fora; heartbeat normal não gera evento; primeiro contato não é reconexão; o evento aparece no
histórico do próprio nó). Mutações: sem detectar a volta, primeiro contato como reconexão, heartbeat
normal auditado — **3 de 3 derrubam**.

## 6.7 Atraso de sincronização visto pelo painel

O brief §8.6 pede o **histórico de sincronização** por réplica. O nó já podia perguntar o próprio
atraso (`GET /network/content/lag`, autenticado pela chave dele); o administrador não via o de
ninguém. Sem isso, "a réplica X está atrasada" só era sabível entrando na máquina — e o operador que
precisa redistribuir carga não entra em vinte máquinas para descobrir qual está para trás.

A rota nova é a leitura do painel:

```
GET /admin/network/content/nodes/{nodeId}/lag
```

Devolve **todos** os tipos de conteúdo, cada um com `behind` — quantos agregados daquele tipo o nó
tem em versão anterior à da origem (`countBehind`). Tipo em dia aparece com `0`: o tipo ausente
esconderia qual conjunto foi medido.

Decisões:

- **Nó desconhecido é `404`, não lista zerada.** "Não está atrasado" e "não existe" não podem ser a
  mesma resposta — a primeira afirma saúde sobre um nó que pode nem existir.
- **A rota é do painel, não do nó.** Ela lê o atraso de outro nó pelo id; a autenticação é de
  administrador, e o `AdminRouteInterceptor` fecha `/admin/**` como rede de segurança.
- **É o atraso medido, não uma estimativa.** O número sai dos snapshots que o nó declarou ter contra
  a versão da origem; não há heurística de "provavelmente está atrasado".

**Verificação:** `AdminNetworkContentLagTests` **4** (o admin vê o atraso por tipo, com todos os
tipos presentes; nó desconhecido é `404`; sem sessão não abre; pessoa comum recebe `403`). Mutações:
nó desconhecido devolvendo lista zerada e leitura do nó errado — **2 de 2 derrubam**.

## 6.8 Quatro dimensões de saúde — `health`

O brief §8.8 exige "obrigatoriamente" distinguir quatro dimensões de saúde, com um motivo explícito:
**uma máquina pode estar online e a réplica inutilizável**. O quadro do nó era série + console +
`freshness`; não havia onde ler se a máquina responde, se a instalação se identifica, se o nó serve a
rede e se participa do balanceamento. Um único "verde/vermelho" afundava os quatro casos num só.

`GET /admin/network/nodes/{id}/observability` passa a devolver `health` com quatro campos, cada um em
`OK` / `DEGRADED` / `FAILING`:

| dimensão | pergunta que responde | `OK` | `DEGRADED` | `FAILING` |
|---|---|---|---|---|
| `host` | o processo do nó responde? | heartbeat na janela de 180 s | — | heartbeat velho ou nunca recebido |
| `runtime` | a instalação se identifica? | versão reportada | sem versão (runtime anterior ao campo) | — |
| `application` | o nó executa o que lhe foi autorizado, e bem? | com perfil, sem falhas seguidas, sem atraso | falhas seguidas ≥ 3, ou agregados atrás da origem | sem perfil |
| `network` | participa do balanceamento? | vivo, operando, com capacidade | saturado (CPU ≥ 85 %) ou sem vaga | parado, suspenso, revogado ou em quarentena |

Decisões:

- **Só o que o Control Plane mediu.** Não há sonda ao vivo: o nó não abre porta (o modelo é pull), e
  "está vivo" é o heartbeat dentro da janela — a mesma do scheduler, do painel e da série. Um segundo
  corte faria o nó estar vivo numa dimensão e morto na outra no mesmo quadro.
- **`runtime` sem versão é `DEGRADED`, não `FAILING`.** O agente está vivo e atende, só não se dá a
  conhecer; tratá-lo como falha tiraria do balanceamento máquinas que trabalham.
- **`application` sem perfil é `FAILING`, com host `OK`.** A máquina está de pé e não serve a ninguém —
  é o caso que o brief diz que não pode aparecer como "offline".
- **`network` separada da `application`.** Nó suspenso por decisão administrativa tem host e runtime
  saudáveis; o que caiu foi a participação. Sem essa separação o operador procuraria um defeito de
  máquina que não existe.
- **Atraso e falhas seguidas são `DEGRADED`.** Quantos agregados dependem de quantos existem na origem;
  essa proporção não está no quadro, então o estado não sobe sozinho para `FAILING`.

A derivação vive em `NodeHealth` (`network/domain`), puro: reusa `alive`, `canOperate`, `quarantined`,
`saturated`, `hasCapacity`, `failing` e os limiares do domínio (`SATURATED_CPU_PERCENT = 85`,
`FAILURE_STREAK_LIMIT = 3`) — nenhum limiar novo.

**Verificação:** `NodeHealthTests` **8** + 3 testes novos em `NetworkNodeObservabilityTests`. Mutações:
`network` ignorando `canOperate` (suspenso virando `OK`), `application` ignorando perfil vazio, `host`
sempre `OK` — **3 de 3 derrubam**.

## 6.9 Cobertura de trabalho: a rede tem quem faça cada tipo?

O brief §8.4 pede que o painel mostre como os nós trabalham juntos. `byProfile` conta perfis
**declarados**, e isso não responde a pergunta operacional: **a rede tem alguém para executar cada
tipo de trabalho agora?** Um nó com o perfil continua contado quando está em quarentena, de região
evacuada ou sem vaga — e não executa nada. Hoje essa descoberta custava abrir nó por nó até achar
que nenhum aceitava `MEDIA_TRANSCODE`.

`GET /admin/network/nodes/dashboard` passa a devolver `workTypes`: um item por tipo de
`TaskType`, com `capable` = quantos nós podem executá-lo **agora**.

| regra de "pode agora" | por quê |
|---|---|
| `ACTIVE` | suspenso, pendente e revogado não recebem tarefa |
| vivo na janela de 180 s | é a mesma régua do scheduler; nó sem heartbeat não aceita |
| sem quarentena vigente | decisão administrativa já o tirou do balanceamento |
| região não evacuada | região fora de operação não atende ninguém |
| com vaga (`hasCapacity`) | nó cheio não aceita, mesmo com perfil |
| o perfil cobre o tipo | `allowedFor`, a mesma regra do despacho (`NODE_LOCAL` cobre todos) |

Decisões:

- **Todos os tipos aparecem, inclusive com `0`.** O tipo ausente esconderia justamente a lacuna —
  é o erro que o painel tem de evitar. `capable = 0` é o vermelho da tela.
- **Conta nó, não vaga.** "Dois nós podem fazer mídia" é o que se quer saber para decidir se falta
  gente; quantas vagas restam em cada um já está em `freeSlots` e na carga por nó.
- **É o medido na hora, não cache.** O mesmo nó devolvendo a vaga muda o número na leitura seguinte.
- **Vale para todos os tipos, sem lista fixa.** A enumeração é `TaskType.values()`: tipo novo entra
  sozinho no painel, e não há lista paralela para esquecer de atualizar.

**Verificação:** 3 testes novos em `NetworkNodesTests` (tipo sem ninguém apto aparece com `0` e os
demais tipos também aparecem; nó em quarentena não conta; nó que devolve a vaga volta a contar).
Mutações: sem checagem de vaga, sem checagem de quarentena, e omitir os tipos zerados — **3 de 3
derrubam**.

## 7. O que NÃO está entregue

| item | por quê |
|---|---|
| leitura em streaming (push do backend para a tela) | a tela recarrega a rota a cada poucos segundos; um canal aberto por nó exigiria outra infraestrutura e outro modelo de falha |
| retenção longa / exportação do console | é janela de tela; para histórico existe o log do próprio nó, na máquina, e a auditoria do Control Plane |
| métricas de processo (heap, GC, threads) do nó | o nó reporta o que mede no host (CPU, memória, disco, tarefas). Heap/GC exigiriam o runtime expor métricas próprias |
| tela de front | as rotas existem; os quatro quadros (série, resumo, console, contagem) são o contrato da página do nó |
## 8. Defeito encontrado no caminho: o modo global vazando entre classes de teste

`NetworkStorageResultAcceptanceTests.oDespachoEnfileiraOPutParaADesignacaoPendente` falhava em cerca
de **uma a cada três** rodadas da suíte completa, sempre passando isolado. A causa foi medida, não
suposta: nos modos `DEGRADED` e `EMERGENCY` o despacho não designa nada (`dispatchLimit = 0`), e o
`network_state` — estado **global** da rede — é deixado nesses modos por outras classes e **não** era
limpo pelo `setUp` do teste de Storage. A tarefa ficava com `assigned_node_id` nulo com o nó certo
elegível, e o resultado dependia de qual classe rodou antes.

O `setUp` passa a limpar `network_state`, como `NetworkTasksTests` e `NetworkModesTests` já faziam.
**Prova discriminante:** `NetworkTasksTests` + `NetworkStorageResultAcceptanceTests` na mesma JVM —
sem a limpeza **falha**, com a limpeza **passa**. O `network_state` é a segunda tabela global do
módulo que os testes precisam zerar (a primeira foi o catálogo de conteúdo, §6.8).

## 9. A contribuição que a tela do participante mostra

A interface do participante (`GET /network/participant/nodes` e `/nodes/{id}`) mostrava só
`tasksDone`. Dois casos ficavam indistinguíveis:

- **tarefa reciclada não contava para quem a executou.** `countDoneByNode` agrupa por
  `assigned_node_id`, que é zerado quando a tarefa volta para a fila; se outro nó a conclui, o
  crédito vai para ele. Numa rede onde réplicas caem no meio do trabalho — o motivo de existir a
  reciclagem —, quem executou e perdeu o claim não aparecia em lugar nenhum;
- **zero de nó novo e zero de nó parado eram iguais.** "0 tarefas" para o nó aprovado há cinco
  minutos e para o que roda há uma semana sem concluir nada.

Agora a rota devolve também **`tasksRequeued`**: tarefas que este nó **entregou** e tiveram o
resultado recusado, voltando à fila.

| campo | o que é |
|---|---|
| `tasksDone` | concluídas **por este nó como último executor** |
| `tasksRequeued` | entregues por ele e devolvidas à fila (resultado recusado) |

Decisões:

- **Só a devolução com o nó como ator.** A trilha já separa os casos: `claim_expired` é auditado como
  `system` — o nó não devolveu nada, ele sumiu no meio do trabalho. Contá-lo como contribuição
  transformaria abandono em atividade. Entra só `NETWORK_TASK_RESULT_REFUSED`, que é a recusa do
  resultado que o próprio nó enviou.
- **Lida da auditoria, não deduzida.** É a mesma linha que prova a recusa, contada por `actor_id`
  pelo índice `idx_audit_actor_created` (V1). O número e a prova não podem divergir porque são a
  mesma linha.
- **A desconexão devolve o mesmo quadro.** A resposta de `POST /nodes/{id}/disconnect` antes montava a
  visão com zeros inventados; ela passa a ler o nó de verdade, e o trabalho já feito continua visível
  no instante em que o participante sai.

**Verificação:** `NetworkNodesTests` **4 novos** (nó novo devolve `0`/`0`; conclusão e devolução
aparecem separadas; claim vencido do `system` não conta; desconexão mantém a contribuição). Mutações:
contar o reenfileiramento do sistema, somar a devolução em `tasksDone`, e devolver zero inventado na
desconexão — **3 de 3 derrubam**.
