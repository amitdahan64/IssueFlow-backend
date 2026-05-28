package com.att.tdp.issueflow.csv;

import com.att.tdp.issueflow.csv.dto.ImportResultDto;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/tickets")
@RequiredArgsConstructor
public class CsvController {

    private final CsvService csvService;

    @GetMapping("/export")
    public void export(@RequestParam Long projectId, HttpServletResponse response) throws IOException {
        response.setStatus(200);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"tickets-" + projectId + ".csv\"");
        csvService.exportProjectTickets(projectId, response.getOutputStream());
        response.getOutputStream().flush();
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResultDto importFile(@RequestParam Long projectId,
                                      @RequestParam("file") MultipartFile file) {
        return csvService.importTickets(projectId, file);
    }
}
