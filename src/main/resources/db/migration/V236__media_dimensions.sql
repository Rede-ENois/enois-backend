-- Pixel size of image assets as displayed (EXIF orientation applied), so a post card can reserve its
-- final height before the image loads. Existing rows keep null until the dimensions backfill worker
-- reads them; videos, audio and PDFs stay null. dimension_attempts counts failed reads so an object
-- that cannot be decoded is skipped after a few tries instead of being retried forever.
alter table media_assets add column if not exists width int null;
alter table media_assets add column if not exists height int null;
alter table media_assets add column if not exists dimension_attempts int not null default 0;
