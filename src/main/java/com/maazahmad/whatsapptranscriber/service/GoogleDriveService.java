package com.maazahmad.whatsapptranscriber.service;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.Permission;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;

@Service
@RequiredArgsConstructor
public class GoogleDriveService {

    private final Drive driveService;

    // This was the missing variable!
    @Value("${google.template.sheet.id}")
    private String templateSheetId;

    @Value("${google.drive.folder.id}")
    private String destinationFolderId;

    public String cloneSheetForUser(String userEmail, String phoneNumber) throws Exception {
        // 1. Setup metadata for the new file
        File copiedFileMetadata = new File();
        copiedFileMetadata.setName("SpendTrace Ledger: " + phoneNumber);
        
        // Ensure the file is placed in your folder ID from application.properties
        copiedFileMetadata.setParents(java.util.Collections.singletonList(destinationFolderId));

        // 2. Execute the copy with 'supportsAllDrives' enabled
        // This is the key to bypassing some quota restrictions in shared folders
        File newSheet = driveService.files().copy(templateSheetId, copiedFileMetadata)
                .setFields("id")
                .setSupportsAllDrives(true) 
                .execute();
        
        String newSheetId = newSheet.getId();

        // 3. Grant the user access
        Permission userPermission = new Permission()
                .setType("user")
                .setRole("writer")
                .setEmailAddress(userEmail);

        driveService.permissions().create(newSheetId, userPermission)
                .setSupportsAllDrives(true)
                .execute();

        return newSheetId;
    }
}
