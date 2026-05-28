package com.att.tdp.issueflow.csv.dto;

public record RowErrorDto(
        long row,
        String message
) {}
