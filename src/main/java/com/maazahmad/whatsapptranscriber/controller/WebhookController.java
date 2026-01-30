package com.maazahmad.whatsapptranscriber.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maazahmad.whatsapptranscriber.dto.WhatsAppWebhookDto;
import com.maazahmad.whatsapptranscriber.service.GoogleSheetsService;
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
    private final GoogleSheetsService googleSheetsService;

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
            System.out.println("Transcribing...");
            String transcribedText = groqService.transcribe(audioData);
            System.out.println("User said: " + transcribedText);

            // Step 3: Analyze Intent (Llama 3 - CFO Mode)
            System.out.println("Analyzing Intent...");
            String analysisJson = groqService.analyzeInput(transcribedText);

            JsonNode root = objectMapper.readTree(analysisJson);
            String intent = root.path("intent").asText();

            String replyMessage = "";

            // Step 4: Route based on Intent
            switch (intent) {
                case "LOG_EXPENSE" -> {
                    // --- CASE A: SAVE EXPENSE ---
                    JsonNode data = root.path("data");
                    // We pass the inner data object, but logExpense expects the structure Llama returns inside 'data'
                    googleSheetsService.logExpense(data.toString());

                    String item = data.path("item").asText();
                    String amount = data.path("amount").asText();
                    String currency = data.path("currency").asText();
                    replyMessage = String.format("✅ *Expense Saved!*\n🛒 %s\n💰 %s %s", item, amount, currency);
                }
                case "QUERY_SPENDING" -> {
                    // --- CASE B: ANALYTICS ---
                    JsonNode query = root.path("query");
                    String category = query.path("category").asText("ALL");
                    String merchant = query.path("merchant").asText("ALL");
                    String item = query.path("item").asText("ALL");
                    String start = query.path("start_date").asText();
                    String end = query.path("end_date").asText();

                    System.out.println("Querying: Cat=" + category + ", Merch=" + merchant + ", Item=" + item);
                    String report = googleSheetsService.calculateAnalytics(category, merchant, item, start, end);
                    replyMessage = "🔍 *CFO Report*\n" + report;
                }
                case "EDIT_EXPENSE" -> {
                    // --- CASE C: EDIT (Context-Aware) ---
                    JsonNode edit = root.path("edit");
                    String targetItem = edit.path("target_item").asText();
                    String targetDate = edit.path("target_date").asText();
                    double newAmount = edit.path("new_amount").asDouble();
                    String newCurrency = edit.path("new_currency").asText("PKR");

                    System.out.println("Editing: " + targetItem + " on " + targetDate);
                    replyMessage = googleSheetsService.editExpense(targetItem, targetDate, newAmount, newCurrency);
                }
                case "UNDO_LAST" -> {
                    // --- CASE D: UNDO ---
                    System.out.println("Undoing last entry...");
                    replyMessage = googleSheetsService.undoLastLog();
                }
                case "IRRELEVANT" -> {
                    // --- CASE E: IGNORE ---
                    System.out.println("Intent: IRRELEVANT");
                    replyMessage = "I only answer expense-related queries.";
                }
                default -> {
                    System.out.println("Unknown Intent: " + intent);
                    replyMessage = "🤔 I wasn't sure what you meant. I can log expenses, answer questions, or fix mistakes.";
                }
            }

            // Step 5: Reply
            whatsAppService.sendReply(from, replyMessage);

        } catch (Exception e) {
            System.err.println("Processing failed: " + e.getMessage());
            e.printStackTrace();
            whatsAppService.sendReply(from, "❌ Error: " + e.getMessage());
        }
    }
}