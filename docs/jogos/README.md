# Jogos diários — plataforma e entregáveis

> Proposta de implementação, não evidência de funcionalidade publicada. Os contratos abaixo devem ser reconciliados com a implementação e publicados no OpenAPI antes de distribuir trabalho que dependa deles.

## Relação com o catálogo existente e primeira entrega

O Énóis já possui o módulo de catálogo de jogos de criadores, descrito em [GAMES.md](../GAMES.md), com rotas e dados próprios. Os desafios diários são uma capacidade adicional, distinta do catálogo: não substituir seus fluxos, modelos, rotas ou moderação. Integrar a descoberta na navegação existente sem confundir jogos externos de criadores com desafios nativos.

As primeiras fatias em desenvolvimento cobrem Palavras, Labirinto e Blocos em `/daily-games/words`, `/daily-games/maze` e `/daily-games/blocks`. A disponibilidade de cada jogo depende da conclusão da integração e dos testes. Este documento descreve a visão completa, não garante que ranking entre conexões, sequência, fases, lembretes ou compartilhamento já estejam disponíveis. O contrato exato de Palavras deve ser conferido no código e no OpenAPI gerado; não presumir que a tabela abaixo já esteja implementada.

## Produto e regras comuns

Três jogos originais ([Blocos](BLOCOS.md), [Palavras](PALAVRAS.md) e [Labirinto](LABIRINTO.md)) ficam em uma área acessível a usuários autenticados. A página mostra desafio do dia, estado de cada jogo, sequência pessoal e número de conexões participantes. Partidas devem ser curtas, utilizáveis por teclado e toque e independentes da reputação profissional.

O dia é calculado em `America/Sao_Paulo`, usando o relógio do servidor. Cada jogo tem desafio diário igual para todos, identificado por data e versão imutável das regras. Criar ou recuperar uma tentativa oficial deve ser atômico por usuário, jogo e data: a primeira tentativa iniciada é oficial, inclusive se abandonada; iniciar novamente recupera a mesma partida. Tentativas adicionais são treino e não alteram ranking ou sequência. Treino e fases têm geração aleatória e progressão separada; a reprodução de um desafio usa seed e versão preservadas. Uma partida iniciada antes da virada mantém sua data; definir um prazo explícito de conclusão no contrato implementado e testá-lo.

Uma conclusão é registrada uma vez, inclusive quando o jogador perde; a sequência mede dias consecutivos com desafio concluído, separadamente por jogo. Abandonos não contam. O histórico pertence ao usuário. Ranking diário público exibe apenas posição, nome, link do perfil e resultado necessário à classificação; a visão de conexões usa relações atuais autorizadas. É necessário indicar que a lista muda quando conexões mudam e que resultados podem ser invalidados por moderação. Dados de jogadas e conteúdo de partidas não são públicos.

Resultados e posição aparecem ao concluir. Compartilhar resumo sem solução ou convidar alguém exige ação explícita; nunca publicar, enviar mensagem ou criar engajamento automaticamente. Lembretes são opt-in, desligados inicialmente, e devem respeitar preferências e canais reais disponíveis. A [política de publicações](../PUBLISHING_POLICY.md) se aplica.

## Contrato a implementar

O contrato HTTP definitivo deve seguir os padrões de autenticação, erros e paginação já existentes e especificar exemplos OpenAPI. A capacidade mínima é:

| Operação | Acesso | Resultado e regras |
| --- | --- | --- |
| Consultar jogos e desafios do dia | autenticado | estado pessoal, sequência e participantes entre conexões; não revelar resposta de palavras |
| Iniciar ou recuperar partida | autenticado | identificador, modo, data, versão, estado recuperável e desafio permitido; criação oficial idempotente |
| Registrar ação | proprietário | validar ordem e regras; retornos de palavras são avaliados no servidor; operação idempotente por identificador de ação |
| Concluir ou consultar partida | proprietário | resultado validado, posição quando elegível e resumo sem solução; conclusão idempotente |
| Consultar histórico e preferências | proprietário | histórico e opção de lembrete persistida |
| Consultar ranking | público; filtro de conexões autenticado | paginação e ordem determinística por jogo, data e versão |

Nunca confiar em pontuação, duração, solução ou estado final fornecidos pelo cliente. O servidor verifica movimentos de blocos e labirinto por replay versionado e ações válidas; para palavras valida cada palpite sem transmitir a resposta. Usar início e fim registrados no servidor para tempo competitivo, reconhecer que latência, automação e relógios tornam a prevenção de trapaça incompleta. Limitar tamanho, frequência e duração dos registros; proteger acesso e registrar rejeições sem expor segredos. Evitar registrar a solução de palavras em logs, mensagens de erro ou payloads públicos. Treino pode ser local, mas não é fonte de resultado competitivo.

Persistir desafio, versão, tentativa, ações ou estado suficiente para recuperação, resultado validado, sequência e preferência. Restrições únicas no banco e transações devem proteger concorrência e reenvio. Migrações devem seguir Flyway e suportar os bancos do projeto; seeds e versões existentes não podem mudar após publicação. Para rankings, desempate estável final por identificador interno; descrever critérios específicos em cada jogo. Definir retenção e exclusão conforme as políticas da plataforma antes de armazenar replays permanentemente.

## Entregáveis paralelos

1. **Contrato e plataforma:** migrações, modelo, endpoints documentados, autenticação, persistência, dias, recuperação, sequência, ranking e preferências. Entrega: OpenAPI, testes de integração e guia de migração. Publicar contrato antes de integrar motores e telas.
2. **Motor por jogo:** implementar regra determinística versionada e validação de ações/resultados, com testes de referência; cada equipe recebe este contrato e o documento do jogo. Não alterar contratos compartilhados sem coordenar.
3. **Frontend:** hub, telas de partida, treino/fases, resultado, histórico, rankings, convite e configurações. Integrar apenas contratos reais; apresentar carregamento, falhas, recuperação e estados vazios sem dados simulados.
4. **Integração e QA:** revisar contratos e migrações, rodar testes de backend e frontend, validar navegação e acessibilidade em teclado e toque, ensaiar concorrência, falhas e virada de dia. Registrar resultados, limitações e instruções de implantação; liberar apenas após ambiente integrado validado.

Cada entrega deve informar arquivos alterados, dependências, comandos e resultados de testes, limitações e eventuais decisões que mudem os contratos. Não marcar como pronto algo baseado apenas em mock, build ou teste isolado.

## Aceite transversal

- Mesmo desafio e versão para todas as contas no mesmo dia; testes na virada de fuso, concorrência e partida retomada.
- Uma tentativa oficial por usuário/jogo/dia; reenvios não duplicam; treino não afeta ranking ou sequência.
- Resultado adulterado é rejeitado; perdas concluídas contam para sequência; histórico e replays são privados.
- Ranking paginado, determinístico e filtrado por conexões; resultado sem spoiler e publicação somente por ação humana.
- Preferência de lembrete começa desligada e persiste; testes de falhas e interrupção não perdem estado confirmado.
- Jogos acessíveis em dispositivos pequenos, teclado e toque; cores não são o único indicador. Verificação de integração em ambiente de teste antecede publicação.

