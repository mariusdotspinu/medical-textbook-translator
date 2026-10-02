package com.medlearn.translation.application;

import com.medlearn.jobs.domain.JobFinding;
import com.medlearn.translation.domain.QualityFinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FindingReportTest {

    @Test
    @DisplayName("findings carry their source page and are listed in book order")
    void mapsAndSortsByPage() {
        List<JobFinding> report = FindingReport.toJobFindings(
                List.of(
                        new QualityFinding.Refused("p9-b2", "cbrn"),
                        new QualityFinding.Overflow("p4-b1", 4),
                        new QualityFinding.NumericMismatch("p2-b5", "300", "30")),
                Map.of("p9-b2", 9, "p2-b5", 2));

        assertThat(report).extracting(JobFinding::pageNo).containsExactly(2, 4, 9);
        assertThat(report).extracting(JobFinding::kind)
                .containsExactly("NUMERIC_MISMATCH", "OVERFLOW", "REFUSED");
        assertThat(report.getFirst().message()).contains("[300]").contains("[30]");
        assertThat(report.getFirst().blockId()).isEqualTo("p2-b5");
    }

    @Test
    @DisplayName("a block whose page is unknown is reported on page 0 rather than dropped")
    void unknownPageIsKept() {
        List<JobFinding> report = FindingReport.toJobFindings(
                List.of(new QualityFinding.MissingBlock("p7-b1")), Map.of());

        assertThat(report).singleElement()
                .satisfies(finding -> assertThat(finding.pageNo()).isZero())
                .satisfies(finding -> assertThat(finding.kind()).isEqualTo("MISSING_BLOCK"));
    }
}
