package com.waygo.backend.repository;

import com.waygo.backend.entity.PassengerChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PassengerChatMessageRepository extends JpaRepository<PassengerChatMessage, Long> {
    List<PassengerChatMessage> findByPassengerIdOrderByCreatedAtAsc(Long passengerId);
    void deleteByPassengerId(Long passengerId);

    @org.springframework.data.jpa.repository.Query("SELECT m FROM PassengerChatMessage m WHERE m.id IN (SELECT MAX(m2.id) FROM PassengerChatMessage m2 GROUP BY m2.passenger.id)")
    List<PassengerChatMessage> findLatestMessagesGroupedByPassenger();
}
