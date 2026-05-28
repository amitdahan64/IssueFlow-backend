package com.att.tdp.issueflow.csv;

import com.att.tdp.issueflow.common.domain.Priority;
import com.att.tdp.issueflow.common.domain.TicketStatus;
import com.att.tdp.issueflow.common.domain.TicketType;
import com.att.tdp.issueflow.csv.dto.ImportResultDto;
import com.att.tdp.issueflow.csv.dto.RowErrorDto;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketService;
import com.att.tdp.issueflow.ticket.dto.TicketCreateDto;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.QuoteMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CsvService {

    /** Column order per the README contract. */
    private static final String[] HEADERS =
            {"id", "title", "description", "status", "priority", "type", "assigneeId"};

    private final TicketService ticketService;

    /** Streams the project's tickets as CSV to the supplied output stream. */
    public void exportProjectTickets(Long projectId, OutputStream out) throws IOException {
        List<Ticket> tickets = ticketService.findAllByProject(projectId);

        // Quote every field so commas / newlines / embedded quotes survive a round-trip.
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader(HEADERS)
                .setQuoteMode(QuoteMode.ALL_NON_NULL)
                .build();

        try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, format)) {
            for (Ticket t : tickets) {
                printer.printRecord(
                        t.getId(),
                        t.getTitle(),
                        t.getDescription(),
                        t.getStatus(),
                        t.getPriority(),
                        t.getType(),
                        t.getAssigneeId()
                );
            }
        }
    }

    /** Imports a CSV file into the given project. Per-row failures are collected; the batch
     *  continues. Each successful row goes through {@link TicketService#create} so auto-assign,
     *  audit, and lifecycle defaults apply uniformly. */
    @Transactional
    public ImportResultDto importTickets(Long projectId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return new ImportResultDto(0, 0, List.of());
        }

        int created = 0;
        List<RowErrorDto> errors = new ArrayList<>();

        CSVFormat parseFormat = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(false)
                .build();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
             CSVParser parser = parseFormat.parse(reader)) {

            for (CSVRecord record : parser) {
                // record.getRecordNumber() is 1-based and counts data rows after the header,
                // so it lines up with what a user would see in a CSV editor.
                long rowNumber = record.getRecordNumber();
                try {
                    TicketCreateDto dto = toCreateDto(record, projectId);
                    ticketService.create(dto);
                    created++;
                } catch (Exception ex) {
                    errors.add(new RowErrorDto(rowNumber, ex.getMessage()));
                }
            }
        } catch (IOException ex) {
            errors.add(new RowErrorDto(0, "Failed to parse CSV: " + ex.getMessage()));
        }

        return new ImportResultDto(created, errors.size(), errors);
    }

    private TicketCreateDto toCreateDto(CSVRecord record, Long projectId) {
        String title = required(record, "title");
        String description = optional(record, "description");
        TicketStatus status = parseEnum(record, "status", TicketStatus.class);
        Priority priority = parseEnum(record, "priority", Priority.class);
        TicketType type = parseEnum(record, "type", TicketType.class);
        Long assigneeId = optionalLong(record, "assigneeId");
        // Spec 3.4: imported tickets keep their explicit assignee; rows without one go
        // through the auto-assign path because the resolver inspects dto.assigneeId().
        return new TicketCreateDto(title, description, status, priority, type,
                projectId, assigneeId, /*dueDate=*/ null);
    }

    private String required(CSVRecord r, String header) {
        if (!r.isMapped(header)) {
            throw new IllegalArgumentException("Missing column: " + header);
        }
        String value = r.get(header);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + header);
        }
        return value;
    }

    private String optional(CSVRecord r, String header) {
        if (!r.isMapped(header)) return null;
        String value = r.get(header);
        return (value == null || value.isEmpty()) ? null : value;
    }

    private Long optionalLong(CSVRecord r, String header) {
        String s = optional(r, header);
        if (s == null) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid number for " + header + ": '" + s + "'");
        }
    }

    private <E extends Enum<E>> E parseEnum(CSVRecord r, String header, Class<E> type) {
        String value = required(r, header);
        try {
            return Enum.valueOf(type, value.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid " + header + ": '" + value + "'");
        }
    }
}
