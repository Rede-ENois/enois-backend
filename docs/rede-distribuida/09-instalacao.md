# 09 — Instalação do Node Runtime

O instalador transforma uma máquina comum em participante da rede sem que o
participante precise conhecer a arquitetura interna. Está em
[`deploy/node-runtime/`](../../deploy/node-runtime/):

| arquivo | papel |
|---|---|
| `docker-compose.yml` | o container do nó: `read_only`, `cap_drop: ALL`, `no-new-privileges`, limites de CPU/memória/PIDs, sem porta exposta e sem docker socket; `entrypoint` roda o loop do nó, não o jar |
| `install.sh` | o fluxo de instalação em três passos (abaixo), mais backup e restauração da identidade |
| `node-runtime.sh` | o loop de execução: heartbeat → config assinada → poll → executa o que a config autoriza → resultado |
| `node-doctor.sh` | diagnóstico PASS/WARN/FAIL — mesmo formato do `scripts/dev-doctor.sh` do backend |

## 1. Os três passos

```bash
# 1. Enroll com o token de provisionamento que o admin entregou por canal privado.
./install.sh --control-plane https://<control-plane> --token <token> \
             --region sa-east-1 --country BR

# 2. Depois de o admin gerar a API key na tela de administração, cole-a uma vez.
#    Ela fica em $NODE_HOME/api-key com permissão 600 e nunca aparece em log.
./install.sh --set-key

# 3. Sobe o container — mas só depois de provar o primeiro heartbeat com a chave.
./install.sh --start --control-plane https://<control-plane>
```

O `--start` valida a credencial contra o Control Plane **antes** de subir
qualquer container: subir um nó com chave revogada só criaria um loop de erro.

### 1.1 O pino da config assinada (TOFU)

A chave pública que assina a config é pinada em `$NODE_HOME/pinning-key`, e é
**contra ela** que o runtime verifica a assinatura de cada config (não contra o
campo `publicKey` do corpo); config assinada por outra chave é recusada, inclusive
depois de uma rotação silenciosa no Control Plane. O pino é gravado **uma única vez**:

- **no enroll**, quando a config já traz `publicKey` (nó com chave de configuração
  ativa do lado do Control Plane);
- **no primeiro `--start`**, quando o enroll não conseguiu (nó recém-criado ainda
  sem chave): é o momento TOFU — a credencial do nó acabou de ser provada pelo
  heartbeat, que é a única circunstância em que confiar na chave devolvida é
  legítimo;
- **nunca depois**: um `--start` com pino existente não re-pina, mesmo que o
  Control Plane passe a devolver outra chave. Trocar a chave é decisão consciente
  do participante (remover o arquivo e pinar de novo), não um efeito colateral.

## 2. O que o instalador materializa das regras da rede

- **Token de uso único.** Rodar o enroll de novo com o mesmo token falha com
  `NODE_TOKEN_USED`; o install repetido com `node.json` existente não refaz a
  requisição.
- **API key não vem do enroll.** O enroll devolve `nodeId` e fica pendente; a
  chave é gerada pelo admin (`POST /admin/network/nodes/{id}/keys`) e o
  participante a cola uma vez com `--set-key`. Sem chave, o install para no
  passo 2 com a instrução do próximo passo.
- **Pull, não push.** O compose não publica porta nenhuma: o nó pergunta
  (`GET /network/tasks/next`) e o Control Plane nunca abre conexão para ele —
  participante atrás de NAT funciona.
- **Limite é contrato.** O container nasce com `mem_limit`/`cpus`/`pids_limit`:
  o nó não consegue consumir mais do que o participante declarou, mesmo se o
  processo dentro dele quiser.
- **Região como a doc documenta.** Quando `--region` é passado, o corpo vai
  aninhado em `location` (contrato §2), forma que o DTO exige e o teste
  `enrollComLocationDaDocGravaARegiao` fixa.

## 3. Diagnóstico

```bash
NODE_HOME=/var/lib/enois-node ./node-doctor.sh --control-plane https://<control-plane>
```

Checa dependências, identidade registrada, permissão da chave (600) e do
diretório (700), **presença e permissão do pino da config** (sem pino o runtime
recusa toda config — o doctor aponta o `--start` que resolve), estado do
container e conectividade com o Control Plane.
Sai 0 quando não há FAIL; WARN não impede a operação. Nunca imprime a API key.

## 3.1 O loop de execução (`node-runtime.sh`)

O container roda o loop do nó, não o jar (o jar é o Control Plane). Cada ciclo:

1. **heartbeat** com capacidade observada — a resposta traz o modo operacional e
   `acceptTasks`. O corpo leva também `observedTerm`, o último termo de autoridade
   aceito (`$NODE_HOME/term`): é por ele que o Control Plane detecta que este nó viu
   um mando maior que o vigente e o põe em quarentena (doc 06 §4.2). Nó que nunca
   aceitou config não manda o campo;
2. **drenar é não pedir**: `acceptTasks:false` (DEGRADED/EMERGENCY, ou o próprio nó
   fora de operação) encerra o ciclo sem consultar a fila;
3. **config assinada**: recusa config sem assinatura (padrão do runtime) e confere
   o campo `publicKey` contra o pino gravado no enroll ou no primeiro `--start`
   (`$NODE_HOME/pinning-key`); sem pino, recusa toda config. Depois **verifica a
   assinatura Ed25519**: reconstrói a linha canônica do corpo recebido e roda
   `openssl pkeyutl -verify -rawin` contra o X.509 do pino (base64 → DER). O campo
   `publicKey` viaja no mesmo corpo que a permissão, então comparar só ele provaria
   que quem mandou sabe copiar uma string; a assinatura é a prova. Falha fechada:
   openssl ausente, pino ilegível, campo assinado faltando ou assinatura que não
   confere — o ciclo termina sem pedir tarefa. O **termo da autoridade** entra na
   mesma linha assinada (formato 2) e o runtime guarda o maior já aceito em
   `$NODE_HOME/term`: config de termo menor é recusada como autoridade deposta
   (doc 06 §4), e o termo recusado não rebaixa o registro local;
4. **poll** (`GET /network/tasks/next`) — uma tarefa por ciclo;
5. **executa só o que a config assinada autoriza** (`allowedTasks`): autorizada vai
   para o executor do tipo; fora da lista vira `FAILED` com `TASK_NOT_ALLOWED` — o
   Control Plane decide sozinho se redistribui;
6. **resultado** (`POST /network/tasks/{id}/result`) com a tentativa vigente.

**Executor de mídia (`MEDIA_TRANSCODE`).** O nó baixa o objeto em
`GET /network/media/{id}/source` (a permissão é perfil `NODE_MEDIA` **e** designação
vigente), roda a receita do `MediaTranscodeSpec` — escala de 854px, mesmos encoders
e limite de 2 threads por tipo, `-protocol_whitelist file`, `-movflags +faststart`
no mp4 — e envia o arquivo em `PUT /network/media/{id}/result`. A receita é do
Control Plane, não do nó: um nó que invente a própria receita produz um arquivo que
o pod não produziria. O nó declara no resultado as medidas que o Control Plane
devolveu **ao medir o arquivo recebido** (tamanho e sha-256); se a resposta não
trouxer as medidas, ele declara as suas e o Control Plane confere contra as dele.

Falha é código próprio, não silêncio: sem `ffmpeg` no runtime → `TASK_EXECUTOR_MISSING`;
payload sem `mediaId`/`mimeType` ou tipo fora do contrato → `TASK_PAYLOAD_INVALID`;
download, ffmpeg ou upload que falham → `TRANSCODE_FAILED`. O rascunho do arquivo
fica no volume do nó (`$NODE_HOME/work-task-*`, apagado ao fim do ciclo) e **não**
em `/tmp`, que o compose monta como tmpfs de 64 MB — menor que o teto de 150 MB do
transcode.

**Executor de réplica (`READ_QUERY`).** O nó percorre o catálogo em
`GET /network/replica/{kind}` — `kind` vem no payload, a allowlist (`PROFILE`,
`COMMUNITY`) é do Control Plane — e, para cada id, lê o corpo em
`GET /network/content/{kind}/{id}` e confirma o que guardou em
`POST .../synced` com **versão e hash**. A travessia é por cursor
`(criado em, id)`, nunca por offset: percorrer um catálogo vivo por posição pula
item quando algo é apagado e relê quando algo nasce.

O estado da réplica fica em `$NODE_HOME/replica/`: `cursor-<kind>` guarda onde a
travessia parou, `<kind>/<id>.json` o corpo e `<kind>/<id>.version` a versão
guardada. Na rodada seguinte o nó pergunta `?version=<guardada>` — item em dia
volta `changed=false` sem corpo, então o catálogo inteiro não é rebaixado a cada
ciclo. A travessia é limitada por ciclo (`REPLICA_PAGES_PER_TASK`, 20 páginas) e,
se acabar antes do fim, devolve o cursor e conclui `complete=false`; a próxima
tarefa retoma de onde parou — drenar metade do catálogo não é erro.

**404 na origem é remoção, não falha:** perfil banido ou comunidade excluída não
vira linha no feed de mudanças, então é aqui que o nó descobre. Ele apaga o corpo
e a versão guardados (senão serviria o que a vitrine esconde) e conta em
`removed`. Falha de rede e 5xx mantêm o corpo — descartar por erro transitório
seria pior que reter por uma rodada.

**Executor de cache de borda (`EDGE_CACHE`).** O nó consome o **feed de mudanças**
(V283): `GET /network/content/changes?cursor=<seq>&limit=<n>` devolve, depois do
cursor que o nó guardou, só o que ganhou versão nova — `kind`, `contentId`,
`version`, `contentHash` — e **nunca o corpo**. Para cada mudança, o corpo vem de
`GET /network/content/{kind}/{id}` (o mesmo caminho autorizado da borda), é
guardado e confirmado em `POST .../synced` com versão e hash.

O cache é descartável; o **cursor não**. Ele só avança depois de a entrada estar
em disco: consumir a mudança sem baixar o corpo deixaria a entrada velha até o
TTL — ou para sempre, se o agregado nunca mais fosse editado. Falha no meio
devolve `complete=false` com o cursor de onde parou, e a próxima tarefa retoma.

- **`reset=true` é recarga, não "nada mudou":** o cursor ficou atrás da janela
  retida (o feed é janela, não histórico). O nó apaga o cache inteiro e recomeça
  do cursor 0, que o Control Plane trata como carga normal — e o guarda de
  "reset já feito" impede giro infinito se o segundo pedido também voltasse
  `reset`.
- **404 na origem é remoção:** o nó apaga o corpo e a versão guardados e conta em
  `removed`. Remoção não vira linha no feed, então é revalidando que ele descobre.
- **Em dia não rebaixa:** a versão guardada vai como `?version=`; `changed=false`
  volta sem corpo e a entrada não é reescrita nem reconfirmada.

O estado fica em `$NODE_HOME/edge/`: `cursor` guarda o último `seq` processado,
`<kind>/<id>.json` o corpo serve à borda e `<kind>/<id>.version` a versão
guardada. Por ciclo: `EDGE_CHANGES_PER_TASK` (200) mudanças por página e
`EDGE_PAGES_PER_TASK` (5) páginas; esgotado o orçamento, devolve `complete=false`
com o cursor.

**Quem enfileira `EDGE_CACHE` — e por que a tarefa tem alvo.** O executor sozinho
não bastava: nenhuma tarefa chegava até ele. Toda tarefa de borda nasce **com
alvo** (`network_tasks.target_node_id`, V287), e não para "a rede", porque o
cursor e os corpos do cache vivem em `$NODE_HOME/edge/` **daquele** nó: uma
tarefa sem alvo seria designada por mérito a um nó sorteado, que avançaria só o
próprio cursor enquanto os outros de borda continuam frios. Alvo é filtro, ao
contrário de `region` (que ordena entre elegíveis): se o nó alvo está offline,
suspenso ou sem perfil, a tarefa permanece na fila em vez de ir para outro nó.
Dois caminhos criam a tarefa:

- **Pedido explícito** — `POST /admin/network/nodes/{nodeId}/edge-cache` (admin).
  É o caso imediato: nó recém-aprovado, ou cache descartado por `reset`, precisa
  buscar o conteúdo agora, sem esperar o ciclo. `GET` na mesma rota lista os
  pedidos em aberto do nó;
- **Aquisição periódica** — `EdgeCacheWorker` a cada 2 min dá uma tarefa a cada
  nó de borda ativo e vivo **que não tenha pedido em aberto**. Sem a condição de
  parada, cada ciclo empilharia uma tarefa que executaria as mesmas páginas sem
  avançar o cursor.

A chave de idempotência é `edge:<nó>:<janela de 10 min>`: por nó, porque dois nós
têm cursores diferentes; por janela, porque o mesmo nó precisa de trabalho novo
depois de consumir o feed. Um pedido repetido na mesma janela devolve a tarefa
existente.

**`EDGE_DELIVER`: entrega verificada, não ouvinte HTTP.** O nó não expõe porta
(§2), então "entregar" não é servir: é o Control Plane perguntar a um nó de
borda específico *"você entrega este conteúdo agora?"* e receber um veredicto
sobre o que aquele nó **tem**. O executor lê `kind` e `contentId` do payload,
revalida a entrada do cache com `GET /network/content/{kind}/{id}?version=`,
guarda o corpo novo (quando há) **antes** de confirmar, reconfirma em
`/synced` e conclui com
`{"kind":…,"contentId":…,"version":…,"contentHash":…,"deliverable":true,"source":"cache|refetched"}`.
A reconfirmação é obrigatória nos dois caminhos: sem ela o veredicto atestaria
o cache de antes, não o estado agora. 404 vira veredicto negativo
(`CONTENT_GONE`) e descarta a entrada local — entregar o que a origem esconde é
impossível em qualquer nó, e veredicto negativo por remoção alheia não é falha
do executor.

Do lado do Control Plane, `POST /admin/network/nodes/{nodeId}/edge-deliver?kind=&contentId=`
enfileira a tarefa **com alvo** (o corpo verificado é o do cache daquele nó) e
o veredicto é conferido contra o snapshot que o próprio nó gravou em `/synced`:
veredicto positivo sem confirmação é recusado (`EDGE_DELIVER_UNCONFIRMED`), e
versão ou hash diferentes do confirmado também (`EDGE_DELIVER_MISMATCH`,
retentável). A declaração do nó nunca é aceita por si só.

O único tipo ainda em prova de ciclo de vida (eco do `idempotencyKey`) é
`API_REQUEST`.
Conteúdo de usuário (HTML, CSS, KofScript) **nunca** executa no nó — continua nos
sandboxes próprios (02-modelo-de-ameacas.md). O script é montado no container
(`/node-runtime.sh:ro`); a imagem do runtime se troca por `install.sh --update`
(§6), nunca por `docker pull` manual — a troca só acontece com assinatura OCI
verificada.

## 4. Estado local

| caminho | conteúdo | permissão |
|---|---|---|
| `$NODE_HOME/node.json` | resposta do enroll (`nodeId`, estado) | 600 |
| `$NODE_HOME/api-key` | a API key do nó em claro — único lugar | 600 |
| `$NODE_HOME/pinning-key` | a chave pública do Control Plane, pinada no enroll ou no primeiro `--start` | 600 |
| `$NODE_HOME/term` | o maior `term` de autoridade já aceito — config de termo menor é recusada | 600 |
| `$NODE_HOME/image-pubkey` | a chave pública que assina as imagens do runtime (cosign) — sem ela não há `--update` | 600 |
| `$NODE_HOME/runtime-image` | o digest verificado em uso (`repo@sha256:…`), não a tag | 600 |
| `$NODE_HOME/runtime-image.previous` | o digest anterior, para `--rollback` | 600 |
| `$NODE_HOME/replica/cursor-<kind>` | onde a travessia do catálogo parou (`<criado em>|<id>`) | 600 |
| `$NODE_HOME/replica/<kind>/<id>.json` + `.version` | conteúdo público guardado e a versão confirmada | 600 |
| `$NODE_HOME/edge/cursor` | o último `seq` do feed de mudanças já aplicado no cache | 600 |
| `$NODE_HOME/edge/<kind>/<id>.json` + `.version` | conteúdo público em cache de borda e a versão confirmada | 600 |
| volume `enois-node` | workspace do runtime no container | — |

`NODE_HOME` padrão é `/var/lib/enois-node`; sobrescreva por ambiente. O par
`nodeId` + chave é a identidade do nó: apagar os dois arquivos não "desregistra"
nada — a revogação é decisão do admin, e voltar após revogação exige cadastro
novo (identidade nova, chave nova).

## 5. Backup da identidade

A config assinada se recupera do Control Plane (`GET /network/nodes/me/config`);
a identidade, não — ela só existe em `$NODE_HOME`. Perdida, o re-enroll cria um
nó **novo** e a aprovação humana se repete. Para máquina reformada ou migrada:

```bash
# Na máquina atual: arquiva node.json + api-key num tar.gz com permissão 600.
./install.sh --backup              # → enois-node-<nodeId>-<data>.tar.gz

# Na máquina nova (sem node.json existente): restaura e sobe.
./install.sh --restore enois-node-nd_x-20261112-150000.tar.gz
./install.sh --start --control-plane https://<control-plane>
```

O `--restore` se recusa a pisa uma identidade já registrada — trocar o nó em
operação por engano seria pior que refazer o onboarding. O backup vale como a
chave: quem tem o arquivo opera como o nó; guarde com o mesmo cuidado e descarte
junto com a revogação (a retenção acompanha o ciclo de vida da identidade).

## 6. Atualização e rollback (assinatura OCI)

Trocar a imagem do runtime não é `docker pull`: é substituir o que roda no nó.
Por isso a troca exige três coisas — a chave pública que assina as imagens, o
**digest** da imagem nova (nunca a tag, que o registry reescreve) e a verificação
`cosign` sobre esse digest.

```bash
./install.sh --update                 # resolve o digest da tag qa, verifica e sobe
./install.sh --update --version v1.2.3   # mesma coisa, para uma versão publicada
./install.sh --rollback               # volta ao digest anterior (verificado de novo)
```

A chave sai de `--image-pubkey <arquivo>` ou de `$NODE_HOME/image-pubkey`. Sem
chave, o `--update` **recusa**: atualizar sem verificação é justamente o que a
assinatura existe para impedir. O fluxo é fail-secure — assinatura inválida,
ausente ou digest não resolvido abortam a troca e o runtime atual continua
rodando; nada é gravado em `runtime-image` antes da verificação passar.

O digest verificado fica em `$NODE_HOME/runtime-image` e o `--start` passa a
usá-lo (`NODE_RUNTIME_IMAGE=<digest>`), de modo que um restart não volte à tag
que o registry possa ter reescrito. O `--rollback` relê
`runtime-image.previous`, **verifica a assinatura de novo** com a chave atual
(uma imagem que não verifica mais não volta) e grava o digest que estava em uso
como novo anterior — rollback repetido alterna entre os dois, não perde o outro.

Comportamento fixado em `scripts/tests/node-runtime-test.sh` (43 asserções): sem
chave recusa; update grava e sobe o digest; assinatura inválida recusa e o
runtime atual sobrevive; rollback sem anterior recusa; rollback aplica o anterior
e preserva o atual; digest não verificável é recusado.

## 7. O que ainda não está aqui

- **Executores de trabalho real por perfil**: quatro já executam de verdade no nó —
  `MEDIA_TRANSCODE` (receita do Control Plane, resultado verificado por tamanho e
  hash), `READ_QUERY` (travessia do catálogo da réplica com corpo e confirmação),
  `EDGE_CACHE` (consumo do feed de mudanças com corpo baixado e confirmado) e
  `EDGE_DELIVER` (revalidação da entrada e veredicto de entrega conferido contra
  o snapshot do nó), 112 asserções em `scripts/tests/node-executor-test.sh`.
  Falta `API_REQUEST` (NODE_API), que hoje cai no eco de ciclo de vida.
- **Banda compartilhada entre execuções concorrentes**: o teto declarado
  (`limits.bandwidthMbps`) já vale como `curl --limit-rate` em cada transferência de
  corpo do runtime. Hoje o runtime executa uma tarefa por ciclo, então limitar cada
  transferência limita o nó; se o ciclo passar a executar em paralelo, o limite tem
  de virar taxa compartilhada — senão N tarefas concorrentes multiplicariam a banda
  que o participante cedeu por N.
- **Publicação das imagens assinadas**: o verificador está pronto e testado; o
  pipeline que constrói e assina as imagens do runtime (`cosign sign`) entra com
  o registro de imagens da rede.
