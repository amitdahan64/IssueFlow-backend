package com.att.tdp.issueflow.csv.dto;

import java.util.List;

public record ImportResultDto(
        int created,
        int failed,
        List<RowErrorDto> errors
) {}
