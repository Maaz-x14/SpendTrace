package com.maazahmad.whatsapptranscriber.dto;

import lombok.Data;
import java.util.List;

@Data
public class WhatsAppWebhookDto {
    private List<Entry> entry;

    @Data
    public static class Entry {
        private List<Change> changes;
    }

    @Data
    public static class Change {
        private Value value;
    }

    @Data
    public static class Value {
        private List<Message> messages;
    }

    @Data
    public static class Message {
        private String id; // Added for idempotency
        private String type;
        private String from;
        private Audio audio;
        private Text text; // Added this
    }

    @Data
    public static class Audio {
        private String id;
    }

    @Data
    public static class Text { // Added this nested class
        private String body;
    }
}