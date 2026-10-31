-- Sala da sessão de mentoria: o padrao passa a ser uma sala Jitsi criada na hora do agendamento
-- (o mentor nao precisa configurar nada); o mentor pode escolher uma sala permanente do Jitsi ou
-- apontar para outra sala (Meet, Zoom, o que for).
alter table mentors add column if not exists room_mode varchar(24) not null default 'JITSI_SESSION';
alter table mentors add column if not exists jitsi_room varchar(120) null;

-- Quem ja tinha sala configurada continua usando ela: o padrao novo (sala Jitsi por sessao) vale
-- so para quem nunca escolheu nada, para nao mudar a sala de quem ja avisou aos alunos onde a
-- sessao acontece.
update mentors set room_mode = 'CUSTOM' where meeting_url is not null and meeting_url <> '';

-- O link e resolvido no agendamento e congelado na sessao: trocar a sala do perfil depois nao muda
-- onde uma sessao ja marcada acontece.
alter table mentor_sessions add column if not exists room_mode varchar(24) null;
alter table mentor_sessions add column if not exists room_url varchar(500) null;
