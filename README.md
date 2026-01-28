# 🎙️ WhatsApp Voice Note Transcriber (Java Spring Boot)

A high-performance backend service that integrates **WhatsApp Business API** with **Groq AI** to transcribe voice notes in real-time. Built with Java 22 and Spring Boot, this system handles webhook events, securely fetches private media, and delivers sub-second transcription using the `whisper-large-v3` model.

## 🚀 Key Features

* **⚡ Real-Time Transcription:** Converts WhatsApp OGG/Opus audio to text in milliseconds via Groq API.
* **🛡️ Secure Webhook Handling:** Validates Meta's `hub.verify_token` handshake and filters event payloads.
* **🧠 Idempotency Layer:** Implements memory-based duplicate detection to prevent "echo" replies from Meta's retry logic.
* **🔊 Media Pipeline:** Authenticated fetching of private media URLs from Meta's Graph API.
* **🚇 Local Tunneling:** Configured for **Serveo** (`ssh -R`) to expose local ports securely without browser warnings.

## 🛠️ Tech Stack

* **Core:** Java 22, Spring Boot 3.2.0 (Web, RestTemplate)
* **AI Engine:** Groq API (Whisper-Large-V3)
* **Messaging:** WhatsApp Business Cloud API
* **Tools:** Lombok, Jackson JSON, Maven

## ⚙️ Architecture

1.  **User** sends a Voice Note on WhatsApp.
2.  **Meta** sends a `POST` webhook to the Spring Boot application.
3.  **Controller** verifies the message type (Audio vs Text) and checks for duplicates.
4.  **Service** uses the Media ID to fetch the authenticated download URL from Graph API.
5.  **Service** downloads the binary audio data.
6.  **AI Service** streams the buffer to Groq's Transcription endpoint.
7.  **Bot** replies to the user with the transcribed text.

## 🏃‍♂️ Quick Start

### Prerequisites
* Java 22+
* Maven
* WhatsApp Business Account (Meta for Developers)
* Groq API Key

### 1. Clone the Repo
```bash
git clone [https://github.com/your-username/whatsapp-transcriber.git](https://github.com/your-username/whatsapp-transcriber.git)
cd whatsapp-transcriber
```

### 2. Configure Environment
Create an application.properties file in src/main/resources:

```bash
server.port=8080
whatsapp.token=YOUR_META_ACCESS_TOKEN
whatsapp.phoneNumberId=YOUR_PHONE_NUMBER_ID
whatsapp.verifyToken=YOUR_CUSTOM_VERIFY_TOKEN
groq.apiKey=YOUR_GROQ_API_KEY
groq.apiUrl=[https://api.groq.com/openai/v1/audio/transcriptions](https://api.groq.com/openai/v1/audio/transcriptions)
```

3. Start the Tunnel (Serveo)
   Expose your local server to the internet:

```bash
# Register your key first at serveo.net
ssh -R your-custom-name:80:localhost:8080 serveo.net
```

4. Run the Application
```bash
mvn spring-boot:run
```

5. Update Meta Webhook
Set your Callback URL in the Meta Dashboard to:https://your-custom-name.serveo.net/webhook

### 🔮 Future Roadmap (The "Big Project")
**LLM Integration:** Extract structured JSON data from voice notes using Llama 3.

**Database Persistence:** Store transcripts in PostgreSQL.

**Task Automation:** Auto-create Notion/Jira tickets from voice commands.

