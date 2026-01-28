package com.maazahmad.whatsapptranscriber.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maazahmad.whatsapptranscriber.dto.WhatsAppWebhookDto;
import com.maazahmad.whatsapptranscriber.service.GroqService;
import com.maazahmad.whatsapptranscriber.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final WhatsAppService whatsAppService;
    private final GroqService groqService;
    private final ObjectMapper objectMapper; // Injected by Spring

    @Value("${whatsapp.verifyToken}")
    private String verifyToken;

    @GetMapping
    public ResponseEntity<String> verifyWebhook(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.verify_token") String verifyTokenParam,
            @RequestParam("hub.challenge") String challenge) {

        if ("subscribe".equals(mode) && verifyToken.equals(verifyTokenParam)) {
            return ResponseEntity.ok(challenge);
        } else {
            return ResponseEntity.badRequest().body("Verification failed");
        }
    }

    @PostMapping
    public ResponseEntity<String> handleWebhook(@RequestBody String rawPayload) {
        // 1. Log the payload so we know it arrived
        System.out.println("========== NEW WEBHOOK RECEIVED ==========");
        System.out.println(rawPayload);

        try {
            // 2. Parse the JSON string into our DTO
            WhatsAppWebhookDto dto = objectMapper.readValue(rawPayload, WhatsAppWebhookDto.class);

            // 3. Dig through the nested JSON to find the message
            if (dto.getEntry() != null && !dto.getEntry().isEmpty()) {
                WhatsAppWebhookDto.Entry entry = dto.getEntry().get(0);
                if (entry.getChanges() != null && !entry.getChanges().isEmpty()) {
                    WhatsAppWebhookDto.Change change = entry.getChanges().get(0);
                    if (change.getValue() != null && change.getValue().getMessages() != null && !change.getValue().getMessages().isEmpty()) {

                        WhatsAppWebhookDto.Message message = change.getValue().getMessages().get(0);
                        String from = message.getFrom();

                        // 4. Check if it is an AUDIO message
                        if ("audio".equals(message.getType()) && message.getAudio() != null) {
                            String mediaId = message.getAudio().getId();
                            System.out.println("Audio detected! Processing ID: " + mediaId);

                            // 5. Fire and forget (Async transcription)
                            processAudioAsync(mediaId, from);
                        } else {
                            System.out.println("Ignored message type: " + message.getType());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing webhook: " + e.getMessage());
            e.printStackTrace();
        }

        // Always return 200 OK to Meta, otherwise they will stop sending messages
        return ResponseEntity.ok("EVENT_RECEIVED");
    }

    @Async
    public void processAudioAsync(String mediaId, String from) {
        try {
            System.out.println("Fetching URL for Media ID: " + mediaId);
            String mediaUrl = whatsAppService.getMediaUrl(mediaId);

            System.out.println("Downloading audio from: " + mediaUrl);
            byte[] audioData = whatsAppService.downloadFile(mediaUrl);

            System.out.println("Transcribing with Groq...");
            String transcribedText = groqService.transcribe(audioData);

            System.out.println("Transcription: " + transcribedText);
            whatsAppService.sendReply(from, "Transcription: " + transcribedText);

        } catch (Exception e) {
            System.err.println("Async processing failed: " + e.getMessage());
            e.printStackTrace();
            whatsAppService.sendReply(from, "Sorry, I crashed while thinking. Check the server logs.");
        }
    }
}