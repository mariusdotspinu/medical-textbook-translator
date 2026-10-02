-- Textbook catalog -----------------------------------------------------------

create table textbook (
    id              uuid        primary key,
    title           text        not null,
    authors         text,
    source_language text,
    page_count      int         not null,
    byte_size       bigint      not null,
    -- Content-addressed: a re-upload of the same file is detected here and
    -- short-circuits instead of re-ingesting 1000 pages.
    sha256          char(64)    not null unique,
    blob_ref        text        not null,
    status          text        not null,   -- UPLOADED|EXTRACTING|READY|FAILED
    chapter_source  text,                   -- OUTLINE|TOC|HEURISTIC|NONE
    uploaded_at     timestamptz not null default now(),
    ingested_at     timestamptz
);

create table chapter (
    id          uuid    primary key,
    textbook_id uuid    not null references textbook (id) on delete cascade,
    parent_id   uuid    references chapter (id) on delete cascade,
    ordinal     int     not null,
    level       int     not null,
    title       text    not null,
    start_page  int     not null,
    end_page    int     not null
);
create index chapter_by_book on chapter (textbook_id, ordinal);

-- Extracted layout ----------------------------------------------------------
-- One row per page. layout_zstd holds the zstd-compressed JSON of the page's
-- text blocks with bounding boxes. Nothing queries inside it, so bytea beats
-- jsonb here: ~3KB/page compressed vs ~20KB raw.

create table page_layout (
    textbook_id uuid    not null references textbook (id) on delete cascade,
    page_no     int     not null,
    width       real    not null,
    height      real    not null,
    kind        text    not null,   -- TEXT|SCANNED|EMPTY
    char_count  int     not null,
    block_count int     not null,
    layout_zstd bytea   not null,
    primary key (textbook_id, page_no)
);

-- Terminology consistency ---------------------------------------------------
-- The mechanism that keeps "ventricle" identical on page 40 and page 940, and
-- that rides along in the cached prompt prefix.

create table glossary_term (
    id          uuid    primary key,
    textbook_id uuid    not null references textbook (id) on delete cascade,
    target_lang text    not null,
    source_term text    not null,
    target_term text    not null,
    kind        text    not null,   -- ANATOMY|DRUG|ABBREV|CONDITION|OTHER
    occurrences int     not null default 0,
    -- Set when a human corrects a term; pinned terms are never re-derived.
    pinned      boolean not null default false,
    unique (textbook_id, target_lang, source_term)
);

create table book_context (
    id          uuid primary key,
    textbook_id uuid not null references textbook (id) on delete cascade,
    chapter_id  uuid references chapter (id) on delete cascade,
    kind        text not null,      -- STYLE_GUIDE|CHAPTER_SYNOPSIS
    content     text not null
);
create index book_context_by_book on book_context (textbook_id, kind);

-- Translation memory --------------------------------------------------------
-- Makes retry, resume, and overlapping chapter ranges nearly free: a unit
-- already translated under the same glossary version never hits the API again.

create table translation_unit (
    source_hash      char(64)    not null,
    target_lang      text        not null,
    glossary_version int         not null,
    model            text        not null,
    target_text_zstd bytea       not null,
    created_at       timestamptz not null default now(),
    primary key (source_hash, target_lang, glossary_version)
);

-- Jobs and artifacts -------------------------------------------------------

create table job (
    id          uuid        primary key,
    textbook_id uuid        references textbook (id) on delete cascade,
    type        text        not null,   -- INGEST|TRANSLATE|STUDY_GUIDE
    target_lang text,
    chapter_ids jsonb,
    options     jsonb,
    state       text        not null,   -- PENDING|RUNNING|SUCCEEDED|FAILED|CANCELLED
    done_units  int         not null default 0,
    total_units int         not null default 0,
    -- Current step, streamed to the UI so a 30-minute job is legible as more than a number.
    phase       text,
    findings    jsonb,
    error       text,
    created_at  timestamptz not null default now(),
    started_at  timestamptz,
    finished_at timestamptz
);
create index job_by_book on job (textbook_id, created_at desc);

create table artifact (
    id         uuid        primary key,
    job_id     uuid        not null references job (id) on delete cascade,
    kind       text        not null,   -- TRANSLATION|STUDY_GUIDE|DEBUG_LAYOUT
    blob_ref   text        not null,
    filename   text        not null,
    byte_size  bigint      not null,
    page_count int,
    created_at timestamptz not null default now()
);
create index artifact_by_job on artifact (job_id);
