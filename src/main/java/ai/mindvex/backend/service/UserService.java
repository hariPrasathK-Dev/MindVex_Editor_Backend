package ai.mindvex.backend.service;

import ai.mindvex.backend.dto.*;
import ai.mindvex.backend.entity.PasswordResetToken;
import ai.mindvex.backend.entity.User;
import ai.mindvex.backend.exception.ResourceNotFoundException;
import ai.mindvex.backend.exception.UnauthorizedException;
import ai.mindvex.backend.repository.PasswordResetTokenRepository;
import ai.mindvex.backend.repository.UserRepository;
import ai.mindvex.backend.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

        private final UserRepository userRepository;
        private final PasswordResetTokenRepository passwordResetTokenRepository;
        private final PasswordEncoder passwordEncoder;
        private final JwtService jwtService;
        private final AuthenticationManager authenticationManager;
        private final UserDetailsService userDetailsService;
        private final EmailService emailService;

        /**
         * Direct registration with email and password
         */
        @Transactional
        public AuthResponse register(RegisterRequest request) {
                if (userRepository.existsByEmail(request.getEmail())) {
                        throw new IllegalArgumentException("Email already registered");
                }

                User user = User.builder()
                                .email(request.getEmail())
                                .passwordHash(passwordEncoder.encode(request.getPassword()))
                                .fullName(request.getFullName())
                                .build();

                user = userRepository.save(user);
                log.info("User registered successfully: {}", request.getEmail());

                UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
                String jwtToken = jwtService.generateToken(userDetails);
                String refreshToken = jwtService.generateRefreshToken(userDetails);

                return AuthResponse.builder()
                                .token(jwtToken)
                                .refreshToken(refreshToken)
                                .user(mapToUserResponse(user))
                                .build();
        }

        /**
         * Direct login with email and password
         */
        public AuthResponse login(LoginRequest request) {
                authenticationManager.authenticate(
                                new UsernamePasswordAuthenticationToken(
                                                request.getEmail(),
                                                request.getPassword()));

                User user = userRepository.findByEmail(request.getEmail())
                                .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));

                log.info("User logged in successfully: {}", request.getEmail());

                UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
                String jwtToken = jwtService.generateToken(userDetails);
                String refreshToken = jwtService.generateRefreshToken(userDetails);

                return AuthResponse.builder()
                                .token(jwtToken)
                                .refreshToken(refreshToken)
                                .user(mapToUserResponse(user))
                                .build();
        }

        public UserResponse getCurrentUser(String email) {
                User user = userRepository.findByEmail(email)
                                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
                return mapToUserResponse(user);
        }

        /**
         * Request password reset - generates token and sends email
         */
        @Transactional
        public MessageResponse requestPasswordReset(ForgotPasswordRequest request) {
                User user = userRepository.findByEmail(request.getEmail())
                                .orElse(null);

                // For security, always return success even if email doesn't exist
                if (user == null) {
                        log.warn("Password reset requested for non-existent email: {}", request.getEmail());
                        return MessageResponse.success("If the email exists, a password reset link has been sent.");
                }

                // Only allow password reset for local auth users
                if (!"local".equals(user.getProvider())) {
                        log.warn("Password reset requested for OAuth user: {}", request.getEmail());
                        return MessageResponse.success("If the email exists, a password reset link has been sent.");
                }

                // Delete any existing tokens for this user
                passwordResetTokenRepository.deleteByUser(user);

                // Generate new token
                String token = UUID.randomUUID().toString();
                PasswordResetToken resetToken = PasswordResetToken.builder()
                                .token(token)
                                .user(user)
                                .expiryDate(LocalDateTime.now().plusHours(1))
                                .used(false)
                                .build();

                passwordResetTokenRepository.save(resetToken);
                log.info("Password reset token generated for user: {}", user.getEmail());

                // Send email
                try {
                        emailService.sendPasswordResetEmail(user.getEmail(), token, user.getFullName());
                } catch (Exception e) {
                        log.error("Failed to send password reset email to: {}", user.getEmail(), e);
                        throw new RuntimeException("Failed to send password reset email. Please try again later.");
                }

                return MessageResponse.success("If the email exists, a password reset link has been sent.");
        }

        /**
         * Reset password using token
         */
        @Transactional
        public MessageResponse resetPassword(ResetPasswordRequest request) {
                PasswordResetToken resetToken = passwordResetTokenRepository.findByTokenAndUsedFalse(request.getToken())
                                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired reset token"));

                if (resetToken.isExpired()) {
                        throw new IllegalArgumentException("Reset token has expired");
                }

                User user = resetToken.getUser();

                // Update password
                user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
                userRepository.save(user);

                // Mark token as used
                resetToken.setUsed(true);
                passwordResetTokenRepository.save(resetToken);

                log.info("Password reset successfully for user: {}", user.getEmail());

                return MessageResponse.success("Password has been reset successfully");
        }

        /**
         * Verify reset token validity
         */
        public MessageResponse verifyResetToken(String token) {
                PasswordResetToken resetToken = passwordResetTokenRepository.findByTokenAndUsedFalse(token)
                                .orElse(null);

                if (resetToken == null || resetToken.isExpired()) {
                        return MessageResponse.error("Invalid or expired reset token");
                }

                return MessageResponse.success("Token is valid");
        }

        private UserResponse mapToUserResponse(User user) {
                return UserResponse.builder()
                                .id(user.getId())
                                .email(user.getEmail())
                                .fullName(user.getFullName())
                                .createdAt(user.getCreatedAt())
                                .build();
        }
}