package com.maazahmad.whatsapptranscriber.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.Permission;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class GoogleDriveService {

    private Drive driveService;

    @Value("${google.credentials.path}")
    private String credentialsPath;

    @Value("${google.template.sheet.id}")
    private String templateId;

    @PostConstruct
    public void init() throws Exception {
        GoogleCredentials credentials = GoogleCredentials.fromStream(
                        new ClassPathResource(credentialsPath).getInputStream())
                .createScoped(Collections.singleton(DriveScopes.DRIVE));

        this.driveService = new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("SpendTrace")
                .build();
    }

    public String cloneSheetForUser(String userEmail, String phoneNumber) throws Exception {
        // 1. Clone the Template
        File copiedFile = new File();
        copiedFile.setName("SpendTrace Ledger: " + phoneNumber);

        File newSheet = driveService.files().copy(templateId, copiedFile).execute();
        String newSheetId = newSheet.getId();

        // 2. Share with User's Email (Writer access)
        Permission userPermission = new Permission()
                .setType("user")
                .setRole("writer")
                .setEmailAddress(userEmail);

        driveService.permissions().create(newSheetId, userPermission)
                .setTransferOwnership(false)
                .execute();

        return newSheetId;
    }
}