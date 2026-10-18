# Blocos — plano de jogo

> Especificação de trabalho; o motor e a integração precisam de validação real. Contratos comuns: [plataforma](README.md).

Esta frente está em desenvolvimento. A disponibilidade depende de integração e validação do motor e da interface.

## Experiência e regras

Peças formadas por quatro células caem em uma grade de 10 × 20. O jogador move e gira peças, completa linhas e termina ao alcançar o objetivo diário ou quando não há espaço para nova peça. O desafio diário apresenta a mesma sequência para todos, gerada por seed e versão; o objetivo, a velocidade inicial e o limite de partida são dados do desafio. O cliente mostra objetivo e estado antes de iniciar, e oferece controles de teclado e toque identificados. Pausa é permitida no treino; no diário não deve suspender a contagem de tempo competitiva.

Uma linha removida vale 100 pontos; duas simultâneas 300, três 500 e quatro 800. A pontuação é calculada pelo servidor; o ranking diário ordena pontuação decrescente, depois menor tempo registrado no servidor, depois identificador interno estável. O resultado registra vitória ou derrota e pontuação. Para não tornar a seed manipulável ou os resultados incompatíveis entre versões, o servidor persiste a versão do gerador e do motor.

O motor em desenvolvimento usa regras versão 2: ticks de 100 ms, gravidade a cada cinco ticks, até uma ação por tick e limite de 6.000 ticks. A posição ativa e a próxima peça são reconstruídas por replay; o tempo e os ticks oficiais devem vir do servidor. Essa implementação precisa de integração e testes antes de ser oferecida como jogo concluído.

Treino oferece sequências aleatórias e fases progressivas com metas crescentes de linhas e velocidade. A fase e seu progresso são separados do desafio diário. A duração e dificuldade devem ser calibradas em testes com pessoas antes da publicação; alterações de regras exigem nova versão.

## Entregáveis para outra IA

- Motor puro e determinístico: sequência, movimentação, rotação, colisões, queda, fixação, remoção de linhas, pontuação e fim de partida. Documentar regras precisas de rotação e gravidade junto aos vetores de teste antes de integrá-las.
- Validador no servidor que reproduz ações em ordem e rejeita movimentos impossíveis, duração inválida, quantidade excessiva de ações e resultados após o fim; não aceitar pontuação do cliente.
- Interface responsiva, controles acessíveis, objetivo, feedback, fim e recuperação; obedecer ao contrato compartilhado para o diário e distinguir treino.
- Testes para mesma seed, limites da grade, colisões, linhas múltiplas, pontuação, fim, replay adulterado, recuperação, teclado e toque. Entregar comandos e evidências dos testes.

