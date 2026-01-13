package ai.mindvex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatRequest {

    @NotBlank(message = "Chat title is required")
    @Size(min = 1, max = 255, message = "Chat title must be between 1 and 255 characters")
    private String title;
}
