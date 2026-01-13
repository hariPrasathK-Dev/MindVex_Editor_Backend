package ai.mindvex.backend.controller;

import ai.mindvex.backend.dto.ChatMessageRequest;
import ai.mindvex.backend.dto.ChatMessageResponse;
import ai.mindvex.backend.dto.ChatRequest;
import ai.mindvex.backend.dto.ChatResponse;
import ai.mindvex.backend.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Chats", description = "Chat and message management endpoints")
@SecurityRequirement(name = "Bearer Authentication")
public class ChatController {

    private final ChatService chatService;
    private final ai.mindvex.backend.repository.UserRepository userRepository;

    @PostMapping("/api/workspaces/{workspaceId}/chats")
    @Operation(summary = "Create a new chat", description = "Creates a new chat in the specified workspace")
    public ResponseEntity<ChatResponse> createChat(
            @PathVariable Long workspaceId,
            @Valid @RequestBody ChatRequest request,
            Authentication authentication) {

        Long userId = getUserIdFromAuthentication(authentication);
        ChatResponse response = chatService.createChat(workspaceId, request, userId);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping("/api/workspaces/{workspaceId}/chats")
    @Operation(summary = "Get workspace chats", description = "Retrieves all chats in the specified workspace")
    public ResponseEntity<List<ChatResponse>> getWorkspaceChats(
            @PathVariable Long workspaceId,
            Authentication authentication) {

        Long userId = getUserIdFromAuthentication(authentication);
        List<ChatResponse> chats = chatService.getWorkspaceChats(workspaceId, userId);
        return ResponseEntity.ok(chats);
    }

    @GetMapping("/api/chats/{id}")
    @Operation(summary = "Get chat by ID", description = "Retrieves a specific chat by ID")
    public ResponseEntity<ChatResponse> getChatById(
            @PathVariable Long id,
            Authentication authentication) {

        Long userId = getUserIdFromAuthentication(authentication);
        ChatResponse response = chatService.getChatById(id, userId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/chats/{id}/messages")
    @Operation(summary = "Add message to chat", description = "Adds a new message to the specified chat")
    public ResponseEntity<ChatMessageResponse> addMessage(
            @PathVariable Long id,
            @Valid @RequestBody ChatMessageRequest request,
            Authentication authentication) {

        Long userId = getUserIdFromAuthentication(authentication);
        ChatMessageResponse response = chatService.addMessage(id, request, userId);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping("/api/chats/{id}/messages")
    @Operation(summary = "Get chat messages", description = "Retrieves all messages in the specified chat")
    public ResponseEntity<List<ChatMessageResponse>> getChatMessages(
            @PathVariable Long id,
            Authentication authentication) {

        Long userId = getUserIdFromAuthentication(authentication);
        List<ChatMessageResponse> messages = chatService.getChatMessages(id, userId);
        return ResponseEntity.ok(messages);
    }

    @DeleteMapping("/api/chats/{id}")
    @Operation(summary = "Delete chat", description = "Deletes a chat and all its messages")
    public ResponseEntity<Void> deleteChat(
            @PathVariable Long id,
            Authentication authentication) {

        Long userId = getUserIdFromAuthentication(authentication);
        chatService.deleteChat(id, userId);
        return ResponseEntity.noContent().build();
    }

    private Long getUserIdFromAuthentication(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        return userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"))
                .getId();
    }
}
