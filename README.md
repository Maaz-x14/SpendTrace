# 💸 SpendTrace: Voice-First AI Financial Assistant

**SpendTrace** is an intelligent "CFO in your pocket." It transforms WhatsApp voice notes into structured financial data in real-time. Unlike simple transcribers, SpendTrace understands **intent**—it knows the difference between *logging an expense* ("I spent $50 on food") and *asking a question* ("How much did I spend on food this month?").

Built with **Java Spring Boot**, powered by **Groq (Whisper + Llama 3)**, and backed by a live **Google Sheets** ledger.

## 🚀 Key Capabilities

### 🧠 Intelligent "CFO Mode"
* **Intent Classification:** The system analyzes your voice to decide if you are **Reporting** an expense, **Querying** your history, or just chatting (and filters out irrelevant noise like songs).
* **Context-Aware Extraction:**
   * **Multilingual:** Understands English, Urdu, Hindi, and mixed-language audio.
   * **Smart Dates:** Resolves relative time like *"Yesterday"*, *"Last month"*, or *"On this day last year"* into specific calendar dates.
   * **Multi-Currency:** Automatically separates and aggregates totals for different currencies (e.g., `PKR`, `USD`, `EUR`).

### ⚡ Technical Highlights
* **Event-Driven Pipeline:** WhatsApp Audio → Text (Whisper) → JSON (Llama-3) → Google Sheet.
* **Granular Analytics:** Queries allow filtering by **Category** (Food), **Merchant** (KFC), **Item** (Wings), and **Date Range**.
* **Resilience:** Implements retry logic for AI services and idempotency checks to handle Meta's duplicate webhook events.
* **Zero-Entry Interface:** No forms or buttons. Just talk.

---

## 🛠️ Tech Stack

* **Core:** Java 22, Spring Boot 3.2 (Web, RestTemplate, Async)
* **AI Engine:** Groq API
   * **Hearing:** `whisper-large-v3` (Audio to Text)
   * **Brain:** `llama-3.3-70b` (Intent Analysis & Entity Extraction)
* **Database:** Google Sheets API v4 (Live Ledger)
* **Messaging:** WhatsApp Business Cloud API
* **Security:** Meta Webhook Verification, Service Account Auth (Google Cloud)

---

## ⚙️ Architecture (The "Brain" Flow)

1. **Ingestion:** User sends OGG Audio via WhatsApp → Spring Boot Webhook.
2. **Transcription:** Service fetches secure media URL and streams bytes to **Groq Whisper**.
3. **Cognition:** **Groq Llama-3** analyzes the text with a specialized "CFO System Prompt":
   * *Is this a new expense?* → Extract specific JSON fields.
   * *Is this a question?* → Extract filter parameters (Dates, Categories).
4. **Execution (The Router):**
   * **Write Path:** Appends a structured row to the Google Sheet.
   * **Read Path:** Fetches sheet data, filters in memory, calculates totals per currency.
5. **Feedback:** Bot replies to WhatsApp with a confirmation or a formatted financial report.

---

## 🏃‍♂️ Quick Start

### Prerequisites
* Java 22+ & Maven
* WhatsApp Business Account (Meta Developers)
* Groq API Key
* **Google Cloud Service Account** (with Sheets API enabled)

### 1. Clone & Configure
```bash
git clone [https://github.com/your-username/spendtrace.git](https://github.com/your-username/spendtrace.git)
cd spendtrace
```

### 2. Configure Environment
Create an application.properties file in src/main/resources:

```bash
server.port=8080

# WhatsApp Config
whatsapp.token=YOUR_META_ACCESS_TOKEN
whatsapp.phoneNumberId=YOUR_PHONE_NUMBER_ID
whatsapp.verifyToken=YOUR_CUSTOM_VERIFY_TOKEN

# Groq AI Config
groq.api.key=YOUR_GROQ_API_KEY
groq.api.url=[https://api.groq.com/openai/v1/audio/transcriptions](https://api.groq.com/openai/v1/audio/transcriptions)

# Google Sheets Config
google.sheets.id=YOUR_SPREADSHEET_ID
google.credentials.path=google-sheets-key.json
```

### 3. Google Sheets Auth
   1. Download your Service Account JSON key from Google Cloud.
   2. Rename it to google-sheets-key.json.
   3. Place it in src/main/resources/.
   4. Crucial: Share your Google Sheet (Editor access) with the client_email found inside that JSON file.

### 4. Run & Expose 

```bash
# Start the Tunnel (for Webhook testing)
ssh -R spendtrace:80:localhost:8080 serveo.net

# Run the App
mvn spring-boot:run
```

### 🗣️ Usage Examples
#### 1. Log an Expense

🎤 "I just paid 5000 rupees for a team dinner at Monal."

Bot: ✅ Expense Saved! 🛒 Dinner | 💰 5000 PKR | 📅 2026-01-30

#### 2. Time-Travel Logging

🎤 "Last Friday I bought a mechanical keyboard for 150 dollars."

Bot: ✅ Expense Saved! 🛒 Mechanical Keyboard | 💰 150 USD | 📅 [Calculates Date of Last Friday]

#### 3. Ask the CFO (Analytics)

🎤 "How much have I spent on Food this month?"

Bot: 🔍 CFO Report 💰 Total: 25,000 PKR 📊 Transactions: 4 📅 Period: 2026-01-01 to 2026-01-30

### 🔮 Future Roadmap
**Visual Charts:** Generate image charts using Python/QuickChart and send back to WhatsApp.

**Budget Alerts:** "Warning: You have exceeded your Food budget."

**Receipt Scanning:** Process image messages using Llama-3 Vision.