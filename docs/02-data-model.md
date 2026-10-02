# 2. Data model

The schema is `src/main/resources/db/migration/V1__initial_schema.sql`, applied by Flyway at startup. New changes go in a new `V<n>__*.sql` file.

## 2.1 Tables

```mermaid
erDiagram
    textbook ||--o{ chapter : "has"
    chapter ||--o{ chapter : "parent of"
    textbook ||--o{ page_layout : "one row per page"
    textbook ||--o{ glossary_term : "per target language"
    textbook ||--o{ glossary_coverage : "pages scanned for terms"
    textbook ||--o{ book_context : "style guide, synopses"
    chapter ||--o{ book_context : "synopsis of"
    textbook ||--o{ job : "work on"
    job ||--o{ artifact : "produces"

    textbook {
        uuid id PK
        text title
        int page_count
        char64 sha256 UK "re-upload detection"
        text blob_ref "path under data/blobs"
        text status "UPLOADED, EXTRACTING, READY, FAILED"
        text chapter_source "OUTLINE, TOC, NONE (HEURISTIC unused)"
    }
    chapter {
        uuid id PK
        uuid textbook_id FK
        uuid parent_id FK
        int ordinal "document order across the whole book"
        int level
        int start_page
        int end_page
    }
    page_layout {
        uuid textbook_id PK
        int page_no PK
        text kind "TEXT, SCANNED, EMPTY"
        int char_count
        bytea layout_zstd "compressed JSON of blocks"
    }
    translation_unit {
        char64 source_hash PK
        text target_lang PK
        char64 glossary_key PK "hash of glossary terms in the block"
        text model
        bytea target_text_zstd
    }
    glossary_coverage {
        uuid textbook_id PK
        text target_lang PK
        int page_no PK
    }
    glossary_term {
        uuid id PK
        text source_term
        text target_term
        bool pinned
    }
    book_context {
        uuid id PK
        text kind "STYLE_GUIDE, CHAPTER_SYNOPSIS"
    }
    job {
        uuid id PK
        text type "INGEST, TRANSLATE, STUDY_GUIDE"
        text target_lang "translation jobs"
        jsonb chapter_ids "translation jobs"
        jsonb options "renderMode, includeStudyGuide, dryRun"
        text state "PENDING ... CANCELLED"
        int done_units
        int total_units
        text phase
        text error
        jsonb findings "array of kind, blockId, pageNo, message"
    }
    artifact {
        uuid id PK
        uuid job_id FK
        text kind "TRANSLATION, STUDY_GUIDE, DEBUG_LAYOUT"
        text blob_ref
        text filename
    }
```

| Table | Written by | Read by | Notes |
|---|---|---|---|
| `textbook` | `IngestTextbookUseCase` | catalog, translation | `sha256` is unique, so the hash is the de-duplication key |
| `chapter` | ingestion (`ChapterRepository.replaceAll`) | catalog, translation | a flat list with `parent_id`. The tree is rebuilt in `TextbookCatalogService.chapterTree` |
| `page_layout` | ingestion, written in batches of `page-write-batch-size` | translation, learning, layout debug | written twice per page when furniture roles change |
| `translation_unit` | `JdbcTranslationMemory` | translation, live runs only | **not** linked to a textbook. The key is the content hash plus the glossary key, so identical paragraphs with the same glossary terms share one entry across books. Dry runs never read or write it |
| `glossary_term` | `GlossaryService` (extraction adds, reader edits pin) | translation, study guide, `GlossaryController` | unique per book and language on `lower(source_term)`. See [Glossary flow](11-flow-glossary.md) |
| `glossary_coverage` | `GlossaryService` | `GlossaryService` | pages already scanned for terms, so extraction is paid once per page and language |
| `book_context` | nothing yet | nothing yet | schema only. See [Known gaps](10-known-gaps.md) |
| `job` | `JobCoordinator` | `JobController` | written from a `JobSpec` at submit. Translation jobs set `target_lang`, `chapter_ids` (the selected chapters) and `options` (`renderMode`, `includeStudyGuide`, `dryRun`). Ingest jobs leave them empty. `findings` is a jsonb array of `JobFinding`, appended per stage |
| `artifact` | `TranslateChaptersUseCase` | `ArtifactController` | `DEBUG_LAYOUT` is defined, but layout-debug PDFs are returned inline and not stored |

Deleting a textbook cascades through every table above except `translation_unit`, and removes the files too:

```mermaid
sequenceDiagram
    autonumber
    actor U as Browser
    participant C as TextbookController
    participant S as TextbookCatalogService
    participant D as JobArtifactCleanup (TextbookDependents)
    participant DB as Postgres
    participant B as BlobStore

    U->>C: DELETE /textbooks/{id}
    C->>S: delete(id)
    alt unknown
        S-->>C: Rejected(TextbookNotFound)
        C-->>U: 404
    else a job for the book is running
        S->>D: busy(id) = true
        S-->>C: Rejected(TextbookBusy)
        C-->>U: 409
    else
        S->>D: remove(id)
        D->>DB: artifact files of the book's jobs
        D->>DB: delete jobs (artifact rows cascade)
        loop each file
            D->>DB: still referenced by another artifact?
            D->>B: delete if not (blobs are content-addressed, so identical PDFs share a file)
        end
        S->>DB: delete textbook (chapters, page layouts, glossary, coverage cascade)
        S->>B: delete the uploaded original
        C-->>U: 204
    end
```

Rows always go before files, so a failure part-way leaves an orphaned file and never a row pointing at nothing. Files left orphaned anyway, by deletes made before this cleanup existed or by a crash between writing a file and its row, are removed by the [orphan sweep](09-configuration-and-operations.md#96-cleaning-up-orphaned-files).

## 2.2 What a page layout contains

`PageLayout` is the published language between `ingestion` and its consumers, `translation` and `learning`.

```mermaid
classDiagram
    class PageLayout {
        int pageNo
        float widthPt
        float heightPt
        PageKind kind
        List~TextBlock~ blocks
        translatableBlocks()
    }
    class TextBlock {
        String id  "e.g. p412-b7"
        String text
        BlockBox box  "y measured down from page top"
        FontInfo font
        int columnIndex
        int orderInPage
        TextRole role
        translatable()
    }
    class BlockBox {
        float x
        float y
        float width
        float height
        toPdfSpaceY(pageHeight)
    }
    PageLayout "1" *-- "many" TextBlock
    TextBlock *-- BlockBox
```

Running heads and folios are given a non-body `TextRole` during ingestion. They are therefore not `translatable()`, and translation and rendering skip them.

## 2.3 Textbook status

```mermaid
stateDiagram-v2
    [*] --> UPLOADED : upload accepted
    UPLOADED --> EXTRACTING : ingest job starts
    EXTRACTING --> READY : all pages extracted and classified
    EXTRACTING --> FAILED : job throws or is cancelled
    UPLOADED --> FAILED : found unfinished at startup
    EXTRACTING --> FAILED : found unfinished at startup
    FAILED --> UPLOADED : same file uploaded again (retry)
    READY --> [*] : DELETE
    FAILED --> [*] : DELETE
    note right of READY : only READY textbooks can be translated
```

- `IngestTextbookUseCase.extract` marks the textbook `FAILED` on any ending other than success, including cancellation.
- At startup, `IngestionRecovery` fails every textbook still `UPLOADED` or `EXTRACTING`, because no process is ingesting it any more.
- Uploading the bytes of a `FAILED` textbook re-runs ingestion under the same id. Uploading a `READY` one is still a no-op.

## 2.4 Job state

```mermaid
stateDiagram-v2
    [*] --> PENDING : JobCoordinator.submit
    PENDING --> RUNNING : picked up by jobExecutor
    RUNNING --> RUNNING : progress (throttled to one write per 300 ms)
    RUNNING --> SUCCEEDED : work returned normally
    RUNNING --> FAILED : work threw
    RUNNING --> CANCELLED : POST /cancel, or interrupted
    PENDING --> CANCELLED : POST /cancel before it started
    PENDING --> FAILED : found unfinished at startup
    RUNNING --> FAILED : found unfinished at startup
    SUCCEEDED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]
```

`JobState` is a sealed interface, and each variant carries only its own data. For example, `Failed` has a `message` and `Running` has `doneUnits`, `totalUnits` and `phase`.

## 2.5 Blob storage layout

```
data/blobs/
└── 9e/
    └── 9e60a6fb…cbb6dd.pdf      <first 2 hex chars>/<sha256>.<ext>
```

- Uploads and generated artifacts both go through `BlobStorePort` and are content-addressed.
- A write goes to a temp file, is hashed while streaming, and is then moved into place. An interrupted write never leaves a file under a valid name.
- `BlobRef` is the relative key, stored in `textbook.blob_ref` and `artifact.blob_ref`.
