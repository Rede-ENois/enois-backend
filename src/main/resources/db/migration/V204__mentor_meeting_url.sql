-- Sala externa da mentoria (Google Meet, Jitsi...): link público do mentor, mostrado a quem agenda.
alter table mentors add column if not exists meeting_url varchar(500) null;
