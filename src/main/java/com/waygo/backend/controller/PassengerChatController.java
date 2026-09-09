package com.waygo.backend.controller;

import com.waygo.backend.dto.ApiResponse;
import com.waygo.backend.entity.PassengerChatMessage;
import com.waygo.backend.entity.User;
import com.waygo.backend.security.SecurityUtils;
import com.waygo.backend.service.PassengerChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/passenger-chat")
@RequiredArgsConstructor
public class PassengerChatController {

    private final PassengerChatService passengerChatService;
    private final SecurityUtils securityUtils;

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<PassengerChatMessage>>> getHistory() {
        User user = securityUtils.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Siz tizimga kirmagansiz"));
        }

        List<PassengerChatMessage> history = passengerChatService.getChatHistory(user.getId());
        return ResponseEntity.ok(ApiResponse.success(history, "Muloqotlar tarixi yuklandi"));
    }

    @PostMapping("/send")
    public ResponseEntity<ApiResponse<PassengerChatMessage>> sendMessage(@RequestBody Map<String, String> payload) {
        User user = securityUtils.getCurrentUser();
        if (user == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Siz tizimga kirmagansiz"));
        }

        String text = payload.get("messageText");
        if (text == null || text.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Xabar matni bo'sh bo'lishi mumkin emas"));
        }

        PassengerChatMessage msg = passengerChatService.sendPassengerMessage(user, text);
        return ResponseEntity.ok(ApiResponse.success(msg, "Xabar muvaffaqiyatli jo'natildi"));
    }
}
