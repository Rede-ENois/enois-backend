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
