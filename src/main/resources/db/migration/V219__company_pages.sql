-- Company pages: jobs linked to a page, people following a page, one review per completed
-- microproject and the professional conversation each proposal opened. Additive only.

-- Jobs linked to a company page (null = not linked; the free-text `company` stays as it is).
alter table jobs add column if not exists company_id varchar(255) null;
create index if not exists idx_jobs_company on jobs (company_id, status);

-- Existing rows: a job is linked ONLY when the person who posted it is a member (any role) of an
-- active page with the same name (case-insensitive). A name match alone never links, since anyone
-- can create a page with any name. Every other job keeps company_id null.
update jobs set company_id = (
  select min(c.id) from companies c
  where c.status = 'ACTIVE'
    and lower(c.name) = lower(jobs.company)
    and c.id in (select m.company_id from company_memberships m where m.user_id = jobs.posted_by)
) where company_id is null;

create table if not exists company_follows (
  company_id varchar(255) not null,
  user_id varchar(255) not null,
  created_at timestamp not null,
  primary key (company_id, user_id),
  key idx_company_follows_user (user_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- One review per microproject, written by the company when it completes the project, about the
-- selected professional.
create table if not exists micro_project_reviews (
  project_id varchar(255) not null,
  company_id varchar(255) not null,
  user_id varchar(255) not null,
  rating tinyint not null,
  text varchar(500) null,
  created_at timestamp not null,
  primary key (project_id),
  key idx_micro_project_reviews_company (company_id, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_uca1400_ai_ci;

-- The professional conversation a proposal opened (null on proposals sent before this version).
alter table micro_project_proposals add column if not exists conversation_id varchar(255) null;
