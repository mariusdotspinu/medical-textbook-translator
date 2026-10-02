# 1. Architecture (HLD)

## 1.1 System context

A single-user, local-first web application. It runs as one Spring Boot process with one Postgres database. Large files live on the local disk, and the Anthropic API is the only external service.

```mermaid
flowchart TB
    reader([Reader in a browser])

    subgraph app["med-textbook-learning (Spring Boot, port 8090)"]
        ui["Static UI<br/>index.html"]
        api["REST API + SSE<br/>/api/v1/..."]
    end

    pg[("PostgreSQL 17<br/>port 5433<br/>catalog, layouts, jobs,<br/>translation memory")]
    fs[["Local disk<br/>./data/blobs  (PDFs)<br/>./fonts       (TTF/OTF)"]]
    claude["Anthropic API<br/>(only when ANTHROPIC_API_KEY is set)"]

    reader --> ui
    ui -->|"fetch + EventSource"| api
    api --> pg
    api --> fs
    api -->|"translation + study guide"| claude
```

| Concern | Choice |
|---|---|
| Runtime | Java 25, Spring Boot 4 (Web MVC, JDBC, Flyway, Actuator) |
| Database | PostgreSQL 17 with plain JDBC (no JPA). The schema lives in `src/main/resources/db/migration` |
| PDF reading and overlay | Apache PDFBox 3 |
| Reflow and study-guide PDFs | OpenHTMLtoPDF (HTML/CSS → PDF) |
| Layout compression | zstd-jni |
| LLM | `anthropic-java` SDK with structured output and prompt caching |
| Boundary enforcement | ArchUnit (`HexagonalArchitectureTest`) |

## 1.2 Bounded contexts

The code is organised by context first and by layer second. Each top-level package under `com.medlearn` is one context.

```mermaid
flowchart TB
    subgraph contexts["Bounded contexts"]
        library["library<br/>catalog: textbooks, chapters"]
        ingestion["ingestion<br/>PDF → PageLayout, chapter detection"]
        translation["translation<br/>batching, model, quality gate, render"]
        learning["learning<br/>study guides"]
        jobs["jobs<br/>async jobs, SSE, artifacts"]
        maintenance["maintenance<br/>orphan-file sweep"]
    end

    subgraph base["Foundation"]
        shared["shared<br/>IDs, DomainError, BlobStorePort, FontProviderPort"]
        platform["platform<br/>blob store, fonts, zstd, @ConfigurationProperties"]
    end

    config["config<br/>executors, conditional Anthropic beans"]

    ingestion -->|"writes the catalog"| library
    translation -->|"reads PageLayout"| ingestion
    learning -->|"reads PageLayout"| ingestion
    translation -->|"triggers study guide"| learning
    translation --> library
    ingestion --> jobs
    jobs -->|"implements TextbookDependents"| library
    translation --> jobs

    contexts --> shared
    platform --> shared
    config --> translation
    config --> learning
    config --> platform
```

Every arrow is one-way, and `HexagonalArchitectureTest` fails the build if a cycle appears. Notable rules:

- `library` never imports `ingestion`. That is why uploading lives in `IngestionController` and not `TextbookController`.
- `learning` knows nothing about `translation`. Translation calls into learning, never the reverse.
- `maintenance` imports only `shared`. It learns which files are in use through `BlobReferencesPort`, which `library` and `jobs` implement on their repositories.
- `library` never imports `jobs`. Deleting a textbook clears its jobs and artifact files through the `TextbookDependents` port, which `library` owns and `jobs` implements.
- No context imports `config`. It only wires things together.

## 1.3 Hexagonal layers inside a context

Every context has the same shape. `translation` is shown here as the example.

```mermaid
flowchart LR
    subgraph in["adapter/web (inbound)"]
        ctrl[TranslationController]
    end

    subgraph core["Inside the hexagon"]
        app["application/<br/>TranslateChaptersUseCase<br/>PromptAssembler<br/>TranslationQualityGate"]
        dom["domain/<br/>BatchPlanner, QualityFinding,<br/>TranslatedBlock, RenderMode"]
        port["domain/port/<br/>TranslationModelPort<br/>TranslationMemoryPort<br/>PdfRendererPort"]
    end

    subgraph out["adapter/* (outbound)"]
        ai["adapter/ai<br/>AnthropicTranslationAdapter<br/>DryRunTranslationAdapter"]
        persist["adapter/persistence<br/>JdbcTranslationMemory"]
        render["adapter/render<br/>OverlayPdfRenderer<br/>ReflowPdfRenderer"]
    end

    ctrl --> app
    app --> dom
    app --> port
    ai -. implements .-> port
    persist -. implements .-> port
    render -. implements .-> port
```

| Layer | May depend on | Must not depend on |
|---|---|---|
| `domain/` | the JDK, `shared` | Spring, PDFBox, Jackson 2/3, Anthropic, zstd, OpenHTMLtoPDF, `jakarta.*`, `javax.sql`, `platform` |
| `application/` | `domain`, ports, Spring stereotypes | any `adapter` package |
| `adapter/` | everything above, plus infrastructure libraries | other contexts' adapters |

## 1.4 Live and dry-run wiring

```mermaid
flowchart TB
    key{"medlearn.anthropic.api-key<br/>empty?"}
    key -->|no| live["AnthropicConfig creates<br/>AnthropicClient<br/>AnthropicTranslationAdapter (billable)<br/>AnthropicStudyGuideAdapter (billable)"]
    key -->|yes| none["AnthropicConfig skipped"]
    dry["DryRunTranslationAdapter<br/>DryRunStudyGuideAdapter<br/>(always present, not billable)"]

    req{"request dryRun?"}
    req -->|true| pickDry["pick model with billable() == false"]
    req -->|false| pickLive["pick model with billable() == true<br/>or fail: 'set ANTHROPIC_API_KEY, or use dryRun'"]
```

Both use cases, `TranslateChaptersUseCase` and `GenerateStudyGuideUseCase`, take a `List` of model ports and choose one per request. The app always starts. A live request without a key fails its job with a clear message.

## 1.5 Threading model

```mermaid
flowchart LR
    http["Tomcat request threads<br/>(virtual threads)"] -->|"submit"| jobEx["jobExecutor<br/>virtual thread per task"]
    jobEx -->|"ingest job"| extEx["extractionExecutor<br/>fixed platform pool<br/>min(cores, 8)"]
    jobEx -->|"translate job"| fan["per-job virtual-thread executor"]
    fan --> slots["ApiSlots: one fair Semaphore<br/>shared by ALL translation jobs<br/>and glossary extraction<br/>(max-in-flight = 8)"]
    slots --> claude["Anthropic API"]
```

| Executor | Kind | Runs | Why |
|---|---|---|---|
| Tomcat | virtual | HTTP requests | `spring.threads.virtual.enabled=true` |
| `jobExecutor` | virtual, unbounded | each job body | jobs mostly wait on I/O |
| `extractionExecutor` | platform, bounded | page extraction chunks | CPU and memory heavy. Each worker holds its own `PDDocument` |
| per-translation executor | virtual, gated by the process-wide `ApiSlots` semaphore | batch and term-extraction API calls | the API rate limit belongs to the account, so concurrent jobs share one limit |

## 1.6 Cross-cutting decisions

| Decision | Where | Reason |
|---|---|---|
| Content-addressed blobs | `FilesystemBlobStore` | a re-upload is a no-op, and a failed upload never leaves a half-written file under a valid name |
| Layout stored as zstd JSON in `bytea` | `LayoutCodec`, `page_layout` | about 1 KB per page, and nothing queries inside it |
| Two Jackson majors | `pom.xml` | Jackson 3 for web payloads (Boot 4), Jackson 2 only for SDK DTOs and `LayoutCodec` |
| Sealed state and outcome types | `JobState`, `UploadOutcome`, `Outcome` | an exhaustive `switch` turns a new state into a compile error at every call site |
| Errors as values | `DomainError` | use cases return `Rejected(error)`, and controllers map it to `422` |
