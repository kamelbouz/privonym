package com.privonym.repository.oracle;

import com.privonym.application.service.JobReport;
import com.privonym.application.service.ValidationMetric;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Dependency-free PDF renderer for immutable execution reports. */
public final class ExecutionReportPdfWriter {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ISO_INSTANT;

    private ExecutionReportPdfWriter() { }

    public static Path write(JobReport report, Instant generatedAt) {
        return write(report, "SUCCEEDED", "", generatedAt);
    }

    public static Path write(JobReport report, String finalStatus,
            String failureDetail, Instant generatedAt) {
        Path directory = Path.of(System.getProperty("privonym.report.directory", "reports"))
                .toAbsolutePath().normalize();
        String name = "privonym-job-" + report.jobId().value() + "-"
                + FILE_TIME.format(generatedAt) + ".pdf";
        Path output = directory.resolve(name);
        try {
            Files.createDirectories(directory);
            Files.write(output, render(report, finalStatus, failureDetail, generatedAt),
                    StandardOpenOption.CREATE_NEW);
            return output;
        } catch (IOException error) {
            throw new IllegalStateException("Unable to write execution PDF report: " + error.getMessage(), error);
        }
    }

    static byte[] render(JobReport report, Instant generatedAt) throws IOException {
        return render(report, "SUCCEEDED", "", generatedAt);
    }

    static byte[] render(JobReport report, String finalStatus,
            String failureDetail, Instant generatedAt) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("PRIVONYM - EXECUTION REPORT");
        lines.add("");
        lines.add("Job ID: " + report.jobId().value());
        lines.add("Reference: " + report.jobReference().value());
        lines.add("Final status: " + finalStatus);
        lines.add("Generated at (UTC): " + DISPLAY_TIME.format(generatedAt));
        lines.add("Execution time: " + duration(report.executionDurationSeconds()));
        boolean completed = "SUCCEEDED".equals(finalStatus);
        lines.add((completed ? "Succeeded steps: " : "Completed table steps: ") + report.succeededSteps());
        lines.add("Rows processed: " + report.rowsProcessed());
        if (failureDetail != null && !failureDetail.isBlank()) {
            lines.add("Failure: " + failureDetail);
        }
        lines.add("");
        if (!completed) {
            lines.add("PARTIAL EXECUTION - FINAL VALIDATION NOT CONFIRMED");
            lines.add("Recorded processed rows: " + report.rowsProcessed());
            lines.add("Checkpoint progress is not a completed data validation.");
        } else {
            lines.add("TABLE VALIDATION");
            lines.add("Table | Expected | Anonymized | Difference | Status");
            report.metrics().stream().filter(metric -> metric.name().startsWith("EXPECTED_"))
                    .sorted(Comparator.comparing(ValidationMetric::name)).forEach(expected -> {
                        String key = expected.name().substring("EXPECTED_".length());
                        long anonymized = metric(report, "ANONYMIZED_" + key);
                        long difference = anonymized - expected.value();
                        lines.add(key + " | " + expected.value() + " | " + anonymized + " | "
                                + signed(difference) + " | " + (difference == 0 ? "PASSED" : "FAILED"));
                    });
            lines.add("");
            lines.add("TOTALS");
            lines.add("Expected rows: " + metric(report, "ROWS_EXPECTED_TOTAL"));
            lines.add("Anonymized rows: " + metric(report, "ROWS_ANONYMIZED_TOTAL"));
        }
        lines.add("");
        lines.add("This report was generated automatically after the execution attempt.");
        return simplePdf(lines);
    }

    private static long metric(JobReport report, String name) {
        return report.metrics().stream().filter(item -> item.name().equals(name))
                .mapToLong(ValidationMetric::value).findFirst().orElse(0);
    }

    private static String signed(long value) { return value > 0 ? "+" + value : Long.toString(value); }

    private static String duration(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = totalSeconds % 3600 / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private static byte[] simplePdf(List<String> lines) throws IOException {
        List<String> pageStreams = new ArrayList<>();
        for (int offset = 0; offset < lines.size(); offset += 38) {
            List<String> page = lines.subList(offset, Math.min(offset + 38, lines.size()));
            StringBuilder stream = new StringBuilder("BT\n/F1 11 Tf\n50 790 Td\n14 TL\n");
            for (int index = 0; index < page.size(); index++) {
                if (index > 0) stream.append("T*\n");
                stream.append('(').append(escape(page.get(index))).append(") Tj\n");
            }
            stream.append("ET\n");
            pageStreams.add(stream.toString());
        }
        int pageCount = pageStreams.size();
        int fontId = 3 + pageCount * 2;
        int objectCount = fontId;
        List<byte[]> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>".getBytes(StandardCharsets.US_ASCII));
        StringBuilder kids = new StringBuilder("[");
        for (int page = 0; page < pageCount; page++) kids.append(3 + page * 2).append(" 0 R ");
        kids.append(']');
        objects.add(("<< /Type /Pages /Kids " + kids + " /Count " + pageCount + " >>")
                .getBytes(StandardCharsets.US_ASCII));
        for (int page = 0; page < pageCount; page++) {
            int contentId = 4 + page * 2;
            objects.add(("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 "
                    + fontId + " 0 R >> >> /Contents " + contentId + " 0 R >>")
                    .getBytes(StandardCharsets.US_ASCII));
            byte[] stream = pageStreams.get(page).getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            content.write(("<< /Length " + stream.length + " >>\nstream\n").getBytes(StandardCharsets.US_ASCII));
            content.write(stream);
            content.write("endstream".getBytes(StandardCharsets.US_ASCII));
            objects.add(content.toByteArray());
        }
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        pdf.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        long[] offsets = new long[objectCount + 1];
        for (int index = 0; index < objects.size(); index++) {
            offsets[index + 1] = pdf.size();
            pdf.write(((index + 1) + " 0 obj\n").getBytes(StandardCharsets.US_ASCII));
            pdf.write(objects.get(index));
            pdf.write("\nendobj\n".getBytes(StandardCharsets.US_ASCII));
        }
        long xref = pdf.size();
        pdf.write(("xref\n0 " + (objectCount + 1) + "\n0000000000 65535 f \n")
                .getBytes(StandardCharsets.US_ASCII));
        for (int index = 1; index <= objectCount; index++) {
            pdf.write(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[index])
                    .getBytes(StandardCharsets.US_ASCII));
        }
        pdf.write(("trailer\n<< /Size " + (objectCount + 1) + " /Root 1 0 R >>\nstartxref\n"
                + xref + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
        return pdf.toByteArray();
    }

    private static String escape(String value) {
        return value.replaceAll("[^\\x20-\\x7E]", "?")
                .replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }
}
