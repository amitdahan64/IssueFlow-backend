package com.att.tdp.issueflow.project.dto;

public record WorkloadEntryDto(
        Long userId,
        String username,
        long openTicketCount
) {}
