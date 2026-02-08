package com.maazahmad.whatsapptranscriber.service;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.Permission;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@RequiredArgsConstructor
public class GoogleDriveService {

    private final Drive driveService;

    @Value("${google.template.sheet.id}")
    private String templateSheetId;

    @Value("${google.drive.folder.id}")
    private String destinationFolderId;

    // Add this to your existing fields
    private final GoogleSheetsService googleSheetsService;

    public String cloneSheetForUser(String userEmail, String phoneNumber) throws Exception {
        System.out.println("DEBUG: Creating Ledger via Sheets API for " + phoneNumber);

        // 1. Create the file using SHEETS API (Bypasses Drive quota)
        String newSheetId = googleSheetsService.createSpreadsheet("SpendTrace Ledger: " + phoneNumber);

        // 2. MOVE the file to the destination folder
        try {
            System.out.println("DEBUG: Moving file to folder: " + destinationFolderId);
            driveService.files().update(newSheetId, null)
                    .setAddParents(destinationFolderId)
                    .setSupportsAllDrives(true)
                    .execute();
            System.out.println("DEBUG: Move SUCCESS.");
        } catch (Exception e) {
            System.err.println("DEBUG: Move FAILED: " + e.getMessage());
            // We continue anyway because the file exists, it's just in the bot's root
        }

        // 3. Transfer Ownership
        Permission userPermission = new Permission()
                .setType("user")
                .setRole("owner")
                .setEmailAddress(userEmail);

        driveService.permissions().create(newSheetId, userPermission)
                .setSupportsAllDrives(true)
                .setTransferOwnership(true)
                .execute();
        
        // 4. Initialize Headers
        googleSheetsService.setupHeaders(newSheetId);

        return newSheetId;
    }
}
