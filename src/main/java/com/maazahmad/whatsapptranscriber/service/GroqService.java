package com.maazahmad.whatsapptranscriber.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GroqService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${groq.api.url}")
    private String groqAudioUrl;

    @Value("${groq.api.key}")
    private String groqApiKey;

    // Hardcoded Chat URL for Llama-3 calls
    private static final String GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions";

    /**
     * Step 1: Transcribe Audio (Whisper) with Retry Logic
     */
    @SneakyThrows
    public String transcribe(byte[] audioData) {
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                return attemptTranscribe(audioData);
            } catch (Exception e) {
                System.err.println("Transcription attempt " + (i + 1) + " failed: " + e.getMessage());
                if (i == maxRetries - 1) {
                    throw e; // Crash if all retries fail
                }
                Thread.sleep(1000); // Wait 1 second before retrying
            }
        }
        return null; // Should not be reached
    }

    private String attemptTranscribe(byte[] audioData) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(groqApiKey);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(audioData) {
            @Override
            public String getFilename() {
                return "audio.ogg";
            }
        });
        body.add("model", "whisper-large-v3");
        body.add("response_format", "json");

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.exchange(groqAudioUrl, HttpMethod.POST, requestEntity, String.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            try {
                JsonNode jsonNode = objectMapper.readTree(response.getBody());
                return jsonNode.get("text").asText();
            } catch (Exception e) {
                throw new RuntimeException("Invalid JSON from Groq");
            }
        } else {
            throw new RuntimeException("Groq API Error: " + response.getStatusCode());
        }
    }

    /**
     * MASTER BRAIN: Analyzes Intent (Log vs Query vs Edit vs Undo)
     */
    @SneakyThrows
    public String analyzeInput(String rawText) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(groqApiKey);

        String today = LocalDate.now().toString();

        String systemPrompt = """
            You are an expert CFO AI. Analyze the user's input.

            Current Date Reference: %s

            --- RULES FOR DATA PURITY ---
            1. **DATES:** MUST be in format `YYYY-MM-DD`.
             - If user says "Last month", use the **1st day** of that month (e.g., 2025-12-01).
             - If user says "2026", use "2026-01-01".
             - NEVER output just "YYYY-MM".

            2. **CATEGORIES (STRICT & CLOSED SET):**
                
            You MUST assign exactly ONE category from the following fixed list.
            NO other categories are allowed under ANY circumstances.

            ALLOWED CATEGORIES:
            [Food, Transport, Office, Utilities, Entertainment, Health, Shopping, Travel, Education, Other]

            --- CATEGORY DEFINITIONS (USE THESE AS RULES, NOT SUGGESTIONS) ---

            • Food:
              - Any edible item, restaurant, cafe, fast food, groceries, snacks, drinks.
              - Includes food delivery apps and takeaway.
              - Examples: KFC, McDonald's, groceries, Uber Eats → Food

            • Transport:
              - Any movement or commuting expense.
              - Examples: Uber, Careem, fuel, bus fare, taxi, train ticket → Transport

            • Office:
              - Work-related supplies or tools used primarily for professional or business purposes.
              - Examples: Pencils, notebooks, printer ink, office furniture → Office

            • Utilities:
              - Recurring household or service bills.
              - Examples: electricity, gas, water, internet, mobile bill, subscriptions for utilities → Utilities

            • Entertainment:
              - Non-essential leisure or fun activities.
              - Examples: movies, Netflix, Spotify, games, concerts → Entertainment

            • Health:
              - Medical, healthcare, or wellness-related expenses.
              - Examples: hospital, doctor visit, medicines, pharmacy, gym (if fitness-focused) → Health

            • Shopping:
              - General retail purchases NOT fitting other categories.
              - Examples: clothes, shoes, electronics, Amazon purchase → Shopping

            • Travel:
              - Long-distance or out-of-city travel expenses.
              - Examples: flights, hotels, Airbnb, travel tickets, visa fees → Travel

            • Education:
              - Learning or academic-related expenses.
              - Examples: books, courses, tuition fees, online classes → Education

            • Other:
              - Use ONLY if the item clearly does NOT fit ANY category above.
              - This is a LAST RESORT category.

            --- DECISION RULES (MANDATORY) ---
            1. NEVER invent a new category.
            2. If multiple categories seem possible, choose the MOST SPECIFIC one.
            3. If still ambiguous, choose the category based on PRIMARY INTENT of the expense.
            4. If no category applies confidently, use "Other" — but ONLY after all others fail.


            STEP 1: DETERMINE INTENT
            1. "LOG_EXPENSE": User is reporting spending (e.g., "I spent 500 on lunch").
            2. "QUERY_SPENDING": User is asking for analytics (e.g., "How much did I spend on KFC?").
            3. "EDIT_EXPENSE": User wants to update a specific previous entry (e.g., "Update chicken wings to 550", "Change yesterday's lunch to 300").
            4. "UNDO_LAST": User wants to delete or revert the immediately preceding action (e.g., "Undo that", "Delete the last one", "Cancel").
            5. "IRRELEVANT": Input is NOT related to finances.
            
            STEP 2: EXTRACT DATA
            
            --- CASE A: LOG_EXPENSE ---
            Extract: item, amount, currency (default PKR), merchant, category (FROM ALLOWED LIST), date (YYYY-MM-DD).
            
            --- CASE B: QUERY_SPENDING ---
            Extract filter parameters (use "ALL" if not specified):
            - category, merchant, item, start_date, end_date.
            
            --- CASE C: EDIT_EXPENSE ---
            Extract:
            - target_item (string): The item name.
            - target_date (string):
                 - If user specifies "today", "yesterday", "last friday" -> Calculate YYYY-MM-DD.
                 - If user DOES NOT mention a date -> Return "LAST_MATCH".
            - new_amount (number): The corrected cost.
            - new_currency (string): The corrected currency (default to PKR).
            
            --- CASE D: UNDO_LAST ---
            No specific data needed.
            
            STEP 3: OUTPUT JSON ONLY
            
            Format for LOG_EXPENSE:
            {
              "intent": "LOG_EXPENSE",
              "data": { "item": "...", "amount": 0, "currency": "...", "merchant": "...", "category": "...", "date": "..." }
            }
            
            Format for QUERY_SPENDING:
            {
              "intent": "QUERY_SPENDING",
              "query": { "category": "...", "merchant": "...", "item": "...", "start_date": "...", "end_date": "..." }
            }
            
            Format for EDIT_EXPENSE:
            {
              "intent": "EDIT_EXPENSE",
              "edit": { "target_item": "...", "target_date": "...", "new_amount": 0, "new_currency": "..." }
            }
            
            Format for UNDO_LAST:
            { "intent": "UNDO_LAST" }
            
            Format for IRRELEVANT:
            { "intent": "IRRELEVANT", "message": "..." }
            """.formatted(today);

        Map<String, Object> body = Map.of(
                "model", "llama-3.3-70b-versatile",
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", rawText)
                ),
                "response_format", Map.of("type", "json_object"),
                "temperature", 0.1
        );

        String jsonBody = objectMapper.writeValueAsString(body);
        HttpEntity<String> requestEntity = new HttpEntity<>(jsonBody, headers);

        ResponseEntity<String> response = restTemplate.exchange(GROQ_CHAT_URL, HttpMethod.POST, requestEntity, String.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            JsonNode root = objectMapper.readTree(response.getBody());
            return root.path("choices").get(0).path("message").path("content").asText();
        } else {
            throw new RuntimeException("Failed to analyze input");
        }
    }
}