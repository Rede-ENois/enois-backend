# Énóis Storage — fase 4: domínios de falha e equilíbrio da distribuição

Documento do que **existe** no código. Complementa
[`13-storage-fase-3-replicacao.md`](13-storage-fase-3-replicacao.md) (réplica, digest, varredura),
que continua valendo: nada da fase 3 foi reescrito.

A fase 4 do brief pede Placement Engine, rebalanceamento, storage classes, distribuição geográfica
e erasure coding. Esta rodada entrega **a parte do Placement Engine que faltava e a medição que
precede o rebalanceamento** — e diz explicitamente o que não existe.

## 1. Domínios de falha: a região entra na escolha

Até aqui a colocação sabia **não repetir o nó** ("duas cópias no mesmo disco não sobrevivem à perda
do disco"). Duas cópias em nós diferentes da **mesma região** caíam juntas quando a região caía: o
mesmo erro, um nível acima.

Agora `StoragePlacementService` decide em **duas passadas sobre a mesma ordem** (mais folga
primeiro):

1. uma cópia por região ainda não usada — inclusive as regiões que **já têm** cópia confirmada;
2. esgotadas as regiões distintas, completa com as repetidas.

| situação | resultado | por quê |
|---|---|---|
| há região distinta livre | vai para ela, mesmo com menos disco | diversidade vale mais que folga |
| não há região distinta | completa na repetida | segunda cópia na mesma região ainda é melhor que nenhuma |
| nó sem região declarada | domínio **desconhecido** | não é igual à região de outro, nem à de outro sem região |

A comparação usa `NetworkRegions.normalize` — a mesma de evacuação e afinidade. `"Sudeste"`,
`"sudeste"` e `"SUDESTE"` são uma região só.

**O que o domínio NÃO é.** Host físico, operadora e rack não estão no cadastro. Derivá-los (do
`display_name`, do IP, do dono) seria deduzir topologia a partir de texto livre — e um domínio errado
é pior que nenhum, porque faz o sistema acreditar em uma redundância que não existe.

## 2. Equilíbrio: medir antes de mover

`GET /admin/network/nodes/storage/balance` (admin) — `StorageBalanceReport`:

| campo | significado |
|---|---|
| `nodes[].confirmedCopies` | cópias **confirmadas** (`OK`) no nó |
| `nodes[].copiesSharePercent` | participação do nó nas cópias totais |
| `nodes[].diskFreeMb` | disco livre **medido** (`null` = não medido) |
| `nodes[].capacitySharePercent` | participação do nó na capacidade pronta |
| `nodes[].skewPp` | cópias − capacidade, em pontos percentuais |
| `skewed` | nós com desvio acima de **15 p.p.** |
| `measurable` | `false` quando não há régua (nenhum disco medido, ou nenhuma cópia) |

**A régua é a folga medida, não o declarado.** Um nó que declarou 1 TB e tem 10 GB livres é
justamente quem não deve receber mais — é a mesma regra do `store` (507 `STORAGE_NO_DISK_SPACE`).

**Só cópia `OK` conta.** Designada e não confirmada não ocupa disco; contá-la mandaria mover dados
para aliviar um nó que não está cheio.

**Sem medida, `measurable=false`.** `diskFreeMb`, `capacitySharePercent` e `skewPp` ficam **nulos**
(nunca zero: zero diria "sem espaço", o oposto de não sabido), o nó continua no relatório com as
cópias que tem, e `skewed` fica vazio — dizer "equilibrado" seria afirmar o que não foi medido.

**Não move nada.** Mover cópias é transferência pela rede de quem participa; a decisão depende de
banda, janela e prioridade (brief §16), que são escolha humana. O relatório entrega o "se" e o
"quem"; o "quando" e o "quanto" não foram implementados.

## 2.1 A colocação decide pela folga **cedida** (§8)

Até a fase 4, `StoragePlacementService` ordenava e filtrava candidato pelo **disco livre** da
máquina. Para o participante do §7 (2 TB de volume, 1,5 TB cedidos), o livre nunca chega a barrar:
com 1,5 TB ocupados o disco ainda mede 500 GB, a colocação continua designando cópia, e o Énóis
passa a ocupar mais do que a pessoa autorizou — o §8 pede "capacidade real dos participantes".

Agora a folga é **cedida**: `declarado − ocupado`, com `ocupado = max(cópias OK somadas, uso medido
pelo nó)`. O disco livre continua sendo régua, junto: ceder 50 GB num disco com 500 MB não é espaço.

| situação | colocação | `store` |
|---|---|---|
| folga cedida ≥ piso (1 GiB) + a cópia | designa | grava |
| folga cedida < piso | não designa | **507 `STORAGE_NO_CEDENT_SPACE`** com cedido, ocupado e piso |
| disco livre < piso | não designa | 507 `STORAGE_NO_DISK_SPACE` |
| declarado ausente (teto `0`) | **não designa** | 507 `STORAGE_NO_CEDENT_SPACE` |

O último caso é a mudança de contrato desta rodada: antes, "não medir não é saber que não cabe"
mandava ser permissivo. A medida que faltava não era o disco — era o número que só o participante
pode dar, e nó sem teto recebia cópia sem limite nenhum, contra o §7 ("não permitir que ...
consuma além do limite"). Nó de storage precisa de `declared_disk_mb` no enrollment.

`ocupado` usa o **maior** entre o confirmado no catálogo e o uso que o nó mediu em si (V344): os
dois são limites de baixo do uso real, e o menor esconderia o espaço que um deles já viu ocupado
(cópia interrompida deixa arquivo no volume sem estar confirmada).

## 3. O que NÃO está entregue

| item | por quê |
|---|---|
| mover cópias de fato (rebalanceamento ativo) | exige o corpo — hoje o Control Plane serve por digest e o nó busca; mover exige banda controlada, pausa/retomada e janela. Projeto próprio |
| host físico / operadora / rack como domínio | não estão no cadastro; derivar seria deduzir |
| storage classes (hot/cold, custo, durabilidade por classe) | o catálogo não tem classe de objeto; acrescentar exige decisão de produto (quem escolhe a classe de um objeto novo?) |
| erasure coding | muda o modelo de bytes+sha256 por objeto; hoje a base é réplica inteira |
| escolha por região na **tarefa** | já existe para despacho (`NetworkRegions`); no Storage só entrou na colocação |
| velocidade de leitura/escrita e IOPS estimados (§7) | **não se estimam.** O brief é explícito no §8.3: indicadores refletem o coletado, nunca o estimado. Medir de verdade exige benchmark no disco do participante — que é carga de escrita num nó de terceiro — e o benchmark precisa de desenho (tamanho, duração, horário, quando repetir). Decisão de produto/infra |
| estado dos discos (§7, SMART) | o runtime roda em contêiner sem acesso ao dispositivo; `smartctl` na máquina de um voluntário é requisição de confiança que hoje a rede não tem motivo para fazer. Decisão de produto |
| disponibilidade histórica (§7) | as amostras por nó são **60 pontos de 1 minuto** (V325) e são tela, não retenção. "Disponibilidade" de verdade exige janela de dias e uma regra do que conta como "no ar" (heartbeat perdido é queda? nó em drenagem conta?). O único registro de histórico hoje é `NETWORK_NODE_RECONNECTED` na trilha de auditoria — transição, não série. Decisão de produto + retenção |

Capacidade (§7) **está entregue** por nó em `GET /admin/network/nodes/storage/overview`: `disks[]`
com `declaredMb`, `freeMb`, `totalMb`, `reservedMb`, `usedMb` e `usedPercent`.

## 4. Verificação

| verificação | resultado |
|---|---|
| `StoragePlacementServiceTests` | **13** (8 da fase 3 + 5 novos: duas réplicas na mesma região; usa a mesma quando não há outra; região não declarada ≠ declarada; não repete a região que já tem cópia; grafia não cria domínio) |
| `StorageBalanceReportTests` | **6** — desequilíbrio apontado; proporcional não aponta ninguém; `STALE` não conta; sem medida não afirma nada; nó sem medida fica com capacidade nula; sem nós não inventa base |
| `NetworkStorageBalanceTests` | **3** — rota exige admin; sem nó não inventa base; disco não medido chega nulo (não zero) |

Mutações conferidas:

| mutação | derruba |
|---|---|
| diversidade de região desligada (uma passada) | `naoDesignaDuasReplicasNaMesmaRegiao`, `naoRepeteARegiaoQueJaTemCopia` |
| sem a 2ª passada (só região distinta) | 6 testes de colocação |
| contar colocação `STALE` | `copiaNaoConfirmadaNaoContaNoRelatorio` |
| disco não medido virar `0` | 2 erros nos testes do relatório |
| usar `declared_disk_mb` em vez do medido | 3 testes do relatório |

Nenhuma migração nova: a fase 4 usa o catálogo e as colocações que a fase 1/3 já tinham.

## 5. Defeitos encontrados no caminho

- **A região ocupada nunca era lida.** O filtro de "nós que já têm cópia" rodava antes de extrair a
  região deles, então `takenRegions` ficava vazio e a segunda cópia caía na mesma região — o teste
  `naoRepeteARegiaoQueJaTemCopia` pegou.
- **`-1` como "não medido" no relatório.** A primeira versão devolvia `-1` para disco/capacidade não
  medidos; isso ordena o nó como se estivesse cheio e é justamente o `null ≠ 0` que o projeto trata
  como regra. Trocado por nulo, com o nó indo para o fim da lista, não para o topo.
- **A rota colidia com `/{id}`.** A classe é mapeada em `/admin/network/nodes`, então
  `/storage/balance` ficou sob `/nodes/storage/balance`; o teste de autorização viu `200` em vez de
  `401` porque `/{id}` respondia.
## 6. Tráfego e disponibilidade do nó (brief §18, V345)

O §18 pede que o participante **consulte o tráfego** e a **disponibilidade** da máquina que cede.
Os dois números têm origens diferentes, e misturá-los seria inventar um deles.

**Tráfego: medido pelo nó.** `bytesInMb`/`bytesOutMb` vêm no heartbeat, acumulados nas interfaces do
próprio nó (V345). O Control Plane só os guarda — contar bytes servidos do lado dele mediria o que o
Control Plane transfere, não o que a máquina do participante consome da banda dele, e é a banda dele
que o §18 quer mostrar. Nulo é "não mediu" (runtime anterior); zero é "medido, nada transferido".
A proporção de saída (`outboundSharePercent`) é calculada aqui, nunca enviada pelo nó — senão um nó
poderia dizer 90% de saída com 1 GB contra 10 GB de entrada.

**Disponibilidade: medida pela rede.** A rede registra *transições*, não cada heartbeat (uma linha
por ciclo por nó afogaria a trilha — ver `registrarReconexao`). O que ela consegue afirmar é o que a
trilha prova: quantas vezes o nó voltou de uma ausência (`outages`, contados em
`NETWORK_NODE_RECONNECTED`) e por quanto tempo ficou fora (`offlineSeconds`, somado dos
`offlineForSeconds` de cada linha).

Por isso `offlineSeconds` é acompanhado de dois campos:

| campo | o que diz |
|---|---|
| `measuredOutages` | quantas das quedas entraram na soma |
| `availabilityComplete` | `false` quando a soma é sobre **menos** quedas do que existem — a amostra de 200 linhas parou antes do fim, ou a linha não tinha o intervalo |

`availabilityComplete=false` quer dizer que `offlineSeconds` é um **piso**, não o tempo fora real. A
tela que apresentar o número como total estaria dizendo que o nó foi mais disponível do que foi.

Linha de reconexão sem `offlineForSeconds` conta como queda — ela prova que houve uma — e não entra
na soma: somar zero diria "voltou no mesmo instante", que é outra afirmação. Pelo mesmo motivo ela
não vira `lastOfflineSeconds`.

`online` usa a mesma janela de "vivo" do painel (`rede.network.node-stale-seconds`, 180 s): um nó
"online" na tela do participante e "fora" no painel seriam dois números para o mesmo fato.

**O que não existe.** Disponibilidade ao longo de um período (servido/parado por faixa de tempo),
latência de tráfego, IOPS e velocidade — dependem de série histórica que o módulo não guarda além da
amostra de observabilidade do último minuto, e de decisão de produto (§7 do brief).

## 7. O participante ajusta o que cede (brief §18, sem migração)

O §18 lista "definir capacidade máxima" e "definir limites de banda" entre o que o **participante**
faz. Até aqui só o admin conseguia (`POST /admin/network/nodes/{id}/capacity`): o participante
precisava de um terceiro para emprestar mais — ou menos — da própria máquina.

`POST /network/participant/nodes/{id}/capacity` fecha isso.

| aspecto | o que vale |
|---|---|
| escrita | a **mesma** do admin (`NetworkNodeService.capacity`) — não existe caminho paralelo de configuração |
| efeito | soma 1 em `configVersion`; o runtime aplica na próxima leitura da config assinada |
| campo ausente | mantém o valor atual |
| negativo | `400 NODE_CAPACITY_INVALID` |
| perfil | **não** muda por esta rota — perfil é permissão de tipo de tarefa e continua decisão administrativa |
| dono | conferido no **serviço** (404, nunca 403) |
| auditoria | `NODE_CAPACITY_CHANGED_BY_PARTICIPANT`, com os quatro limites no metadado |

O participante pode **subir e descer** os próprios limites. Um teto que só o admin sobe faria o
participante pedir licença para emprestar mais da própria máquina, e o brief diz que ele "deverá
conseguir controlar a utilização de sua infraestrutura". O que separa os dois atores é o perfil:
tirar ou dar perfil muda **quais tarefas** o nó pode receber, e isso é aprovação humana.

## 8. Tipos de storage node (brief §6, V348)

O §6 pede seis perfis especializados — `STORAGE_NODE_STANDARD`, `_MEDIA`, `_ARCHIVE`, `_EDGE`,
`_REPLICA`, `_HOME` — e diz que "um mesmo servidor poderá disponibilizar múltiplos perfis,
respeitando os limites e permissões autorizados". Antes desta rodada havia um só: `NODE_STORAGE`.

### 8.1 Perfis

`NodeProfile` ganhou os seis nomes do contrato. `NODE_STORAGE` continua existindo e é o tipo
**STANDARD**: todo nó cadastrado antes tem só ele, e nada muda para ele.

| perfil | `storageKind()` | para que serve |
|---|---|---|
| `NODE_STORAGE` | `STANDARD` | convencional, leitura e escrita, qualquer objeto |
| `NODE_STORAGE_STANDARD` | `STANDARD` | mesmo perfil, com o nome do brief |
| `NODE_STORAGE_MEDIA` | `MEDIA` | imagens, vídeos e miniaturas |
| `NODE_STORAGE_ARCHIVE` | `ARCHIVE` | longo prazo: capacidade e custo |
| `NODE_STORAGE_EDGE` | `EDGE` | perto dos usuários: leitura e distribuição |
| `NODE_STORAGE_REPLICA` | `REPLICA` | mantém cópias redundantes |
| `NODE_STORAGE_HOME` | `HOME` | servidor doméstico, disponibilidade variável, NAT |

**Permissão**: `OBJECT_PUT` e `OBJECT_GET` são autorizados por **qualquer** perfil com
`storageKind()` não nulo — a especialização é aditiva. Quem tem só `NODE_STORAGE` continua
recebendo tudo. Perfil sem `storageKind()` (`NODE_WORKER`, `NODE_EDGE`…) continua sem autorizar
armazenamento, e nenhum perfil de storage autoriza tarefa que não seja de armazenamento.

### 8.2 Preferência de colocação — preferência, nunca filtro

A ordem de candidatos passou a ter três critérios, nesta prioridade:

1. **o tipo do objeto**: nó que declara o tipo preferido vem antes;
2. **folga cedida** (`declarado − ocupado`), a mesma régua do §2.1;
3. `id`, para a ordem ser estável.

A classe do objeto é derivada do que o catálogo já sabe (`ObjectClass`), sem coluna nova:

| classe | como é decidida | tipo preferido |
|---|---|---|
| `MEDIA` | `contentType` começa com `image/`, `video/` ou `audio/` | `MEDIA` |
| `COLD` | `createdAt` mais velho que `rede.storage.cold-after-days` (padrão 30) | `ARCHIVE` |
| `DEFAULT` | o resto, e objeto fora do catálogo | `STANDARD` |

**"Frio" é piso, não verdade**: o catálogo não guarda último acesso, então a idade é medida desde
`createdAt`. Um objeto criado há 60 dias pode ter sido lido ontem — por isso a colocação
**prefere** o arquivo e não exige.

Sem nó do tipo preferido, qualquer nó de armazenamento recebe: exigir o tipo deixaria objeto sem
réplica por falta de um perfil que é declarativo, e a durabilidade do §9 cairia por topologia.
Nó com vários tipos casa com qualquer um deles.

### 8.3 Banda por tipo

| aspecto | o que vale |
|---|---|
| escrita | `POST /network/participant/nodes/{id}/storage-bandwidth/{kind}` com `{"mbps":15}` |
| remoção | `mbps` nulo; o tipo volta a usar a banda geral do nó |
| tipo não declarado pelo nó | `409 STORAGE_KIND_NOT_DECLARED` |
| tipo desconhecido | `400 STORAGE_KIND_INVALID` |
| negativo | `400 NODE_CAPACITY_INVALID` |
| efeito | soma 1 em `configVersion` (`NetworkNodeRepository.touchConfig`, que **não** reescreve os limites) |
| persistência | `network_node_storage_bandwidth`, uma linha por (nó, tipo) — V348 |
| ausência | o tipo usa `limits.bandwidthMbps` do nó; **nunca zero** |
| auditoria | `NODE_STORAGE_BANDWIDTH_CHANGED` / `_REMOVED` |

### 8.4 Formato 7 da config assinada

`FORMAT_VERSION = 7`. O campo novo vai **no fim** da linha canônica:

```
...|diskFreeMb|diskTotalMb|["ARCHIVE=5","MEDIA=15"]
```

Lista ordenada por nome de tipo, no formato `TIPO=mbps`. Config de formato ≤ 6 continua
verificável: `canonical()` **omite** a lista quando `formatVersion <= 6`, então a assinatura de um
documento antigo não muda. O runtime (`deploy/node-runtime/node-runtime.sh`) reconstrói a linha do
mesmo jeito e só acrescenta a lista quando o formato é ≥ 7.

Na transferência, o runtime aplica o teto **do tipo** sobre `limits.bandwidthMbps` (o tipo reservou
aquela banda; aplicar a geral seria ceder mais do que o participante aceitou). Nó com vários tipos
usa o **menor** teto entre eles. Harness: `scripts/tests/node-executor-test.sh`, cenário 25b —
inclui o caso de lista adulterada depois de assinada, que tem de ser recusada.

### 8.5 O que NÃO está entregue

- **`HOME` é declarativo, sem regra própria.** Um nó doméstico não recebe tratamento de
  durabilidade diferente: a colocação o trata como qualquer storage, e a conta de domínio de falha
  do §1 não o distingue. O perfil serve para o participante declarar a natureza da máquina e para
  o painel mostrá-la. Diferenciar (nunca ser única cópia de um domínio, menos réplicas, peso menor)
  é decisão de produto e não foi tomada.
- **`EDGE` e `REPLICA` não mudaram nada no comportamento.** Não há objeto cujo tipo preferido seja
  `EDGE` ou `REPLICA`: nó desses tipos recebe por folga, como o STANDARD. O brief descreve o que
  eles *priorizam*, e não há ainda medida (latência por usuário, papel de redundância) que decida a
  preferência.
- **CDC do Cooperative (§4)** continua bloqueado por decisão de infra.
- **Sem IOPS, SMART ou disponibilidade por faixa** (§7): o que existe é disco livre, total, uso de
  conteúdo e tráfego medidos pelo nó.

## 9. IP de origem e mudança de endereço (brief §5, V350)

O §5 lista o que o participante doméstico **não** deve precisar fazer: contratar IP público,
configurar DNS, abrir portas, informar IP no painel. A consequência é que a rede não deve
**perguntar** o endereço — ela o **observa** na borda do heartbeat.

| coluna | o que é |
|---|---|
| `observed_ip` | último endereço de origem visto; nulo é "não medido" |
| `observed_ip_changed_at` | instante da última **mudança**; nulo é "nunca mudou desde o primeiro contato" |

Regras, todas provadas por teste:

1. **Primeiro contato não é mudança** — grava o IP e deixa o instante nulo (houve conhecimento,
   não troca).
2. **O mesmo IP não move o instante** — nó doméstico bate coração a cada ciclo; reescrever a marca
   a cada vez faria o painel dizer "mudou há 15 segundos" para sempre. Mesmo princípio da
   reconexão (§8.6): registra-se a **transição**, não a amostra.
3. **`X-Forwarded-For` vence o socket**, e o primeiro item da lista é o cliente: atrás do proxy, o
   socket fala com o proxy, não com o nó.
4. Sem nenhum dos dois, devolve nulo e **nada é gravado** — nunca um endereço inventado.

O IP entra pelo **transporte**, não pelo corpo: um campo no JSON seria o nó afirmando o próprio
endereço, o oposto de medir.

### O que NÃO está entregue

- **Reverse tunnel**: o brief manda "reutilizar o mecanismo de Reverse Tunnel existente", e ele
  **não existe**. O modelo é pull curto (`GET /network/tasks/next` a cada `NODE_POLL_SECONDS`).
  Isso já atende NAT (o nó só faz saída), mas não permite ao Control Plane **empurrar** trabalho.
- **Multiplexação, renovação de credenciais, transferências retomáveis, recuperação de sessões**:
  inexistentes. `NODE_POLL_SECONDS` fixo, sem backoff; a chave do nó não expira nem roda.
- A detecção de IP **registra**, não age: não há política que reaja a uma troca de endereço.
