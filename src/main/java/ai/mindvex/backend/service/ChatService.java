package ai.mindvex.backend.service;

import ai.mindvex.backend.dto.ChatMessageRequest;
import ai.mindvex.backend.dto.ChatMessageResponse;
import ai.mindvex.backend.dto.ChatRequest;
import ai.mindvex.backend.dto.ChatResponse;
import ai.mindvex.backend.entity.Chat;
import ai.mindvex.backend.entity.ChatMessage;
import ai.mindvex.backend.entity.Workspace;
import ai.mindvex.backend.exception.ResourceNotFoundException;
import ai.mindvex.backend.repository.ChatMessageRepository;
import ai.mindvex.backend.repository.ChatRepository;
import ai.mindvex.backend.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatRepository chatRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final WorkspaceRepository workspaceRepository;

    @Transactional
    public ChatResponse createChat(Long workspaceId, ChatRequest request, Long userId) {
        log.info("Creating chat in workspace {} for user: {}", workspaceId, userId);

        // Verify workspace ownership
        Workspace workspace = workspaceRepository.findByIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace not found or access denied"));

        Chat chat = Chat.builder()
                .workspaceId(workspaceId)
                .title(request.getTitle())
                .build();

        chat = chatRepository.save(chat);
        log.info("Chat created with ID: {}", chat.getId());

        return mapToResponse(chat);
    }

    @Transactional(readOnly = true)
    public List<ChatResponse> getWorkspaceChats(Long workspaceId, Long userId) {
        log.info("Fetching chats for workspace {} and user: {}", workspaceId, userId);

        // Verify workspace ownership
        workspaceRepository.findByIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace not found or access denied"));

        List<Chat> chats = chatRepository.findByWorkspaceId(workspaceId);

        return chats.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ChatResponse getChatById(Long chatId, Long userId) {
        log.info("Fetching chat {} for user: {}", chatId, userId);

        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat not found"));

        // Verify workspace ownership
        workspaceRepository.findByIdAndUserId(chat.getWorkspaceId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Access denied"));

        return mapToResponse(chat);
    }

    @Transactional
    public ChatMessageResponse addMessage(Long chatId, ChatMessageRequest request, Long userId) {
        log.info("Adding message to chat {} for user: {}", chatId, userId);

        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat not found"));

        // Verify workspace ownership
        workspaceRepository.findByIdAndUserId(chat.getWorkspaceId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Access denied"));

        ChatMessage message = ChatMessage.builder()
                .chatId(chatId)
                .role(request.getRole())
                .content(request.getContent())
                .metadata(request.getMetadata())
                .build();

        message = chatMessageRepository.save(message);
        log.info("Message added to chat {} with ID: {}", chatId, message.getId());

        return mapToMessageResponse(message);
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getChatMessages(Long chatId, Long userId) {
        log.info("Fetching messages for chat {} and user: {}", chatId, userId);

        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat not found"));

        // Verify workspace ownership
        workspaceRepository.findByIdAndUserId(chat.getWorkspaceId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Access denied"));

        List<ChatMessage> messages = chatMessageRepository.findByChatIdOrderByCreatedAtAsc(chatId);

        return messages.stream()
                .map(this::mapToMessageResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteChat(Long chatId, Long userId) {
        log.info("Deleting chat {} for user: {}", chatId, userId);

        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat not found"));

        // Verify workspace ownership
        workspaceRepository.findByIdAndUserId(chat.getWorkspaceId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Access denied"));

        chatRepository.delete(chat);
        log.info("Chat {} deleted successfully", chatId);
    }

    private ChatResponse mapToResponse(Chat chat) {
        int messageCount = chatMessageRepository.findByChatIdOrderByCreatedAtAsc(chat.getId()).size();

        return ChatResponse.builder()
                .id(chat.getId())
                .workspaceId(chat.getWorkspaceId())
                .title(chat.getTitle())
                .createdAt(chat.getCreatedAt())
                .updatedAt(chat.getUpdatedAt())
                .messageCount(messageCount)
                .build();
    }

    private ChatMessageResponse mapToMessageResponse(ChatMessage message) {
        return ChatMessageResponse.builder()
                .id(message.getId())
                .chatId(message.getChatId())
                .role(message.getRole())
                .content(message.getContent())
                .metadata(message.getMetadata())
                .createdAt(message.getCreatedAt())
                .build();
    }
}
