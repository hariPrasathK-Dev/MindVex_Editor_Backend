package ai.mindvex.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class MindVexBackendApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(MindVexBackendApplication.class, args);
    }
}
