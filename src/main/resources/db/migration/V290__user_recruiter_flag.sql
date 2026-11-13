-- Declaracao do proprio perfil: a pessoa recruta. Liga a central de recrutamento antes da primeira
-- vaga publicada; quem ja tem vaga continua vendo a central (o vinculo e a propria vaga).
-- Aditiva e com default: contas existentes nascem sem o papel.
alter table users add column if not exists is_recruiter boolean not null default false;