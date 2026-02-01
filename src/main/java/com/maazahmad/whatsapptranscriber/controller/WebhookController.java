package com.maazahmad.whatsapptranscriber.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maazahmad.whatsapptranscriber.dto.WhatsAppWebhookDto;
import com.maazahmad.whatsapptranscriber.model.User;
import com.maazahmad.whatsapptranscriber.repository.UserRepository;
import com.maazahmad.whatsapptranscriber.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
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
    private final UserRepository userRepository;
    private final GoogleDriveService googleDriveService;

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
        System.out.println("========== NEW WEBHOOK RECEIVED ==========");

        try {
            WhatsAppWebhookDto dto = objectMapper.readValue(rawPayload, WhatsAppWebhookDto.class);

            if (dto.getEntry() != null && !dto.getEntry().isEmpty()) {
                WhatsAppWebhookDto.Value value = dto.getEntry().get(0).getChanges().get(0).getValue();

                if (value.getMessages() != null && !value.getMessages().isEmpty()) {
                    WhatsAppWebhookDto.Message message = value.getMessages().get(0);
                    String from = message.getFrom();
                    String msgId = message.getId() != null ? message.getId() : "default_id";

                    // Idempotency check
                    if (processedMessageIds.contains(msgId)) {
                        return ResponseEntity.ok().build();
                    }
                    processedMessageIds.add(msgId);

                    // AUDIO PROCESSING
                    if ("audio".equals(message.getType()) && message.getAudio() != null) {
                        processAudioAsync(message.getAudio().getId(), from);
                    }
                    // TEXT ONBOARDING
                    else if ("text".equals(message.getType()) && message.getText() != null) {
                        String body = message.getText().getBody();
                        if (body != null && body.contains("@")) {
                            processOnboardingAsync(from, body);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing webhook: " + e.getMessage());
        }

        return ResponseEntity.ok("EVENT_RECEIVED");
    }

    @Async
    public void processAudioAsync(String mediaId, String from) {
        Optional<User> userOpt = userRepository.findByPhoneNumber(from);
        if (userOpt.isEmpty()) {
            whatsAppService.sendReply(from, "👋 Welcome! I don't have a ledger for you yet. Please reply with your *email address* to set one up.");
            return;
        }

        String userSheetId = userOpt.get().getSpreadsheetId();

        try {
            byte[] audioData = whatsAppService.downloadFile(whatsAppService.getMediaUrl(mediaId));
            String transcription = groqService.transcribe(audioData);
            String analysisJson = groqService.analyzeInput(transcription);
            JsonNode root = objectMapper.readTree(analysisJson);
            String intent = root.path("intent").asText();

            String replyMessage = switch (intent) {
                case "LOG_EXPENSE" -> {
                    JsonNode data = root.path("data");
                    googleSheetsService.logExpense(data.toString(), userSheetId);
                    yield String.format("✅ *Expense Saved!*\n🛒 *Item:* %s\n💰 *Cost:* %s %s",
                            data.path("item").asText(), data.path("amount").asText(), data.path("currency").asText());
                }
                case "QUERY_SPENDING" -> {
                    JsonNode q = root.path("query");
                    yield "🔍 *CFO Report:* " + googleSheetsService.calculateAnalytics(
                            q.path("category").asText(), q.path("merchant").asText(),
                            q.path("item").asText(), q.path("start_date").asText(),
                            q.path("end_date").asText(), userSheetId);
                }
                case "EDIT_EXPENSE" -> {
                    JsonNode e = root.path("edit");
                    yield googleSheetsService.editExpense(
                            e.path("target_item").asText(), e.path("target_date").asText(),
                            e.path("new_amount").asDouble(), e.path("new_currency").asText(), userSheetId);
                }
                case "UNDO_LAST" -> googleSheetsService.undoLastLog(userSheetId);
                default -> "👋 I am your AI CFO. Send me voice notes to log expenses!";
            };

            whatsAppService.sendReply(from, replyMessage);

        } catch (Exception e) {
            whatsAppService.sendReply(from, "❌ Error: " + e.getMessage());
        }
    }

    @Async
    public void processOnboardingAsync(String from, String email) {
        try {
            whatsAppService.sendReply(from, "⚙️ Provisioning your private ledger...");
            String newSheetId = googleDriveService.cloneSheetForUser(email, from);

            User newUser = User.builder()
                    .phoneNumber(from)
                    .spreadsheetId(newSheetId)
                    .email(email)
                    .build();
            userRepository.save(newUser);

            whatsAppService.sendReply(from, "✅ *Success!* Your ledger is ready:\nhttps://docs.google.com/spreadsheets/d/" + newSheetId);
        } catch (Exception e) {
            whatsAppService.sendReply(from, "❌ Setup failed: " + e.getMessage());
        }
    }
}