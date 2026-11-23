# Papo Reto: como um assunto entra e como ele sai

O Papo Reto mostra as conversas da comunidade, não as palavras mais repetidas. Este documento é a
régua: o que o corte faz, o que a leitura julga e por que cada número do painel é o que é.

Código: `paporeto` (application / domain / adapters) — `PapoAssuntoExtractor`, `PapoRetoWorker`,
`PapoAssunto`, `PapoRetoService`.

## O caminho de uma publicação até a lista

1. **Coleta (corte de 5 minutos)** — `PapoRetoWorker` lê das fontes (`PapoPublicacaoReader`: posts,
   comentários, desabafos, confissões) só o que foi criado desde a última fronteira (`lida_em`).
2. **Extração** — `PapoAssuntoExtractor` limpa o texto, dobra (NFD, sem acento, minúsculo),
   tokeniza, descarta palavra vazia e monta as expressões (unigramas + pares).
3. **Cluster** — dentro de um mesmo texto, a expressão composta cobre as suas palavras: se
   "inteligencia artificial" aparece junto, ela é o assunto, e "inteligencia" e "artificial" não
   viram assuntos separados. Tags do autor entram como assunto declarado.
4. **Fala** — cada assunto vira uma linha em `papo_falas` (bucket da hora, expressão, fonte, autor,
   comunidade, publicação). A dedup é por publicação + expressão: reler não conta duas vezes.
5. **Agregação (corte)** — por escopo (global e cada comunidade com fala nova), as falas da janela
   de 7 dias são reagrupadas por assunto e o agregado é reescrito em `papo_assuntos`; a timeline
   (`papo_intervalos`) é reconciliada bucket a bucket.
6. **Leitura (página)** — `PapoRetoService` lê o agregado já pronto: nenhuma publicação é
   processada no carregamento da página.

## Extração: o que pode nomear um assunto

Uma palavra só entra se passar por todas as peneiras:

| Peneira | Regra |
| --- | --- |
| Limpeza | link, e-mail, menção, emote, HTML e domínio saem inteiros antes da tokenização |
| Tamanho | 3 a 59 letras/números |
| Número puro | `2026` não é assunto |
| Riso e tecla presa | `kkkk`, `hahaha`, `assssss`, `kkjkkj` |
| Palavra vazia | lista fechada em `PALAVRAS_VAZIAS` (artigos, preposições, pronomes, verbos de ligação, contrações, fala da rede: `gente`, `tipo`, `coisa`, `mundo`, `enois`, `sei`, `tenho`) |

**Expressões com contexto.** Duas palavras elegíveis formam par quando são **vizinhas**
(`inteligencia artificial`) ou quando há **um conector** entre elas (`mercado de trabalho`,
`meetup na sexta`, `aula de violao`, `mutirao do bairro`, `palestra sobre rust`). Os conectores
são: `de, da, do, das, dos, na, no, nas, nos, pra, pro, para, sobre`. "Com", "que" e a pontuação
quebram o par (`praia com o cachorro` são duas palavras, não uma expressão), e um verbo de intenção
ao lado de um conector não forma par (`vou pra casa`, `quero na sexta` são frases, não conversas).

O pareamento é guloso, da esquerda para a direita, e **não sobrepõe**: "meetup de rust na sexta"
vira `meetup de rust` + `sexta`.

**A palavra do par não sai solta.** O assunto é o cluster já decidido na extração: a palavra que
entrou num par fica coberta por ele e não vira assunto próprio — a lista mostra `mercado de
trabalho`, e não `mercado`, `trabalho` e `mercado de trabalho`. Só as palavras que não couberam em
par nenhum entram sozinhas.

## Pontuação: dois fatores, e os dois são necessários

```
pontuação = potência × discriminação
```

### Potência — o quanto a conversa está viva agora

```
potência = fontesDecaídas + √(pessoas) × pesoPessoas + √(falas da última hora) × pesoCrescimento
```

- **fontesDecaídas**: cada fala vale o peso da sua fonte (30% post, 25% comentário — configurável)
  multiplicado por `0,5^(idade em horas / 24)`. Uma fala de 24 h atrás vale metade; uma de 48 h,
  um quarto. Como o decaimento é **fala a fala**, o assunto velho cai sozinho e uma fala nova não
  ressuscita as quarenta de anteontem.
- **Teto por autor**: cada pessoa soma no máximo **3 falas** do mesmo assunto, nas fontes e no
  crescimento. Quem publica vinte vezes a mesma coisa soma três.
- **pessoas**: pessoas distintas na janela (contagem honesta, sem teto) — é o termo de diversidade.
- **crescimento**: falas da última hora, também com o teto por autor.

Pesos em `rede.paporeto.peso-posts`, `peso-comentarios`, `peso-pessoas`, `peso-crescimento`.

### Discriminação — o quanto o assunto é da conversa, e não da língua

Frequência bruta não distingue "gente" de "seguranca digital": as duas aparecem muito. O corte grava
duas contagens por assunto — `publicacoes` (publicações distintas que falaram do assunto) e
`janelaPublicacoes` (publicações distintas com alguma fala no escopo, na janela). A fração é o sinal:

| Fração | Palavra de uma palavra | Expressão composta |
| --- | --- | --- |
| ≥ 15% da janela | **onipresente: sai do ranking** (fator 0) | fica com o piso **0,5** |
| < 15% | bônus de raridade: `min(2,0 ; 1 + ln(0,15 / fração))` | o mesmo bônus |

A expressão composta nunca cai na peneira de onipresença: ela é específica por natureza, e conversa
grande e legítima (um assunto que tomou a rede) não desaparece por ser grande — ela perde só o bônus
de raridade. A régua só julga com **20 publicações ou mais** na janela do escopo: em amostra pequena
a fração é ruído e o assunto vale pelo que é (fator 1).

### Trajetória: status e crescimento são medidos, não deduzidos

O corte grava também a **potência de 24 horas antes**, avaliada nas falas que já existiam então (com
o decaimento avaliado naquele instante). Dessa comparação saem:

| Status | Leitura (`PapoStatus.de`) |
| --- | --- |
| `EMERGENTE` | antes tinha no máximo 30% do que tem agora |
| `PERSISTENTE` | antes já tinha ao menos 80% do que tem agora |
| `HOT` | entre os dois: esquentou de verdade |

**Crescimento** na tela = `(potência agora − potência antes) / potência antes × 100`, mínimo 0.

A **ordem** da lista sai da pontuação (potência já ajustada pela discriminação); o **status** sai da
potência crua das duas pontas. São perguntas diferentes — "o que é relevante agora" e "como isso
está se movendo" — e por isso os números não se misturam.

## Chão de entrada

Independente de tudo: o assunto precisa de **pessoas distintas** na janela. Repetição de uma conta
só nunca chega à lista — o chão nunca desce abaixo de **2 pessoas**.

O chão é **adaptativo** e acompanha o tamanho da base do escopo (`chaoDePessoas` em `PapoAssunto`):

| Publicações distintas no escopo (na janela) | Chão | Leitura |
| --- | --- | --- |
| 20 ou mais | **5 pessoas** | base com gente de sobra: cinco separam a conversa da repetição |
| menos de 20 | **2 pessoas** | rede pequena: dois já estão conversando; o assunto aparece **marcado como amostragem menor** |

O assunto marcado como amostragem menor (`amostragemMenor` na resposta do ranking) leva o tamanho da
amostra para a tela: junto vão `pessoas` (quem falou) e `janelaPublicacoes` (o tamanho da base). A régua é a mesma massa da discriminação (`MINIMO_PUBLICACOES_REGUA`): abaixo dela, nem a
fração de publicações nem o chão cheio julgam — e a tela diz que a amostra é pequena em vez de
esconder a conversa.

Regras extras da leitura: o assunto passa pela mesma régua do extrator (`assuntoValido`), então
ruído antigo gravado antes de uma correção (`http`, `kkkk`, resto de URL) sai da tela sem esperar a
janela expirar.

## Linha gravada antes desta régua

O agregado guarda o estado como JSON em `papo_assuntos.estado`. Linha gravada antes desta mudança
não tem `potencia`: ela é marcada como **legado** e pontuada pela fórmula antiga até o primeiro corte
reescrevê-la (minutos). Por isso não houve migração de banco — a coluna é a mesma, o JSON é que
carrega mais campos.

## Manutenção

- **Palavra nova na lista de vazias**: acrescente em `PALAVRAS_VAZIAS` (dobrada, sem acento) com o
  motivo no grupo certo. A lista é a primeira peneira, não a única: palavra de conversa que escapar
  dela cai na régua de publicações distintas.
- **Conector novo**: `CONECTORES` no extrator. Cuidado: conector aceito aqui cola palavras —
  "trabalho de equipe" e "meetup na sexta" são expressão, "post com foto" não é. Conector curto
  ("na", "de") cola o que vem logo depois dele; verbo de intenção ao lado de conector (`VERBOS`)
  não forma par.
- **Limiar, teto ou piso**: constantes em `PapoAssunto` (`LIMIAR_ONIPRESENTE`,
  `TETO_FALAS_POR_AUTOR`, `TETO_DISCRIMINACAO`, `PISO_ONIPRESENTE_COMPOSTO`,
  `MINIMO_PUBLICACOES_REGUA`, `MINIMO_PESSOAS`, `MINIMO_PESSOAS_BASE_MENOR`, `BASE_PARA_CHAO_CHEIO`,
  `MEIA_VIDA`).
- **Testes**: `PapoAssuntoExtractorTests` (extração), `PapoAssuntoTests` (domínio),
  `PapoRetoWorkerTests` (corte), `PapoRetoServiceTests` (leitura), `PapoRetoQualidadeTests` (corpus
  sintético ponta a ponta, com o comparativo da régua anterior).
