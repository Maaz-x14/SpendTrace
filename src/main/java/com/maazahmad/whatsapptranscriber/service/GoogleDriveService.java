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
    private final GoogleSheetsService googleSheetsService;

    @Value("${google.drive.folder.id}")
    private String destinationFolderId;

    public String cloneSheetForUser(String userEmail, String phoneNumber) throws Exception {
        System.out.println("DEBUG: Provisioning UNIQUE ledger for " + phoneNumber);

        // 1. Metadata for a fresh, uniquely named Spreadsheet
        File fileMetadata = new File();
        fileMetadata.setName("SpendTrace Ledger: " + phoneNumber);
        fileMetadata.setMimeType("application/vnd.google-apps.spreadsheet");
        fileMetadata.setParents(Collections.singletonList(destinationFolderId));

        String newSheetId;
        try {
            // Creating a fresh file and ignoring default visibility to bypass 0GB quota
            File newSheet = driveService.files().create(fileMetadata)
                    .setFields("id")
                    .setSupportsAllDrives(true)
                    .setIgnoreDefaultVisibility(true) 
                    .execute();
            
            newSheetId = newSheet.getId();
            System.out.println("DEBUG: Creation SUCCESS. ID: " + newSheetId);

        } catch (Exception e) {
            System.err.println("DEBUG: FAILED at Step 1 (Create): " + e.getMessage());
            throw e;
        }

        // 2. Add the User as a Writer
        try {
            Permission userPermission = new Permission()
                    .setType("user")
                    .setRole("writer") 
                    .setEmailAddress(userEmail);

            driveService.permissions().create(newSheetId, userPermission)
                    .setSupportsAllDrives(true)
                    .execute();
            System.out.println("DEBUG: Permission granted to " + userEmail);
        } catch (Exception e) {
            System.err.println("DEBUG: FAILED at Step 2 (Permission): " + e.getMessage());
        }

        // 3. Initialize the clean headers
        googleSheetsService.setupHeaders(newSheetId);

        return newSheetId;
    }
}
