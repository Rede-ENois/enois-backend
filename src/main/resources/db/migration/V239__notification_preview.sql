-- Prévia nas notificações (issue #392): um trecho curto do post/comentário que a notificação é
-- sobre, para quem abre a lista saber do que se trata sem navegar. O texto "Fulana comentou no seu
-- post." continua no `body`; a prévia é o conteúdo de origem, só sem marcação.
--
-- Coluna própria em vez de reaproveitar `body`: o corpo continua legível em quem já lê a API
-- antiga, e o design system já desenha a prévia num bloco separado abaixo dele.
--
-- Aditiva e idempotente: nasce nula, então as notificações existentes aparecem exatamente como
-- antes até a leitura passar a preencher.
alter table notifications
  add column if not exists preview varchar(300) null;
