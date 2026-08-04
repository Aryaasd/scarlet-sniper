# 🎯 Rutgers Course Sniper

A Java Spring Boot service that monitors course availability at Rutgers University. It polls the Rutgers Schedule of Classes API for specific course sections and sends a real-time SMS via Twilio the moment a closed section opens up.

---

## 🚀 Features

* **Automated sniping:** Periodically polls the Rutgers Schedule of Classes API to check section status.
* **Database persistence:** Stores tracking requests and course metadata in PostgreSQL.
* **SMS notifications:** Integrates with Twilio to send instant alerts when a course opens.
* **Memory optimized:** Tuned to run in containerized environments (like Railway) under strict memory limits.

---

## 🛠️ Tech Stack

| Layer | Technology |
| --- | --- |
| Language | Java 21 |
| Framework | Spring Boot 3 |
| Database | PostgreSQL (Hibernate/JPA) |
| Notifications | Twilio SDK |
| Build | Maven |
| Deployment | Docker / Railway |

---

## ⚙️ Configuration

The application reads all credentials from the environment. Set these in your deployment platform, or use the `local` profile below to run without any of them.

| Variable | Description |
| --- | --- |
| `DATABASE_URL` | JDBC URL for your PostgreSQL database |
| `DATABASE_USERNAME` | Database username |
| `DATABASE_PASSWORD` | Database password |
| `TWILIO_ACCOUNT_SID` | Your Twilio Account SID |
| `TWILIO_AUTH_TOKEN` | Your Twilio Auth Token |
| `TWILIO_PHONE_NUMBER` | The Twilio number the SMS is sent from |
| `PORT` | Server port (defaults to `8080`) |

> Never commit real credentials. `src/main/resources/application-local.properties` is gitignored for exactly this purpose.

---

## 📦 Running Locally

**1. Clone the repository**

```bash
git clone https://github.com/Aryaasd/rutgers-course-sniper.git
cd rutgers-course-sniper
```

**2. Run it**

The `local` profile starts the app against an in-memory H2 database, so you don't need Postgres or any cloud setup to try it:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Create `src/main/resources/application-local.properties` with your own H2 and Twilio settings (the file is gitignored). Leave the Twilio values blank to run without SMS — `SmsService` falls back to logging alerts to the console.

To run against a real PostgreSQL database instead, export the environment variables from the table above and run `./mvnw spring-boot:run`.

---

## 📡 Usage

Sections are registered through the REST API at `/api`.

**Start watching a section**

```bash
curl -X POST http://localhost:8080/api/sections \
  -H "Content-Type: application/json" \
  -d '{"sectionIndex": "08278", "userContact": "+15555550123"}'
```

**List everything being watched**

```bash
curl http://localhost:8080/api/sections
```

**Stop watching a section**

```bash
curl -X DELETE http://localhost:8080/api/sections/1
```

Once a section is registered, the scheduler polls it automatically and texts `userContact` when it opens.

---

## ☁️ Deployment (Railway)

1. **Connect GitHub:** Link this repository to a new Railway service.
2. **Add a database:** Add a PostgreSQL service in Railway.
3. **Set variables:** Railway provides the database connection automatically, but you must add your Twilio credentials in the **Variables** tab.
4. **Cap the heap:** To prevent Out-Of-Memory crashes on smaller tiers, add:

   ```
   JAVA_TOOL_OPTIONS=-Xmx300m -Xms300m
   ```

---

## ⚠️ Disclaimer

This tool is for educational purposes. Please use it responsibly and comply with the university's API usage policies to avoid rate limiting or IP bans.

---

## 📄 License

MIT
