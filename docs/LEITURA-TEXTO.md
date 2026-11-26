# Leitura de intenção de texto

O módulo `leitura/` entende o que um texto QUER dizer — o tema real, a intenção (opinião, pergunta,
anúncio, desabafo, notícia, explicação, conversa) e um resumo curto — em vez de só contar a palavra
que mais se repete. É consumido pelo **Papo Reto** (agrupa por assunto real) e pelo **Wrapped**
(assuntos do ano).

## Como funciona

1. **Inferência** (`InferenciaLeitor`): chama o endpoint de inferência do projeto (API compatível
   com OpenAI, o mesmo da moderação — `rede.leitura.llm.*`, por padrão as chaves da moderação). O
   texto vai no papel de usuário, delimitado por `<texto>`; o contrato de saída é um JSON estrito.
2. **Validação** (`LeituraTexto.validada`): nada do modelo é confiável antes de passar por aqui —
   temas aparados, sem acento, sem duplicata, no máximo 3; intenção de um enum fechado; resumo com
   teto. Resposta inválida = sem leitura.
3. **Cache** (`leitura_texto`, V381): a chave é o **hash do conteúdo** (sha-256). O mesmo texto é
   lido UMA vez; editar muda a chave e lê de novo. `lido_pelo_modelo` registra a procedência.
4. **Degradação**: sem chave, com o interruptor desligado, em timeout/erro ou com texto curto, a
   leitura é vazia e marcada como não-inferida. O Papo Reto e o Wrapped continuam funcionando com
   a extração determinística (regra de contexto) — o dado mostra que foi degradado.

## Contrato com os consumidores

- O Papo Reto usa os **temas reais** da leitura quando ela veio do modelo; senão, a extração por
  composto. Assim o ranking passa a agrupar pelo assunto do texto, não pela repetição de palavra.
- O Wrapped usa os temas reais no lugar das expressões extraídas, quando disponíveis.
- Nenhum consumidor chama a inferência no caminho da leitura da tela: leem do cache.

## Configuração

- `rede.leitura.habilitado` (`LEITURA_HABILITADO`, padrão `true`).
- `rede.leitura.llm.base-url` / `api-key` / `model` — caem para `rede.moderation.llm.*` quando
  ausentes.

## Custo e privacidade

- A inferência é paga por texto **novo**: o cache evita reler o mesmo conteúdo.
- O texto enviado é o conteúdo público da publicação; nada de texto privado, segredo ou dado
  pessoal do perfil. O modelo só devolve um palpite estruturado — o backend nunca executa nada da
  saída.

## Testes

- `LeituraDeTextoTests`: validação, intenção inválida → desconhecida, cache paga uma vez, degradação
  sem modelo, lote sem repetir texto.
- `PapoRetoWorkerTests.comLeituraDeIntencaoOsTemasReaisSubstituemAsPalavras`: o worker usa o tema
  real no lugar da palavra extraída.
