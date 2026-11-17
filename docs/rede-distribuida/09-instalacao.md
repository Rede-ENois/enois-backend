# 09 — Instalação do Node Runtime

O instalador transforma uma máquina comum em participante da rede sem que o
participante precise conhecer a arquitetura interna. Está em
[`deploy/node-runtime/`](../../deploy/node-runtime/):

| arquivo | papel |
|---|---|
| `docker-compose.yml` | o container do nó: `read_only`, `cap_drop: ALL`, `no-new-privileges`, limites de CPU/memória/PIDs, sem porta exposta e sem docker socket; `entrypoint` roda o loop do nó, não o jar |
| `Dockerfile` | a base do container do nó (`debian:bookworm-slim` por digest + curl/openssl/ffmpeg, usuário 10001) — construída **nesta máquina** pelo bootstrap, não baixada de registry |
| `bootstrap.sh` | criação programática do nó numa passada: obtém o código (release oficial verificado por sha256, ou git na fonte de verdade), constrói a imagem local, roda o ciclo do `install.sh` e grava versão/fonte em `$NODE_HOME` |
| `install.sh` | o fluxo de instalação em três passos (abaixo), mais backup/restauração da identidade; `--update`/`--rollback` delegam ao `bootstrap.sh` |
| `node-runtime.sh` | o loop de execução: heartbeat → config assinada → poll → executa o que a config autoriza → resultado |
| `node-doctor.sh` | diagnóstico PASS/WARN/FAIL — mesmo formato do `scripts/dev-doctor.sh` do backend |
| `painel.py` | painel de observabilidade local (TUI, Python stdlib): config assinada, métricas do container e do host, eventos e log do executor — ver §3.2 |

O runtime também tem pipeline de distribuição próprio: a tag `slave-v*` no
repositório dispara `.github/workflows/slave-release.yml`, que empacota
`deploy/node-runtime/` num tar.gz **achatado** com um `VERSION`
(`<fonte>@<sha8>`) e publica `enois-node-runtime-<tag>.tar.gz` +
`SHA256SUMS` no GitHub Releases — sem tocar o CI/CD de deploy que já funciona.

## 1. Os três passos (ou uma passada: bootstrap)

O **bootstrap** cria o nó programaticamente numa só passada — é o caminho
recomendado para quem vai contribuir com infra:

```bash
./bootstrap.sh --control-plane https://<control-plane> --token <token> \
               --region sa-east-1 --country BR          # fonte de verdade: qa
./bootstrap.sh --update --source main                    # troca a fonte e atualiza
./bootstrap.sh --rollback                                # volta ao runtime anterior
./bootstrap.sh --status                                  # diagnóstico (node-doctor)
```

Ele obtém o código do runtime e **constrói a imagem nesta máquina**: primeiro
tenta o **release oficial** (baixa o tar.gz e confere o sha256 contra o
`SHA256SUMS` publicado — pacote adulterado é recusado e o runtime atual segue
rodando); sem release publicado, cai para o **git** na fonte de verdade. A fonte
é escolha explícita e **lista fechada** (`qa` ou `main` — branch ou fork
arbitrário é recusado no parse), e fica gravada em `$NODE_HOME/runtime-source`;
o `--update` sem `--source` mantém a fonte já gravada, nunca troca sozinho. A
versão em uso (`qa@<sha8>` — o commit exato que foi construído) vai para
`$NODE_HOME/runtime-version` e é o que o heartbeat declara: é assim que o painel
vê divergência de runtime entre nós. Depois roda o ciclo do `install.sh`
(enroll com o token de uso único → cola a API key que o admin gerou → `--start`).

Os três passos manuais continuam existindo para quem quer entender cada etapa:

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

### 1.2 Ligar a config assinada no Control Plane

Sem chave configurada, o Control Plane responde `signed:false` e `publicKey:null`,
o pino não tem o que gravar e o nó autentica mas não executa — é o estado que o
`node-doctor.sh` mostra como "a rede não despacha". O par é gerado **uma vez** e
fica no ambiente do Control Plane:

```bash
openssl genpkey -algorithm ed25519 -out signing.pem

# publica em X.509 base64 (vai no payload e é pinada pelo nó)
openssl pkey -in signing.pem -pubout -outform DER | base64 -w0

# privada em PKCS#8 base64 (assinatura, só no Control Plane)
openssl pkcs8 -topk8 -nocrypt -in signing.pem -outform DER | base64 -w0
```

Exporte `REDE_NETWORK_CONFIG_SIGNING_KEY` (privada) e
`REDE_NETWORK_CONFIG_SIGNING_PUBLIC_KEY` (pública) no serviço e reinicie. A
resposta de `GET /network/nodes/me/config` passa a sair com `"signed":true`. O nó
que ainda não tem pino pina no próximo `--start`; o que já tem pino continua
aceitando a chave nova só se ela for a mesma que ele pinou — trocar o par exige
remover o pino no nó, que é decisão do participante (TOFU, §1.1).

Gerar o par, provar que o Control Plane assinou de verdade e acompanhar um nó
até o pino tem ferramenta própria em
[`deploy/config-signing/`](../../deploy/config-signing/README.md): o master gera
o par e verifica a assinatura com o fingerprint esperado; o slave instala o
monitor que pina a chave no nó quando ela aparecer.

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
diretório (711 até a entrega ao container; 750 depois), **presença e permissão do pino da config** (sem pino o runtime
recusa toda config — o doctor aponta o `--start` que resolve), estado do
container e conectividade com o Control Plane.
Sai 0 quando não há FAIL; WARN não impede a operação. Nunca imprime a API key.

## 3.2 Painel de observabilidade local (`painel.py`)

O `node-doctor.sh` responde "o nó está pronto?"; o painel responde "o que o nó
está fazendo agora?". É uma TUI **read-only** em Python puro (só stdlib — nada
para instalar no nó):

```bash
python3 deploy/node-runtime/painel.py
```

O que mostra, atualizado a cada 3 s (`Q` sai, `R` força uma atualização):

- **Contrato vigente**: estado do nó (`ACTIVE`/`PENDING_APPROVAL`…), se a config
  está assinada (`✓ Ed25519` = o nó verifica a assinatura contra o pino),
  `configVersion`/`formatVersion`/termo, perfis, `allowedTasks`,
  `allowedRoutes` e limites (cores, memória, disco declarado × disco livre
  **medido** que o próprio nó subiu no heartbeat).
- **Container**: CPU com sparkline (~6 min de histórico), memória, rede e bloco;
  bytes do cache de borda e da réplica; cursor do feed de mudanças.
- **Host**: load, memória livre, uptime.
- **Eventos recentes e log do executor**: tarefas despachadas, `DONE`/`FAILED`
  na janela do log, recusas de assinatura, erros de rede — verde quando tudo
  flui, vermelho no que exige atenção (502 do Control Plane é erro DELE, não do
  nó: o painel deixa isso legível em vez de parecer que o nó quebrou).

A identidade do nó (`nodeId`, API key) é lida **via container** (`docker exec` —
o operador do nó tem docker, não precisa de sudo) ou, fora do container, de
`$ENOIS_NODE_HOME`. Variáveis de ambiente para rodar contra outro nó/CP:
`ENOIS_NODE_ID`, `ENOIS_NODE_HOME`, `ENOIS_CONTROL_PLANE`, `ENOIS_NODE_CONTAINER`,
`ENOIS_ORIGIN`, `ENOIS_PANEL_REFRESH`. O painel não manda heartbeat nem executa
tarefa — ele só olha; nada do que ele faz aparece para o Control Plane além das
leituras GET que o próprio nó já faz.

### Regra de manutenção: o painel acompanha a evolução da rede

Este painel é parte da superfície operacional do nó, não acessório — painel que
mostra contrato velho é pior do que nenhum. Por isso:

**Todo PR que mudar o contrato visível ao nó atualiza o `painel.py` no mesmo PR.**
Em concreto, mexe no painel quem mexer em:

- campos de `/network/nodes/me/config` (`ConfigResponse`/`NodeConfigView`) —
  campo novo entra no painel; campo removido sai;
- o formato da config assinada (`NetworkConfigSigner`, `formatVersion`);
- os enums de tarefa (`NodeTaskType`/`allowedTasks`) e de rota
  (`allowedRoutes`) — o painel lista o que a config traz, e os executores
  novos precisam aparecer nos eventos;
- o texto dos eventos que o `node-runtime.sh` escreve no log (o painel parseia
  padrões como `tarefa tk_… tipo=… tentativa=…`, `aceito pela rede`,
  `recusada`) — mudou a linha do log, muda o parse;
- novos subcomandos de diagnóstico: se o `node-doctor.sh` ganhar uma checagem
  nova relevante para o operador, o painel a reflete.

O teste de fumaça é barato e não precisa de nó real: rodar o painel contra o
ambiente de QA e conferir que os campos novos aparecem. Registra-se aqui a
obrigação para não virar código órfão na próxima evolução da rede.

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
(V312): `GET /network/content/changes?cursor=<seq>&limit=<n>` devolve, depois do
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
alvo** (`network_tasks.target_node_id`, V316), e não para "a rede", porque o
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

**`API_REQUEST`: o destino não vem do payload.** O payload carrega só o
**nome** da rota (`{"route":"catalogo","method":"GET"}`); para onde ir está na
lista `allowedRoutes` da config **assinada** (formato 4). O executor resolve o
nome contra essa lista e, se não achar, falha com `API_ROUTE_NOT_ALLOWED` — sem
requisição nenhuma. Não existe "se não achar, usa a URL do payload": essa
alternativa é um SSRF apontado para dentro da rede do participante, e é
exatamente o que a assinatura impede (doc 02 §4.2, ADR-020).

O executor chama a rota **sem `-L`** (o destino não escolhe outro por
redirect), **sem a credencial do nó** (o `X-Node-Key` é do Control Plane, não
do destino), com teto de tempo (30 s) e de tamanho (1 MiB). O `--max-filesize`
do curl só confia no `Content-Length`, então o executor **mede** o arquivo
baixado e recusa acima do teto — servidor que não declara o tamanho não escapa
do limite. O que volta ao Control Plane é **evidência**, não conteúdo:
`{"route":…,"status":…,"bytes":…,"digest":…}`. O corpo da rota externa não
entra na rede.

A lista de rotas vem de `rede.network.config.allowed-routes`
(`NETWORK_CONFIG_ALLOWED_ROUTES`) e é **vazia por padrão**: sem rota
declarada, o nó não tem para onde ir e a task é recusada. Entrada malformada
(sem `=`, ou com nome/URL vazio) é descartada com aviso e nunca entra na
assinatura.

A lista é também o que o **produtor** consulta: `POST
/admin/network/nodes/{nodeId}/api-request?route=<nome>` enfileira a task com
alvo, e o nome pedido é conferido contra a mesma lista assinada que o nó
recebe. Nome fora da lista é recusado **antes** de a task nascer
(`API_ROUTE_NOT_ALLOWED`, 422), para que a fila não encha de trabalho fadado a
falhar no nó. O painel lista os nomes assinados em `GET /admin/network/api-routes`.

Quem declara a rota escreve `nome=url` e obedece a quatro regras, porque cada
uma protege uma propriedade de que o runtime depende: nome em `[A-Za-z0-9._-]`
(o mesmo conjunto que o runtime aceita no payload), destino `http://` ou
`https://`, destino só em ASCII imprimível sem `"`, `\` nem `]` (o primeiro vira
escape no JSON e o último fecha o array — o destino seria lido em pedaços), e
nome único (duas URLs para o mesmo nome significam um destino que nunca será
chamado).

**A vírgula da query string não corta o destino.**
`https://api.exemplo/x?ids=1,2` é **um** destino. A lista é lida quebrando os
itens pelo trecho `","` — o que separa dois elementos do array — e não por
qualquer vírgula: cortar ali daria ao nó um caminho **diferente** do assinado,
e a chamada iria para um lugar que ninguém autorizou. Quem precisar de uma
vírgula literal na query escreve `%2C`.

Conteúdo de usuário (HTML, CSS, KofScript) **nunca** executa no nó — continua nos
sandboxes próprios (02-modelo-de-ameacas.md). O script é montado no container
(`/node-runtime.sh:ro`); a imagem do runtime se troca por `bootstrap.sh --update`
(§6), nunca por `docker pull` manual — a troca só acontece com o código da
release oficial conferido por sha256, ou vindo do git na fonte de verdade.

## 4. Estado local

| caminho | conteúdo | permissão |
|---|---|---|
| `$NODE_HOME/node.json` | resposta do enroll (`nodeId`, estado) | 600 até o `--start`; 640 depois (dono 10001) |
| `$NODE_HOME/api-key` | a API key do nó em claro — único lugar | 600 até o `--start`; 640 depois (dono 10001) |
| `$NODE_HOME/pinning-key` | a chave pública do Control Plane, pinada no enroll ou no primeiro `--start` | 600 |
| `$NODE_HOME/term` | o maior `term` de autoridade já aceito — config de termo menor é recusada | 600 |
| `$NODE_HOME/runtime-source` | a fonte de verdade do código (`qa` ou `main` — lista fechada) | 600 |
| `$NODE_HOME/runtime-version` | a versão em uso (`<fonte>@<sha8>` — o commit construído, o que o heartbeat declara) | 600 |
| `$NODE_HOME/runtime-version.previous` | a versão anterior, para `--rollback` | 600 |
| `$NODE_HOME/control-plane` | a URL do Control Plane, gravada pelo `--start` — os updates seguintes não redigitam | 600 |
| `$NODE_HOME/src/` | o código conferido de que a imagem local foi construída | — |
| `$NODE_HOME/replica/cursor-<kind>` | onde a travessia do catálogo parou (`<criado em>|<id>`) | 600 |
| `$NODE_HOME/replica/<kind>/<id>.json` + `.version` | conteúdo público guardado e a versão confirmada | 600 |
| `$NODE_HOME/edge/cursor` | o último `seq` do feed de mudanças já aplicado no cache | 600 |
| `$NODE_HOME/edge/<kind>/<id>.json` + `.version` | conteúdo público em cache de borda e a versão confirmada | 600 |
| volume `enois-node` | workspace do runtime no container | — |

**Quem é o dono do estado.** O container roda como `10001:10001` (o Dockerfile),
e o `install.sh` roda como o participante (uid 1000 num host comum). Até o
`--start` os arquivos ficam `600` do participante, porque o enroll pode acontecer
sem docker. O `--start` é quem entrega o estado ao uid do container: `chown
10001:10001` do diretório, `750` nele e `640` nos arquivos de identidade — dono
lê e escreve, outros não leem. O `chown` precisa de privilégio; sem ele o
instalador avisa e o nó pode não conseguir ler a própria identidade.

Por que não `644`: medido no ensaio (container `10001`, diretório montado que o
Docker registra como `1000:1000`, kernel com `fs.protected_regular=2`). O uid
10001 lia um arquivo `644` de outro dono numa hierarquia montada, e recebia
`Permission denied` em `node.json` (`600`) — o nó subia, não achava a identidade
e reiniciava. Depois da entrega ao uid do container o arquivo é **do próprio nó**
(`640`, dono 10001), e a leitura não depende de quem o Docker diz que é o dono do
diretório.

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

## 6. Atualização e rollback (código verificado, imagem construída aqui)

Trocar a imagem do runtime não é `docker pull`: é substituir o que roda no nó.
A imagem é **construída na máquina do participante** a partir do código
verificado — não baixada de um registry — e a verificação é sobre o **código**,
que é o que a rede executa:

- **release oficial**: o bootstrap baixa `enois-node-runtime-<tag>.tar.gz` e o
  `SHA256SUMS` do GitHub Releases e roda `sha256sum -c` **contra o arquivo
  baixado**, na mesma pasta — soma que aponta para outro arquivo é recusada pelo
  próprio `sha256sum -c`, não aceita por comparação de string. O `VERSION`
  dentro do pacote tem de ser `<fonte>@<sha8>`; o que não tem esse formato é
  recusado mesmo com o sha conferindo;
- **git na fonte de verdade**: sem release publicado, o bootstrap faz checkout
  de `origin/<fonte>` e a versão é `git rev-parse --short=8` do checkout.

```bash
./bootstrap.sh --update                    # release/git novo → constrói → troca → sobe
./bootstrap.sh --update --source main      # muda a fonte de verdade E atualiza
./bootstrap.sh --rollback                  # volta à versão anterior
./install.sh --update                      # mesmo caminho (delega ao bootstrap)
```

O fluxo é fail-secure: download que falha, sha que não confere, `VERSION`
inválido ou build que quebra abortam **antes** de gravar qualquer estado — o
runtime atual continua rodando. Só depois do build pronto o bootstrap grava
`runtime-version` (o anterior vai para `runtime-version.previous`) e chama o
`install.sh --start` com `NODE_RUNTIME_TAG=<versão>` (`@` vira `-` na tag, que
não aceita `@`): o restart sobe exatamente a imagem construída do commit
verificado, não uma tag móvel de registry.

O `--rollback` exige que a imagem da versão anterior **ainda exista nesta
máquina** (`docker image inspect`): "voltar" sem a imagem seria construir de
novo exatamente o que se quis evitar. Ele troca versão, guarda a atual como novo
anterior e **não troca a fonte de verdade** — fonte é escolha do participante,
não efeito colateral do rollback. O `install.sh --update/--rollback` direto
delega ao bootstrap: manter dois caminhos de atualização seria ter duas regras
sobre o que o nó executa.

Comportamento fixado em `scripts/tests/node-runtime-test.sh` (79 asserções):
fonte fora da lista fechada recusa antes de rede; release com sha divergente é
recusado e o runtime atual sobrevive; `SHA256SUMS` sem a linha do asset recusa;
`VERSION` fora do formato recusa; sem release cai para o git; mesma versão não
refaz nada; update preserva o anterior em `.previous`; rollback sem anterior
recusa; rollback sem a imagem anterior nesta máquina recusa; rollback não troca
a fonte.

## 7. O que ainda não está aqui

- **Executores de trabalho real por perfil**: cinco já executam de verdade no nó —
  `MEDIA_TRANSCODE` (receita do Control Plane, resultado verificado por tamanho e
  hash), `READ_QUERY` (travessia do catálogo da réplica com corpo e confirmação),
  `EDGE_CACHE` (consumo do feed de mudanças com corpo baixado e confirmado),
  `EDGE_DELIVER` (revalidação da entrada e veredicto de entrega conferido contra
  o snapshot do nó) e `API_REQUEST` (chamada à rota da lista **assinada**
  `allowedRoutes`, com o destino fora do payload e o resultado como evidência),
  189 asserções em `scripts/tests/node-executor-test.sh` (12 asserções são do executor `OBJECT_PUT` do Storage, ver 13-storage-fase-3 §5.3).
  O **produtor** de `API_REQUEST` no lado do Control Plane também existe:
  `POST /admin/network/nodes/{nodeId}/api-request?route=<nome>` enfileira a task
  com alvo, conferindo o nome contra a mesma lista assinada que o nó recebe
  (`NetworkConfigService.allowedRouteNames()`), de modo que nenhuma task nasce
  fadada a `API_ROUTE_NOT_ALLOWED`.
- **Banda compartilhada entre execuções concorrentes**: o teto declarado
  (`limits.bandwidthMbps`) já vale como `curl --limit-rate` em cada transferência de
  corpo do runtime. Hoje o runtime executa uma tarefa por ciclo, então limitar cada
  transferência limita o nó; se o ciclo passar a executar em paralelo, o limite tem
  de virar taxa compartilhada — senão N tarefas concorrentes multiplicariam a banda
  que o participante cedeu por N.
- **Cache do pacote no bootstrap**: hoje cada `--update` baixa o tar.gz de
  novo; um cache local do último release verificado reduziria o tráfego com o
  GitHub em updates frequentes.
