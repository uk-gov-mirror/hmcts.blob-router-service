package uk.gov.hmcts.reform.blobrouter.services.report;

import org.springframework.stereotype.Service;
import uk.gov.hmcts.reform.blobrouter.data.reports.ReportRepository;
import uk.gov.hmcts.reform.blobrouter.model.out.EnvelopeSummaryItem;
import uk.gov.hmcts.reform.blobrouter.model.out.reports.EnvelopeCountSummaryReportItem;
import uk.gov.hmcts.reform.blobrouter.util.ZeroRowFiller;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

import static java.util.stream.Collectors.toList;
import static uk.gov.hmcts.reform.blobrouter.util.TimeZones.EUROPE_LONDON_ZONE_ID;

@Service
public class ReportService {
    private final ReportRepository reportRepository;
    private final ZeroRowFiller zeroRowFiller;

    public ReportService(ReportRepository reportRepository, ZeroRowFiller zeroRowFiller) {
        this.reportRepository = reportRepository;
        this.zeroRowFiller = zeroRowFiller;
    }

    public List<EnvelopeSummaryItem> getDailyReport(LocalDate date) {

        var from = date.atStartOfDay().atZone(EUROPE_LONDON_ZONE_ID).toInstant();
        var to = date.atStartOfDay().plusDays(1).atZone(EUROPE_LONDON_ZONE_ID).toInstant();

        return reportRepository
            .getEnvelopeSummary(from, to)
            .stream()
            .map(s -> new EnvelopeSummaryItem(
                s.container,
                s.fileName,
                toLocalDate(s.fileCreatedAt),
                toLocalTime(s.fileCreatedAt),
                toLocalDate(s.dispatchedAt),
                toLocalTime(s.dispatchedAt),
                s.status.name(),
                s.isDeleted
            ))
            .collect(toList());
    }

    public List<EnvelopeCountSummaryReportItem> getCountFor(LocalDate date, boolean includeTestContainer) {
        long start = System.currentTimeMillis();
        final List<EnvelopeCountSummaryReportItem> reportResult = zeroRowFiller
            .fill(reportRepository.getReportFor(date).stream().map(this::fromDb).collect(toList()), date)
            .stream()
            .filter(it -> includeTestContainer || !Objects.equals(it.container, TEST_CONTAINER))
            .collect(toList());
        log.info("Count summary report took {} ms", System.currentTimeMillis() - start);
        return reportResult;
    }

    private LocalDate toLocalDate(Instant instant) {
        if (instant != null) {
            return LocalDateTime.ofInstant(instant, EUROPE_LONDON_ZONE_ID).toLocalDate();
        }
        return null;
    }

    private LocalTime toLocalTime(Instant instant) {
        if (instant != null) {
            return LocalTime.ofInstant(instant, EUROPE_LONDON_ZONE_ID);
        }
        return null;
    }

}
