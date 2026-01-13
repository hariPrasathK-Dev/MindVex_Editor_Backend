package ai.mindvex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class ChatMessageRequest {

    @NotBlank(message = "Role is required")
    @Pattern(regexp = "user|assistant|system", message = "Role must be 'user', 'assistant', or 'system'")
    private String role;

    @NotBlank(message = "Content is required")
    private String content;

    private Map<String, Object> metadata = new HashMap<>();
}
