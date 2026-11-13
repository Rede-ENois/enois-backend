# Marketplace: cotação e isolamento do fluxo

`MarketplaceFlowTests` verifica o fluxo HTTP real de tema, anúncio, autocompra recusada,
retenção, confirmação e reembolso. As regras financeiras e de segurança de produção não mudam.

## Valores exatos

Valores em milésimos de faísca (mf). O preço é 50.000, o repasse é 45.000, a taxa é 5.000 e o
reembolso é integral. O airdrop de 100.000 não é escalado; a presença depende da cotação vigente.

| Cotação (micro-reais) | Presença | Carteira inicial | Após compra | Autor após repasse | Segunda retenção | Após reembolso |
| --- | --- | --- | --- | --- | --- | --- |
| 10.000 | 5.000 | 105.000 | 55.000 | 150.000 | 5.000 | 55.000 |
| 9.900 | 5.051 | 105.051 | 55.051 | 150.051 | 5.051 | 55.051 |

Os esperados são explícitos, sem reutilizar o calculador verificado nem tolerância numérica.
Compra, confirmação e reembolso são repetidos com as mesmas chaves para provar ausência de novos
movimentos. O replay da compra reembolsada mantém a resposta original, não seu estado financeiro.

## Fronteiras e fixtures

- Não há `@Transactional` envolvendo o teste. Cada HTTP fecha as transações normais; uma guarda
  exige ausência de transação de teste. Leituras JDBC posteriores conferem preço, estado persistido,
  saldo e soma do ledger das contas próprias, sem usar `flush()` para simular persistência.
- Nas contas compartilhadas, as asserções selecionam referências da retenção do comprador/anúncio
  e `mcmp:<purchaseId>` da decisão. Conferem quantidade e valor exatos, não snapshots globais.
- O relógio local avança além da última cotação, último lançamento e valor anterior, ao meio-dia
  do próximo dia. Avança um milissegundo por leitura, mantendo cada cenário no mesmo dia.
- O scheduler e o SMTP são dublês exclusivos deste contexto. Os serviços financeiros, autenticação
  por `TestAuthHelper` e controles de sessão continuam reais. Outros contextos não são desativados.
- `GET /carteira` credita a presença de forma síncrona. Leituras repetidas exigem uma única recompensa.
- A limpeza tem transação própria: remove somente a cotação publicada e oculta os temas/anúncios
  dos proprietários exclusivos. Contas, compras decididas, idempotência e ledger permanecem coerentes;
  saldos, versões e lançamentos não são apagados nem restaurados. A limpeza é lida após o commit.

## Executar e provar a regressão

```bash
./mvnw -B -DskipTests compile
./mvnw -B -Dtest=MarketplaceFlowTests test
./mvnw -B '-Dtest=Marketplace*Tests,CoinRewardScalingTests,CoinFlowTests,StreakPresenceFlowTests' \
  -Dsurefire.runOrder=alphabetical test
```

Repita os focados com `reversealphabetical`. Para a contraprova, inverta temporariamente o setup
entre 10.000 e 9.900 sem mudar parâmetros/asserções: os dois casos devem falhar na carteira inicial.
Reintroduzir uma transação envolvente deve falhar na guarda antes dos HTTPs. Restaure ambos antes
da validação final. A suíte completa e a integração com outros módulos são gates separados.
