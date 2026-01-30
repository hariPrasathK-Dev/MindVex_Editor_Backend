package ai.mindvex.backend.controller;

import ai.mindvex.backend.dto.*;
import ai.mindvex.backend.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    /**
     * Step 1: Initiate registration - validates data and sends OTP
     */
    @PostMapping("/register")
    public ResponseEntity<OtpResponse> register(@Valid @RequestBody RegisterRequest request) {
        OtpResponse response = userService.initiateRegistration(request);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * Step 1: Initiate login - validates credentials and sends OTP
     */
    @PostMapping("/login")
    public ResponseEntity<OtpResponse> login(@Valid @RequestBody LoginRequest request) {
        OtpResponse response = userService.initiateLogin(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Step 2: Verify OTP and complete authentication
     */
    @PostMapping("/verify-otp")
    public ResponseEntity<AuthResponse> verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
        AuthResponse response = userService.verifyOtpAndAuthenticate(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Resend OTP to email
     */
    @PostMapping("/resend-otp")
    public ResponseEntity<OtpResponse> resendOtp(@Valid @RequestBody ResendOtpRequest request) {
        OtpResponse response = userService.resendOtp(request);
        return ResponseEntity.ok(response);
    }
}
