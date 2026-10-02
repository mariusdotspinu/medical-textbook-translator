# 3. Upload and ingestion flow

**Classes:** `IngestionController`, `IngestTextbookUseCase`, `FilesystemBlobStore`, `PdfBoxLayoutExtractor`, `PdfBoxOutlineReader`, `ChapterDetectionService`, `TableOfContentsDetector`, `TocParser`, `TocPageMapper`, `OutlineTreeBuilder`, `PageSegmenter`, `HeaderFooterFilter`, `JdbcPageLayoutRepository`, `LayoutCodec`.

There are two phases. The **upload** is synchronous and returns in about a second. **Extraction** runs as an `INGEST` job.

## 3.1 Upload (synchronous)

```mermaid
sequenceDiagram
    autonumber
    actor U as Browser
    participant C as IngestionController
    participant UC as IngestTextbookUseCase
    participant B as BlobStore
    participant DB as TextbookRepository
    participant X as LayoutExtractor
    participant J as JobCoordinator

    U->>C: POST /api/v1/textbooks (multipart file)
    alt empty file
        C-->>U: 400 "No file was uploaded"
    end
    C->>UC: upload(stream, filename)
    UC->>B: store(stream)
    Note over B: stream to temp file, SHA-256 while copying,<br/>move to data/blobs/xx/<hash>.pdf
    B-->>UC: StoredBlob(ref, hash, size)
    UC->>DB: findByHash(hash)
    alt already ingested, status FAILED
        DB-->>UC: existing textbook
        UC->>DB: status = UPLOADED
        UC->>J: submit(INGEST, extract(...)) (retry, same textbookId)
        UC-->>C: Accepted(textbookId, jobId)
        C-->>U: 202 { textbookId, jobId }
    else already ingested, any other status
        DB-->>UC: existing textbook
        UC-->>C: AlreadyExists(textbookId)
        C-->>U: 200 { textbookId, "already ingested" }
    else new file
        UC->>X: probe(file)
        alt not a PDF, encrypted, or 0 pages
            UC->>B: delete(ref)
            UC-->>C: Rejected(DomainError)
            C-->>U: 422 { error }
        else valid
            UC->>DB: insert(Textbook UPLOADED)
            UC->>J: submit(INGEST, extract(...))
            J-->>UC: jobId
            UC-->>C: Accepted(textbookId, jobId)
            C-->>U: 202 { textbookId, jobId }
        end
    end
```

After a `202`, the browser subscribes to `/api/v1/jobs/{jobId}/events` for progress. See [Jobs and progress](07-flow-jobs-and-progress.md).

## 3.2 Extraction (the `INGEST` job)

```mermaid
sequenceDiagram
    autonumber
    participant J as Ingest job (virtual thread)
    participant DB as Postgres
    participant O as OutlineReader
    participant CD as ChapterDetectionService
    participant P as extractionExecutor (N platform threads)
    participant F as HeaderFooterFilter

    J->>DB: textbook.status = EXTRACTING
    Note over J: progress.total = pageCount x 2 (two passes)

    rect rgba(127,127,127,0.08)
    Note over J,CD: phase "detecting chapters"
    J->>O: readOutline(file)
    O-->>J: bookmark tree
    J->>CD: detect(outline, pageCount)
    CD-->>J: chapters + source (OUTLINE or NONE)
    J->>DB: chapters.replaceAll
    end

    rect rgba(127,127,127,0.08)
    Note over J,F: phase "extracting pages" (pass 1)
    J->>P: one contiguous page range per worker
    loop each page in the worker's range
        P->>P: extractPage (runs → columns → lines → paragraphs)
        P->>F: observe(layout) (count repeating top/bottom lines)
        P->>DB: saveAll every 100 pages (zstd JSON)
    end
    P-->>J: all chunks joined (first failure fails the job)
    end

    rect rgba(127,127,127,0.08)
    Note over J,F: phase "identifying running heads and page numbers" (pass 2)
    J->>F: repeatedKeys()
    loop windows of 100 pages
        J->>DB: findRange
        J->>F: apply(page, repeated) (re-role heads and folios)
        J->>DB: saveAll (changed pages only)
    end
    end

    opt no bookmarks (source NONE)
    Note over J,DB: phase "reading the table of contents" (see 3.5)
    J->>DB: chapters.replaceAll(TOC chapters), if a TOC was found and placed
    end

    J->>DB: markReady(chapter_source, ingested_at), status = READY

    Note over J,DB: on an exception or a cancel, the textbook is set to FAILED instead
```

Cancellation is checked between pages in every extraction worker and between windows in pass 2. The check uses the job's cancel flag, so workers on `extractionExecutor` stop within a page. A failed or cancelled ingest marks the textbook `FAILED`, and uploading the same file again retries it.

## 3.3 Inside `extractPage`: from words to blocks

This order is fixed. Joining lines before columns are known merges the left column's sentence with the right column's into fluent nonsense.

```mermaid
flowchart TD
    A["PositionalTextStripper<br/>word runs with x, y, font"] --> B["ColumnDetector<br/>coverage histogram → gutter, or single column"]
    B --> C["Bucket runs by baseline"]
    C --> D{"anything inside<br/>the gutter?"}
    D -->|yes| E["one full-width line<br/>(heading, wide caption)"]
    D -->|no| F["two column lines<br/>sharing a baseline"]
    E --> G["LineGrouper"]
    F --> G
    G --> H["ParagraphMerger<br/>per column, never across"]
    H --> I["Order: band → column → y<br/>renumber ids pN-bK"]
    I --> J{"chars < threshold<br/>and large image?"}
    J -->|yes| K["PageKind SCANNED"]
    J -->|"no, 0 chars"| L["PageKind EMPTY"]
    J -->|no| M["PageKind TEXT"]
```

- **Bands.** A full-width element splits the page horizontally. Everything above it is read first, then both columns below it.
- **Coordinates.** The y axis runs downward from the top of the page. `BlockBox.toPdfSpaceY` is the only place it is flipped for drawing.
- **One `PDDocument` per worker.** PDFBox documents are not thread-safe. Each is opened with a temp-file stream cache so a 500 MB PDF is not loaded into the heap.

## 3.4 Chapter detection

```mermaid
flowchart TD
    A["PDF bookmark tree"] --> B{"empty?"}
    B -->|yes| C["one chapter 'Full text'<br/>pages 1..N, source NONE"]
    C --> T{"after extraction:<br/>printed TOC found<br/>and placed? (3.5)"}
    T -->|yes| D
    T -->|no| C2["keep 'Full text', source NONE"]
    B -->|no| D{"more than 4000 nodes?"}
    D -->|yes| E["drop the deepest level, repeat"] --> D
    D -->|no| F["flatten depth-first<br/>ordinal = document order across the book"]
    F --> G["source OUTLINE, or TOC"]
```

`OutlineTreeBuilder` closes each entry's page range against the start of the next entry that is not a descendant, for bookmarks and TOC entries alike. Font-size heuristic detection (`HEURISTIC`) is not implemented.

## 3.5 Chapters from a printed table of contents

Used only when the PDF has no bookmarks. It runs at the end of the ingest job, because it needs the extracted pages and the page numbers recognised by the furniture pass. Each step rejects rather than guesses: a TOC that can't be read or placed leaves the book with its single "Full text" chapter.

```mermaid
sequenceDiagram
    autonumber
    participant J as Ingest job
    participant D as TableOfContentsDetector
    participant X as Session.extractLines
    participant P as TocParser
    participant M as TocPageMapper
    participant DB as page_layout

    J->>D: detect(book, file, pageCount)
    loop first 50 pages
        D->>X: lines of the page (column-aware, not merged into paragraphs)
    end
    D->>P: parse(lines by page)
    alt no TOC pages, or under 3 entries, or page numbers keep going backwards
        P-->>D: empty
        D-->>J: empty: keep "Full text"
    end
    P-->>D: entries (title, level, printed page), TOC pages
    D->>DB: printed page numbers (FOLIO blocks) of up to 300 pages after the TOC
    D->>M: map(entries, folios, body text of a page on demand)
    alt no convincing offset
        M-->>D: empty
        D-->>J: empty: keep "Full text"
    end
    M-->>D: offset (printed page 1 = PDF page 1 + offset)
    D->>D: OutlineTreeBuilder.nest + close
    D-->>J: chapters, source TOC
```

**Why lines are re-read.** Stored layouts merge lines into paragraphs. Dot leaders make every TOC entry full width, so no entry looks like a paragraph's short last line, and consecutive entries would be merged into one block. `PageSegmenter.segmentLines` keeps the column handling and skips the merging.

**Parsing (`TocParser`):**

| Step | Rule |
|---|---|
| TOC page | at least 4 lines ending in a page number, making up at least half the lines with letters. A "Contents" heading lowers this to 2 entries on the first page |
| Extent | consecutive TOC pages within the first 50. A "List of figures / tables / abbreviations…" heading ends it |
| Entry | `Title . . . . 12`, `Title.....12`, `Title ··· 12`, or `Title 12` |
| Wrapped title | a lettered line without a number, directly above an entry in the same column and not further left, is the first half of the title |
| Front matter | entries numbered in roman numerals (Preface … vii) are skipped |
| Level | indentation measured from the column's left edge, in 8 pt steps, at most 5 levels. A two-column TOC does not turn its right column into a deeper level |
| Order | an entry whose page goes backwards is dropped. If more than 10% do, it was not a TOC |

**Placing printed numbers (`TocPageMapper`).** Printed page 1 is rarely PDF page 1:

1. **Printed page numbers.** If at least 3 recognised folios, and 60% of them, agree on `pdfPage − printed`, that is the offset.
2. **Chapter titles.** Otherwise, candidate offsets come from where the first five titles first appear after the TOC. Each candidate is scored by how many of up to 30 sampled entries have their title in the body text of the page they map to. At least half must match. Running heads are excluded from that text, since they repeat a chapter title on all of its pages.
3. Either way, at least 90% of entries must land inside the book.

Titles are compared as lower-case letters only, without a leading "Chapter 3" or "2.1". The TOC's "3 Heart failure" then matches the page's "CHAPTER 3: HEART FAILURE".
