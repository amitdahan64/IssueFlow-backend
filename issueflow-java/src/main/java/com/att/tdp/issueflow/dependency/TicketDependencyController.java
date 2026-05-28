package com.att.tdp.issueflow.dependency;

import com.att.tdp.issueflow.dependency.dto.BlockerTicketDto;
import com.att.tdp.issueflow.dependency.dto.CreateDependencyDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/tickets/{ticketId}/dependencies")
@RequiredArgsConstructor
public class TicketDependencyController {

    private final TicketDependencyService service;

    @PostMapping
    public ResponseEntity<Void> add(@PathVariable Long ticketId,
                                    @Valid @RequestBody CreateDependencyDto body) {
        service.add(ticketId, body.blockedBy());
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public List<BlockerTicketDto> list(@PathVariable Long ticketId) {
        return service.listBlockers(ticketId).stream()
                .map(BlockerTicketDto::from)
                .toList();
    }

    @DeleteMapping("/{blockerId}")
    public ResponseEntity<Void> remove(@PathVariable Long ticketId,
                                       @PathVariable Long blockerId) {
        service.remove(ticketId, blockerId);
        return ResponseEntity.ok().build();
    }
}
