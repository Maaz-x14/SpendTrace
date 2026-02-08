package com.maazahmad.whatsapptranscriber.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ClearValuesRequest;
import com.google.api.services.sheets.v4.model.ValueRange;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GoogleSheetsService {

    private final ObjectMapper objectMapper;
    private Sheets sheetsService;

    @Value("${google.credentials.path}")
    private String credentialsPath;

    // Standardize the tab name here
    private static final String DEFAULT_SHEET = "Sheet1";

    @PostConstruct
    public void init() throws IOException, GeneralSecurityException {
        try (InputStream in = new FileInputStream(credentialsPath)) {
            GoogleCredentials credentials = GoogleCredentials.fromStream(in)
                    .createScoped(Collections.singleton(SheetsScopes.SPREADSHEETS));

            this.sheetsService = new Sheets.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("SpendTrace")
                    .build();
        }
    }

    public void setupHeaders(String spreadsheetId) {
        try {
            List<Object> headers = List.of("Date", "Item", "Amount", "Currency", "Merchant", "Category");
            ValueRange body = new ValueRange().setValues(List.of(headers));
            sheetsService.spreadsheets().values()
                    .update(spreadsheetId, "Sheet1!A1", body)
                    .setValueInputOption("USER_ENTERED")
                    .execute();
        } catch (Exception e) {
            System.err.println("Failed to set headers: " + e.getMessage());
        }
    }

    @SneakyThrows
    public void logExpense(String jsonExpense, String spreadsheetId) {
        JsonNode root = objectMapper.readTree(jsonExpense);

        List<Object> rowData = List.of(
            root.path("date").asText(LocalDate.now().toString()),
            root.path("item").asText("Unknown"),
            root.path("amount").asDouble(0.0),
            root.path("currency").asText("PKR"),
            root.path("merchant").asText("Unknown"),
            root.path("category").asText("Uncategorized")
        );

        ValueRange body = new ValueRange().setValues(List.of(rowData));

        sheetsService.spreadsheets().values()
                .append(spreadsheetId, DEFAULT_SHEET + "!A1", body)
                .setValueInputOption("USER_ENTERED")
                .execute();

        System.out.println("✅ Expense logged to " + spreadsheetId);
    }

    @SneakyThrows
    public List<List<Object>> readAllRows(String spreadsheetId) {
        ValueRange response = sheetsService.spreadsheets().values()
                .get(spreadsheetId, DEFAULT_SHEET + "!A:F")
                .execute();
        return response.getValues();
    }

    public String calculateAnalytics(String category, String merchant, String item, String startStr, String endStr, String spreadsheetId) {
        List<List<Object>> rows = readAllRows(spreadsheetId);
        if (rows == null || rows.isEmpty() || rows.size() < 2) return "⚠️ Your ledger is currently empty.";

        double total = 0.0;
        int matchCount = 0;
        String currency = "PKR";

        LocalDate startDate = parseDateSafely(startStr);
        LocalDate endDate = parseDateSafely(endStr);

        // Skip header (row 0)
        for (int i = 1; i < rows.size(); i++) {
            List<Object> row = rows.get(i);
            if (row.size() < 4) continue;

            try {
                LocalDate rowDate = LocalDate.parse(row.get(0).toString());
                String rowItem = row.get(1).toString().toLowerCase();
                double rowAmount = Double.parseDouble(row.get(2).toString());
                String rowCurrency = row.get(3).toString();
                String rowMerchant = row.size() > 4 ? row.get(4).toString().toLowerCase() : "";
                String rowCategory = row.size() > 5 ? row.get(5).toString().toLowerCase() : "";

                // Date Filter
                if (startDate != null && rowDate.isBefore(startDate)) continue;
                if (endDate != null && rowDate.isAfter(endDate)) continue;

                // Content Filters (Case-Insensitive)
                if (isFilterActive(category) && !rowCategory.contains(category.toLowerCase())) continue;
                if (isFilterActive(merchant) && !rowMerchant.contains(merchant.toLowerCase())) continue;
                if (isFilterActive(item) && !rowItem.contains(item.toLowerCase())) continue;

                total += rowAmount;
                currency = rowCurrency;
                matchCount++;
            } catch (Exception e) {
                // Skip rows with bad formatting
            }
        }

        if (matchCount == 0) return "🔍 No records match your current filters.";

        return String.format("📊 *Spending Report*\n━━━━━━━━━━━━━━\nTotal: *%.2f %s*\nTransactions: %d", 
                total, currency, matchCount);
    }

    private LocalDate parseDateSafely(String dateStr) {
        if (dateStr == null || dateStr.isEmpty() || "null".equalsIgnoreCase(dateStr)) return null;
        try { return LocalDate.parse(dateStr); } catch (Exception e) { return null; }
    }

    private boolean isFilterActive(String filter) {
        return filter != null && !filter.isEmpty() && !"null".equalsIgnoreCase(filter);
    }

    @SneakyThrows
    public String editExpense(String targetItem, String targetDateStr, double newAmount, String newCurrency, String spreadsheetId) {
        List<List<Object>> rows = readAllRows(spreadsheetId);
        if (rows == null || rows.isEmpty()) return "⚠️ Ledger is empty.";

        String searchItem = targetItem.toLowerCase();
        boolean matchAnyDate = "LAST_MATCH".equalsIgnoreCase(targetDateStr);
        LocalDate searchDate = matchAnyDate ? null : LocalDate.parse(targetDateStr);

        for (int i = rows.size() - 1; i >= 0; i--) {
            List<Object> row = rows.get(i);
            if (row.size() < 4) continue;

            try {
                String rowDateStr = row.get(0).toString();
                String rowItem = row.get(1).toString().toLowerCase();

                boolean itemMatch = rowItem.contains(searchItem);
                boolean dateMatch = matchAnyDate || LocalDate.parse(rowDateStr).isEqual(searchDate);

                if (itemMatch && dateMatch) {
                    int rowIndex = i + 1;
                    String range = DEFAULT_SHEET + "!C" + rowIndex + ":D" + rowIndex;

                    ValueRange body = new ValueRange().setValues(List.of(List.of(newAmount, newCurrency)));

                    sheetsService.spreadsheets().values()
                            .update(spreadsheetId, range, body)
                            .setValueInputOption("USER_ENTERED")
                            .execute();

                    return String.format("✅ Updated **%s** to **%.2f %s**.", targetItem, newAmount, newCurrency);
                }
            } catch (Exception e) {}
        }
        return "❌ Expense not found.";
    }

    @SneakyThrows
    public String undoLastLog(String spreadsheetId) {
        List<List<Object>> rows = readAllRows(spreadsheetId);
        if (rows == null || rows.isEmpty()) return "⚠️ Nothing to undo.";

        int lastRowIndex = rows.size();
        String range = DEFAULT_SHEET + "!A" + lastRowIndex + ":F" + lastRowIndex;

        sheetsService.spreadsheets().values()
                .clear(spreadsheetId, range, new ClearValuesRequest())
                .execute();

        return "✅ Last entry deleted.";
    }
}
