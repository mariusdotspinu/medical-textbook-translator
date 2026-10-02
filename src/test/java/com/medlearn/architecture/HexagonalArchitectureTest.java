package com.medlearn.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Enforces the hexagonal boundaries as a build failure rather than a convention.
 *
 * <p>ArchUnit is used here in place of Spring Modulith (which the plan listed as optional):
 * the rule that actually matters for this codebase is "the domain depends on nothing", and
 * expressing that directly is clearer than restructuring packages to satisfy Modulith's
 * module conventions.
 *
 * <p>These cross-context dependencies are deliberate and allowed, and are documented here so
 * that they stay deliberate:
 * <ul>
 *   <li>{@code ingestion -> library.domain} - ingestion is what writes the catalog.
 *   <li>{@code jobs -> library.domain.port} - jobs implements {@code TextbookDependents}, so
 *       deleting a textbook clears its jobs and artifact files without the catalog knowing
 *       about either.
 *   <li>{@code translation/learning -> ingestion.domain} - {@code PageLayout} is treated as
 *       a published language rather than duplicated per context.
 * </ul>
 * The reverse directions are forbidden below.
 */
@AnalyzeClasses(
        packages = "com.medlearn",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class HexagonalArchitectureTest {

    /**
     * The core rule. If the domain can be constructed without Spring, PDFBox, Jackson or the
     * Anthropic SDK on the classpath, then it can be unit-tested and reasoned about alone,
     * and infrastructure choices stay replaceable.
     */
    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "org.apache.pdfbox..",
                    "com.fasterxml..",
                    "tools.jackson..",
                    "com.anthropic..",
                    "com.github.luben..",
                    "io.github.openhtmltopdf..",
                    "jakarta..",
                    "javax.sql..")
            .because("the domain must stay independent of frameworks and infrastructure");

    /** Dependencies point inward: nothing inside may reach out to an adapter. */
    @ArchTest
    static final ArchRule nothingInsideDependsOnAdapters = noClasses()
            .that().resideInAnyPackage("..domain..", "..application..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..")
            .because("domain and application depend on ports, never on their implementations");

    /** Ports are interfaces the domain owns; adapters must not define new ones. */
    @ArchTest
    static final ArchRule domainDoesNotDependOnPlatformInternals = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("com.medlearn.platform..")
            .because("the domain talks to ports, not to the filesystem or a compressor");

    /**
     * Ingestion writes the catalog, so the catalog must not reach back into ingestion.
     * Composition of the two happens in the web adapter, which is allowed.
     */
    @ArchTest
    static final ArchRule catalogDoesNotDependOnIngestion = noClasses()
            .that().resideInAnyPackage(
                    "com.medlearn.library.domain..", "com.medlearn.library.application..")
            .should().dependOnClassesThat().resideInAPackage("com.medlearn.ingestion..")
            .because("the ingestion -> library dependency must stay one-way");

    /** Jobs implements a catalog port; the catalog must never reach back into jobs. */
    @ArchTest
    static final ArchRule catalogDoesNotDependOnJobs = noClasses()
            .that().resideInAPackage("com.medlearn.library..")
            .should().dependOnClassesThat().resideInAPackage("com.medlearn.jobs..")
            .because("the jobs -> library dependency must stay one-way");

    /** No cycles between bounded contexts. */
    @ArchTest
    static final ArchRule contextsAreAcyclic = slices()
            .matching("com.medlearn.(*)..")
            .should().beFreeOfCycles()
            .because("a cycle between contexts means the boundary is not real");
}
