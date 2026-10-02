-- Glossary support.

-- 1. Translation memory: a per-block glossary key replaces the single glossary version.
--
-- A version number per book cannot work here: translation_unit is shared across books, so
-- book A's version 3 and book B's version 3 would collide, and bumping a version on every
-- new term would invalidate every cached block of the book. Instead each block is keyed by
-- the SHA-256 of the glossary entries that occur in it. Editing a term re-translates only
-- the blocks that contain it.
--
-- Existing rows were translated without a glossary, which is exactly the key of a block
-- that contains no glossary term: SHA-256 of the empty string.

alter table translation_unit drop constraint translation_unit_pkey;
alter table translation_unit add column glossary_key char(64);
update translation_unit
   set glossary_key = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';
alter table translation_unit alter column glossary_key set not null;
alter table translation_unit drop column glossary_version;
alter table translation_unit add primary key (source_hash, target_lang, glossary_key);

-- 2. One entry per term regardless of case: "Heart failure" and "heart failure" are the
--    same term, and two renderings of it would defeat the point of a glossary.

alter table glossary_term drop constraint glossary_term_textbook_id_target_lang_source_term_key;
create unique index glossary_term_unique_source
    on glossary_term (textbook_id, target_lang, lower(source_term));

-- 3. Pages already scanned for terms, per language. Extraction is a paid request, so a
--    page is scanned once; re-translating it, or any range containing it, costs nothing.

create table glossary_coverage (
    textbook_id uuid not null references textbook (id) on delete cascade,
    target_lang text not null,
    page_no     int  not null,
    primary key (textbook_id, target_lang, page_no)
);
