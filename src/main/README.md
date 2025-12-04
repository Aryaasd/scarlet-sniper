🎯 Rutgers Course Sniper

A robust, high-performance Java Spring Boot application designed to monitor course availability at Rutgers University. It tracks specific course sections and sends real-time notifications via Twilio when a closed section opens up.

🚀 Features

Automated Sniping: Periodically polls the Rutgers Schedule of Classes API to check section status.

Database Persistence: Stores user tracking requests and course metadata in PostgreSQL.

SMS Notifications: Integrates with Twilio to send instant alerts when a course opens.

Memory Optimized: Tuned to run efficiently in containerized environments (like Railway) with strict memory limits.

🛠️ Tech Stack

Language: Java 21

Framework: Spring Boot 3

Database: PostgreSQL (Hibernate/JPA)

Notifications: Twilio SDK

Build Tool: Maven

Deployment: Docker / Railway

⚙️ Configuration

The application requires the following environment variables to run. You can set these in your local .env file or in your deployment platform settings.

Variable

Description

SPRING_DATASOURCE_URL

JDBC URL for your PostgreSQL database

SPRING_DATASOURCE_USERNAME

Database username

SPRING_DATASOURCE_PASSWORD

Database password

TWILIO_ACCOUNT_SID

Your Twilio Account SID

TWILIO_AUTH_TOKEN

Your Twilio Auth Token

TWILIO_PHONE_NUMBER

The Twilio number sending the SMS

📦 Running Locally

Clone the repository

git clone [https://github.com/your-username/rutgers-course-sniper.git](https://github.com/your-username/rutgers-course-sniper.git)
cd rutgers-course-sniper


Set up PostgreSQL
Ensure you have a Postgres database running locally. Update your application.properties or set the environment variables listed above.

Build and Run

./mvnw spring-boot:run


☁️ Deployment (Railway)

This project is optimized for deployment on Railway.

Connect GitHub: Link this repository to a new service in Railway.

Add Database: Add a PostgreSQL database service in Railway.

Variables: Railway automatically provides DATABASE_URL, but you must manually add your Twilio credentials in the "Variables" tab.

Memory Limits:
To prevent Out-Of-Memory (OOM) crashes on smaller tiers, add the following variable:

JAVA_TOOL_OPTIONS: -Xmx300m -Xms300m

⚠️ Disclaimer

This tool is for educational purposes. Please use responsibly and ensure you comply with the university's API usage policies to avoid rate limiting or IP bans.

📄 License

MIT