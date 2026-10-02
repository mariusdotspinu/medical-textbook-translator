package com.medlearn.translation.application;

import com.medlearn.jobs.domain.JobFinding;
import com.medlearn.translation.domain.QualityFinding;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Turns translation findings into job findings a reader can act on.
 *
 * <p>Each finding carries the page of the source PDF, because that is how a reader finds the
 * passage: a block id means nothing outside this program. Findings are sorted by page so the
 * list reads in book order, regardless of which batch finished first.
 */
final class FindingReport {

    private FindingReport() {}

    static List<JobFinding> toJobFindings(
            Collection<QualityFinding> findings, Map<String, Integer> pageByBlock) {
        return findings.stream()
                .map(finding -> new JobFinding(
                        kind(finding),
                        finding.blockId(),
                        pageOf(finding, pageByBlock),
                        finding.describe()))
                .sorted(Comparator.comparingInt(JobFinding::pageNo)
                        .thenComparing(JobFinding::blockId))
                .toList();
    }

    /** Exhaustive, so a new finding type cannot reach the job without a stable name. */
    static String kind(QualityFinding finding) {
        return switch (finding) {
            case QualityFinding.NumericMismatch ignored -> "NUMERIC_MISMATCH";
            case QualityFinding.EmptyTranslation ignored -> "EMPTY_TRANSLATION";
            case QualityFinding.ImplausibleLength ignored -> "IMPLAUSIBLE_LENGTH";
            case QualityFinding.UnrestoredPlaceholder ignored -> "UNRESTORED_PLACEHOLDER";
            case QualityFinding.MissingBlock ignored -> "MISSING_BLOCK";
            case QualityFinding.Refused ignored -> "REFUSED";
            case QualityFinding.Overflow ignored -> "OVERFLOW";
        };
    }

    private static int pageOf(QualityFinding finding, Map<String, Integer> pageByBlock) {
        if (finding instanceof QualityFinding.Overflow overflow) return overflow.pageNo();
        return pageByBlock.getOrDefault(finding.blockId(), 0);
    }
}
