package com.att.tdp.issueflow.user;

import com.att.tdp.issueflow.user.dto.UserCreateDto;
import com.att.tdp.issueflow.user.dto.UserResponseDto;
import com.att.tdp.issueflow.user.dto.UserUpdateDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public List<UserResponseDto> getAll() {
        return userService.findAll().stream().map(UserResponseDto::from).toList();
    }

    @GetMapping("/{userId}")
    public UserResponseDto getById(@PathVariable Long userId) {
        return UserResponseDto.from(userService.findById(userId));
    }

    @PostMapping
    public UserResponseDto create(@Valid @RequestBody UserCreateDto dto) {
        return UserResponseDto.from(userService.create(dto));
    }

    @PatchMapping("/{userId}")
    public ResponseEntity<Void> update(@PathVariable Long userId,
                                       @Valid @RequestBody UserUpdateDto dto) {
        userService.update(userId, dto);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> delete(@PathVariable Long userId) {
        userService.delete(userId);
        return ResponseEntity.ok().build();
    }
}
