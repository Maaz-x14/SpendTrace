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


    // Step 1: Transcribe Audio (Whisper)
    @SneakyThrows
    public String transcribe(byte[] audioData) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(groqApiKey);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        // Wrap byte array in a Resource with a filename so Groq knows it's audio
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
            JsonNode jsonNode = objectMapper.readTree(response.getBody());
            return jsonNode.get("text").asText();
        } else {
            throw new RuntimeException("Failed to transcribe audio: " + response.getStatusCode());
        }
    }

    // Step 2: Extract JSON Data (Llama 3)
    @SneakyThrows
    public String extractExpenseData(String rawText) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(groqApiKey);

        // System Prompt: Instructs the AI on its role
        String systemPrompt = """
            You are an AI accountant. Extract expense details from the user's spoken text into JSON.
            Fields required:
            - item (string): What was bought
            - amount (number): The cost
            - currency (string): e.g. USD, PKR, EUR (infer from context or default to PKR)
            - merchant (string): Where it was bought
            - category (string): Food, Transport, Utilities, Office, Entertainment, Other
            - date (string): YYYY-MM-DD (Assume today is %s if not mentioned)
            
            Return ONLY the valid JSON object. Do not wrap it in markdown blocks.
            """.formatted(LocalDate.now());

        // Construct the Payload
        Map<String, Object> body = Map.of(
                "model", "llama-3.3-70b-versatile", // Intelligent model
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", rawText)
                ),
                "response_format", Map.of("type", "json_object"), // Enforce JSON
                "temperature", 0.1 // Precision over creativity
        );

        String jsonBody = objectMapper.writeValueAsString(body);
        HttpEntity<String> requestEntity = new HttpEntity<>(jsonBody, headers);

        ResponseEntity<String> response = restTemplate.exchange(GROQ_CHAT_URL, HttpMethod.POST, requestEntity, String.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            JsonNode root = objectMapper.readTree(response.getBody());
            // Extract content from: choices[0].message.content
            return root.path("choices").get(0).path("message").path("content").asText();
        } else {
            throw new RuntimeException("Failed to extract data: " + response.getStatusCode());
        }
    }
}