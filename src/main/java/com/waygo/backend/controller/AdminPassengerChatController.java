package com.waygo.backend.controller;

import com.waygo.backend.entity.PassengerChatMessage;
import com.waygo.backend.entity.User;
import com.waygo.backend.repository.PassengerChatMessageRepository;
import com.waygo.backend.repository.UserRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.waygo.backend.service.NotificationService;

@Controller
@RequestMapping("/admin/passenger-chat")
@RequiredArgsConstructor
public class AdminPassengerChatController {

    private final PassengerChatMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @GetMapping
    public String chatPage(Model model) {
        model.addAttribute("title", "Yo'lovchilar Chati");
        model.addAttribute("activeItem", "passenger_chat");
        return "admin/passenger_chat";
    }

    @GetMapping("/passengers")
    @ResponseBody
    public ResponseEntity<List<ChatPassengerResponse>> getChatPassengers() {
        List<PassengerChatMessage> latestMessages = messageRepository.findLatestMessagesGroupedByPassenger();

        latestMessages.sort((m1, m2) -> m2.getCreatedAt().compareTo(m1.getCreatedAt()));

        List<ChatPassengerResponse> responses = latestMessages.stream()
                .map(m -> {
                    User passenger = m.getPassenger();
                    return ChatPassengerResponse.builder()
                            .id(passenger.getId())
                            .fullName(passenger.getFullName() != null ? passenger.getFullName() : "Ismsiz")
                            .phone(passenger.getPhone())
                            .lastMessage(m.getMessageText())
                            .lastMessageTime(m.getCreatedAt())
                            .build();
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/history/{passengerId}")
    @ResponseBody
    public ResponseEntity<List<PassengerChatMessage>> getChatHistory(@PathVariable Long passengerId) {
        List<PassengerChatMessage> history = messageRepository.findByPassengerIdOrderByCreatedAtAsc(passengerId);
        return ResponseEntity.ok(history);
    }

    @PostMapping("/send")
    @ResponseBody
    public ResponseEntity<PassengerChatMessage> sendAdminMessage(@RequestBody Map<String, Object> payload) {
        Long passengerId = Long.valueOf(payload.get("passengerId").toString());
        String messageText = payload.get("messageText").toString();

        if (messageText == null || messageText.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        User passenger = userRepository.findById(passengerId)
                .orElseThrow(() -> new IllegalArgumentException("Passenger not found"));

        PassengerChatMessage msg = PassengerChatMessage.builder()
                .passenger(passenger)
                .messageText(messageText.trim())
                .sender(PassengerChatMessage.SenderType.ADMIN)
                .build();

        PassengerChatMessage saved = messageRepository.save(msg);
        notificationService.notifyNewPassengerChatMessage(saved);
        return ResponseEntity.ok(saved);
    }

    @Data
    @Builder
    public static class ChatPassengerResponse {
        private Long id;
        private String fullName;
        private String phone;
        private String lastMessage;
        private LocalDateTime lastMessageTime;
    }
}
