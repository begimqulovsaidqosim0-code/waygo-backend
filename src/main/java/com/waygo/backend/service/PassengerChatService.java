package com.waygo.backend.service;

import com.waygo.backend.entity.PassengerChatMessage;
import com.waygo.backend.entity.User;
import com.waygo.backend.repository.PassengerChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PassengerChatService {

    private final PassengerChatMessageRepository messageRepository;

    public List<PassengerChatMessage> getChatHistory(Long passengerId) {
        return messageRepository.findByPassengerIdOrderByCreatedAtAsc(passengerId);
    }

    @Transactional
    public PassengerChatMessage sendPassengerMessage(User passenger, String text) {
        PassengerChatMessage message = PassengerChatMessage.builder()
                .passenger(passenger)
                .messageText(text)
                .sender(PassengerChatMessage.SenderType.PASSENGER)
                .build();

        return messageRepository.save(message);
    }
}
