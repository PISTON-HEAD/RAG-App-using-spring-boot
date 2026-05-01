package com.ragapp.query;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.ragapp.dto.ChatTurn;

@Service
public class ChatHistoryService {

    @Value("${app.rag.chat-history.max-turns:5}")
    private int maxTurns;

    @Value("${app.rag.chat-history.ttl-minutes:30}")
    private int ttlMinutes;

    private record SessionData(Deque<ChatTurn> turns, AtomicLong lastActiveMs) {}

    private final ConcurrentHashMap<String, SessionData> sessions = new ConcurrentHashMap<>();

    /**
     * Returns the chat history for a session (snapshot, ordered oldest → newest).
     * Returns an empty list if the session does not exist.
     */
    public List<ChatTurn> getHistory(String sessionId) {
        SessionData data = sessions.get(sessionId);
        if (data == null) {
            return List.of();
        }
        data.lastActiveMs().set(System.currentTimeMillis());
        synchronized (data.turns()) {
            return new ArrayList<>(data.turns());
        }
    }

    /**
     * Appends a Q&A turn to the session history.
     * If the window exceeds maxTurns, the oldest turn is evicted.
     */
    public void addTurn(String sessionId, String question, String answer) {
        SessionData data = sessions.computeIfAbsent(sessionId,
                id -> new SessionData(new ArrayDeque<>(), new AtomicLong(System.currentTimeMillis())));

        data.lastActiveMs().set(System.currentTimeMillis());
        synchronized (data.turns()) {
            if (data.turns().size() >= maxTurns) {
                data.turns().pollFirst();   // evict oldest turn
            }
            data.turns().addLast(new ChatTurn(question, answer, LocalDateTime.now()));
        }
    }

    /**
     * Evicts sessions that have been idle longer than ttl-minutes.
     * Runs every 5 minutes automatically.
     */
    @Scheduled(fixedRate = 300_000)
    public void evictExpiredSessions() {
        long cutoffMs = System.currentTimeMillis() - (ttlMinutes * 60_000L);
        sessions.entrySet().removeIf(e -> e.getValue().lastActiveMs().get() < cutoffMs);
    }
}
