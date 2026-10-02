package com.medlearn.ingestion.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderFooterFilterTest {

    private static final float PAGE_HEIGHT = 792f;
    private static final float PAGE_WIDTH = 612f;

    private static TextBlock block(String text, float y, float width) {
        return new TextBlock(
                "p1-b0", text, new BlockBox(54f, y, width, 11f),
                new FontInfo("Minion-Regular", 9.5f, false, false), 0, 0, TextRole.BODY);
    }

    private static PageLayout page(int pageNo, List<TextBlock> blocks) {
        return new PageLayout(pageNo, PAGE_WIDTH, PAGE_HEIGHT, PageKind.TEXT, blocks);
    }

    @Test
    @DisplayName("a running head repeating across pages is excluded from translation")
    void detectsRunningHead() {
        HeaderFooterFilter filter = new HeaderFooterFilter();

        List<PageLayout> pages = new java.util.ArrayList<>();
        for (int p = 1; p <= 40; p++) {
            pages.add(page(p, List.of(
                    block("Chapter 12  Acute Coronary Syndromes", 30f, 260f),
                    block("Body text that differs on every page " + p, 300f, 400f),
                    block(String.valueOf(p), 760f, 10f))));
        }
        pages.forEach(filter::observe);
        Set<String> repeated = filter.repeatedKeys();

        PageLayout result = filter.apply(pages.getFirst(), repeated);

        assertThat(result.blocks().get(0).role()).isEqualTo(TextRole.RUNNING_HEAD);
        assertThat(result.blocks().get(0).translatable()).isFalse();
        assertThat(result.blocks().get(1).role()).isEqualTo(TextRole.BODY);
        assertThat(result.blocks().get(1).translatable()).isTrue();
        assertThat(result.blocks().get(2).role()).isEqualTo(TextRole.FOLIO);
    }

    @Test
    @DisplayName("digits are normalised so 'page 7' and 'page 412' are the same candidate")
    void normalisesDigitsAcrossPages() {
        // Without this, a folio looks unique on every page and is never recognised.
        assertThat(HeaderFooterFilter.normalize("Cardiology Review - page 7"))
                .isEqualTo(HeaderFooterFilter.normalize("Cardiology Review - page 412"));
    }

    @Test
    @DisplayName("a page number is a folio even if the page count is small")
    void treatsBareNumbersAsFolios() {
        HeaderFooterFilter filter = new HeaderFooterFilter();
        List<PageLayout> pages = List.of(
                page(1, List.of(block("1", 760f, 10f))),
                page(2, List.of(block("ii", 760f, 10f))),
                page(3, List.of(block("3", 760f, 10f))),
                page(4, List.of(block("4", 760f, 10f))),
                page(5, List.of(block("5", 760f, 10f))));
        pages.forEach(filter::observe);
        Set<String> repeated = filter.repeatedKeys();

        for (PageLayout original : pages) {
            assertThat(filter.apply(original, repeated).blocks().getFirst().role())
                    .isEqualTo(TextRole.FOLIO);
        }
    }

    @Test
    @DisplayName("a genuine heading that happens to sit near the top is not reclassified")
    void leavesNonRepeatingTopContentAlone() {
        HeaderFooterFilter filter = new HeaderFooterFilter();
        String[] titles = {
                "Acute Coronary Syndromes", "Valvular Heart Disease", "Cardiac Arrhythmias",
                "Heart Failure Management", "Congenital Defects", "Pericardial Disease",
                "Aortic Dissection", "Infective Endocarditis", "Cardiomyopathies",
                "Pulmonary Hypertension"};
        List<PageLayout> pages = new java.util.ArrayList<>();
        for (int p = 1; p <= titles.length; p++) {
            pages.add(page(p, List.of(block(titles[p - 1], 30f, 260f))));
        }
        pages.forEach(filter::observe);

        PageLayout result = filter.apply(pages.getFirst(), filter.repeatedKeys());
        assertThat(result.blocks().getFirst().role()).isEqualTo(TextRole.BODY);
    }

    @Test
    @DisplayName("header text differing only by a number IS treated as furniture - by design")
    void treatsDigitOnlyVariationAsFurniture() {
        // A deliberate tradeoff, not an oversight. Collapsing digits is the only thing that
        // makes "page 7" and "page 412" the same candidate, which is what catches folios and
        // numbered running heads at all. The cost is that a real heading in the top 12% of
        // the page, repeating on a third of all pages, and differing from its neighbours
        // only by a digit would be misclassified.
        //
        // That is acceptable because the band restriction carries the weight: in a textbook
        // the top 12% of the page is running-head territory, and genuine content that lives
        // there on a third of the pages does not occur. Narrowing the normalisation to fix
        // this case would break folio detection, which is the far more common need.
        HeaderFooterFilter filter = new HeaderFooterFilter();
        List<PageLayout> pages = new java.util.ArrayList<>();
        for (int p = 1; p <= 20; p++) {
            pages.add(page(p, List.of(block("Section " + p, 30f, 80f))));
        }
        pages.forEach(filter::observe);

        assertThat(filter.apply(pages.getFirst(), filter.repeatedKeys())
                .blocks().getFirst().role()).isEqualTo(TextRole.RUNNING_HEAD);
    }

    @Test
    @DisplayName("a short document is left alone - repetition means nothing over 3 pages")
    void doesNothingOnVeryShortDocuments() {
        HeaderFooterFilter filter = new HeaderFooterFilter();
        List<PageLayout> pages = List.of(
                page(1, List.of(block("Same header", 30f, 100f))),
                page(2, List.of(block("Same header", 30f, 100f))),
                page(3, List.of(block("Same header", 30f, 100f))));
        pages.forEach(filter::observe);

        assertThat(filter.repeatedKeys()).isEmpty();
        assertThat(filter.apply(pages.getFirst(), filter.repeatedKeys())
                .blocks().getFirst().role()).isEqualTo(TextRole.BODY);
    }

    @Test
    @DisplayName("body text is never treated as furniture, however often it repeats")
    void ignoresLongRepeatingBodyText() {
        HeaderFooterFilter filter = new HeaderFooterFilter();
        String boilerplate = "This long sentence repeats on every page of the document but it "
                + "sits in the body area and is far too long to be page furniture.";
        List<PageLayout> pages = new java.util.ArrayList<>();
        for (int p = 1; p <= 30; p++) {
            pages.add(page(p, List.of(block(boilerplate, 400f, 400f))));
        }
        pages.forEach(filter::observe);

        assertThat(filter.apply(pages.getFirst(), filter.repeatedKeys())
                .blocks().getFirst().translatable()).isTrue();
    }
}
