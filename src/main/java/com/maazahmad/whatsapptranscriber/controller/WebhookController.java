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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final WhatsAppService whatsAppService;
    private final GroqService groqService;
    private final ObjectMapper objectMapper;

    // Idempotency cache to prevent duplicate processing
    private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();

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
        // Log payload for debugging
        System.out.println("========== NEW WEBHOOK RECEIVED ==========");
        // System.out.println(rawPayload); // Comment out to reduce noise if needed

        try {
            WhatsAppWebhookDto dto = objectMapper.readValue(rawPayload, WhatsAppWebhookDto.class);

            if (dto.getEntry() != null && !dto.getEntry().isEmpty()) {
                WhatsAppWebhookDto.Entry entry = dto.getEntry().get(0);
                if (entry.getChanges() != null && !entry.getChanges().isEmpty()) {
                    WhatsAppWebhookDto.Change change = entry.getChanges().get(0);

                    if (change.getValue() != null && change.getValue().getMessages() != null && !change.getValue().getMessages().isEmpty()) {
                        WhatsAppWebhookDto.Message message = change.getValue().getMessages().get(0);
                        String from = message.getFrom();

                        // Check for AUDIO
                        if ("audio".equals(message.getType()) && message.getAudio() != null) {
                            String mediaId = message.getAudio().getId();

                            // Idempotency Check
                            if (processedMessageIds.contains(mediaId)) {
                                System.out.println("Duplicate message ignored: " + mediaId);
                                return ResponseEntity.ok().build();
                            }
                            processedMessageIds.add(mediaId);

                            System.out.println("Audio detected! Processing ID: " + mediaId);

                            // Trigger Async Processing
                            processAudioAsync(mediaId, from);
                        }
                        // Optional: Handle text commands here later
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing webhook: " + e.getMessage());
            e.printStackTrace();
        }

        return ResponseEntity.ok("EVENT_RECEIVED");
    }

    @Async
    public void processAudioAsync(String mediaId, String from) {
        try {
            // Step 1: Download Audio
            System.out.println("Fetching URL for Media ID: " + mediaId);
            String mediaUrl = whatsAppService.getMediaUrl(mediaId);
            byte[] audioData = whatsAppService.downloadFile(mediaUrl);

            // Step 2: Transcribe (Whisper)
            System.out.println("Transcribing with Groq Whisper...");
            String transcribedText = groqService.transcribe(audioData);
            System.out.println("Transcription: " + transcribedText);

            // Step 3: Extract Data (Llama 3)
            System.out.println("Extracting JSON with Groq Llama-3...");
            String expenseJson = groqService.extractExpenseData(transcribedText);
            System.out.println("Extracted Data: " + expenseJson);

            // Step 4: Reply with the Result
            String replyMessage = "✅ Expense Logged!\n\n" + expenseJson;
            whatsAppService.sendReply(from, replyMessage);

        } catch (Exception e) {
            System.err.println("Async processing failed: " + e.getMessage());
            e.printStackTrace();
            whatsAppService.sendReply(from, "❌ Failed to process expense: " + e.getMessage());
        }
    }
}