# Palavras — plano de jogo

> Especificação de trabalho; não existe garantia de dicionário licenciado ou implementação. Contratos comuns: [plataforma](README.md).

A primeira entrega em desenvolvimento usa o namespace `/daily-games/words`; o contrato concreto e os recursos entregues devem ser verificados no código e OpenAPI. O catálogo de jogos de criadores existente continua independente. Recursos adicionais desta especificação permanecem planejados até serem comprovados por testes integrados.

## Experiência e regras

Descobrir uma palavra portuguesa de cinco letras em até seis tentativas. Cada palpite válido produz indicação por posição: letra na posição correta, letra presente em outra posição ou ausente. A avaliação de letras repetidas deve consumir primeiro correspondências exatas e depois as restantes, sem atribuir ocorrências além das existentes. Mostrar texto ou símbolo além da cor e fornecer alternativa acessível para leitores de tela.

O servidor seleciona e mantém oculta a palavra diária até a conclusão; nunca enviar solução, seed que permita deduzi-la, nem lista de respostas futuras ao cliente. Validar cada palpite no servidor e devolver apenas avaliações necessárias. Palavras inválidas não consomem tentativa. A normalização e tratamento de acentos devem ser consistentes entre entrada, dicionário e comparação e comunicados na interface.

O ranking ordena vitórias por menor número de tentativas, depois menor tempo registrado no servidor e identificador interno estável; derrotas concluídas aparecem depois das vitórias. A solução nunca integra o resumo compartilhado. Treino e fases progressivas usam palavras aleatórias e dificuldade crescente sem mudar o ranking ou a sequência diária.

## Entregáveis para outra IA

- Selecionar lista de palavras e lista de palpites de português com fonte, licença, versão, procedência e permissão de redistribuição documentadas. Revisar qualidade e conteúdo; sem licença adequada, não liberar o jogo competitivo.
- Implementar seleção diária segura, avaliação de letras repetidas, normalização, estado de partida e validação no servidor. Definir como evitar repetição excessiva sem comprometer seleção consistente e histórico.
- Criar interface por teclado físico, virtual e toque, com feedback textual, histórico de palpites e recuperação. Resultado compartilhável não revela palavra nem grade que a exponha.
- Testar letras repetidas, acentos, entradas inválidas, seis tentativas, vitória e derrota, concorrência/reenvio e inspeção de respostas públicas para ausência da solução. Entregar comandos e evidências.
