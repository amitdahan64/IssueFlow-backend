package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.ticket.dto.DeletedTicketDto;
import com.att.tdp.issueflow.ticket.dto.TicketCreateDto;
import com.att.tdp.issueflow.ticket.dto.TicketResponseDto;
import com.att.tdp.issueflow.ticket.dto.TicketUpdateDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    @GetMapping
    public List<TicketResponseDto> listByProject(@RequestParam Long projectId) {
        return ticketService.findAllByProject(projectId).stream()
                .map(TicketResponseDto::from).toList();
    }

    @GetMapping("/{ticketId}")
    public TicketResponseDto getById(@PathVariable Long ticketId) {
        return TicketResponseDto.from(ticketService.findById(ticketId));
    }

    @PostMapping
    public TicketResponseDto create(@Valid @RequestBody TicketCreateDto dto) {
        return TicketResponseDto.from(ticketService.create(dto));
    }

    @PatchMapping("/{ticketId}")
    public ResponseEntity<Void> update(@PathVariable Long ticketId,
                                       @Valid @RequestBody TicketUpdateDto dto) {
        ticketService.update(ticketId, dto);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{ticketId}")
    public ResponseEntity<Void> softDelete(@PathVariable Long ticketId) {
        ticketService.softDelete(ticketId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/deleted")
    @PreAuthorize("hasRole('ADMIN')")
    public List<DeletedTicketDto> listDeleted(@RequestParam Long projectId) {
        return ticketService.findDeletedByProject(projectId).stream()
                .map(DeletedTicketDto::from).toList();
    }

    @PostMapping("/{ticketId}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> restore(@PathVariable Long ticketId) {
        ticketService.restore(ticketId);
        return ResponseEntity.ok().build();
    }
}
