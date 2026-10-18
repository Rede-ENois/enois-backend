# Labirinto — plano de jogo

> Especificação de trabalho; o motor e a integração precisam de validação real. Contratos comuns: [plataforma](README.md).

Esta frente está em desenvolvimento. A disponibilidade depende de integração e validação do motor e da interface.

## Experiência e regras

O jogador começa em uma célula e encontra a saída por movimentos ortogonais entre células abertas. O desafio diário usa o mesmo mapa, seed e versão para todos. O gerador deve assegurar origem e saída distintas e caminho alcançável. O tamanho do mapa e o objetivo de duração devem ser calibrados em testes; não depender apenas de cor para identificar posição, paredes e saída.

O servidor registra início e fim, reproduz os movimentos e rejeita atravessar paredes ou executar ações após a conclusão. O ranking diário classifica por menor tempo registrado no servidor, depois menor número de movimentos e identificador interno estável. O tempo inclui interrupções e latência; a interface deve explicitar isso. A tentativa oficial só termina ao chegar à saída ou por encerramento registrado; uma partida retomada continua a mesma tentativa.

Treino oferece mapas aleatórios e fases progressivas com tamanho ou complexidade crescentes, separados da classificação e sequência diárias. A UI suporta teclado e toque, expõe instruções e provê representação acessível do mapa e da posição. O resumo compartilhado informa resultado sem revelar o trajeto ou solução.

## Entregáveis para outra IA

- Gerador determinístico versionado e motor de movimentos, incluindo verificação de conectividade e limites de tamanho; preservar seed, versão e mapa diário.
- Validador no servidor com replay, limites de ações, medição de tempo e confirmação da saída; rejeitar movimentos impossíveis e resultados adulterados.
- Interface responsiva com comandos acessíveis, indicação de origem, posição e destino, estado de carregamento e retomada; diferenciar fase e treino do diário.
- Testar determinismo, conectividade em muitas seeds, início/fim distintos, colisão com paredes, limites, replay inválido, tempo, reenvio, teclado e toque. Entregar comandos e evidências.

