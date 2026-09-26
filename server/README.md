# NEXVARY AI Radar Server

Continuously scans GitHub, Hugging Face and arXiv, de-duplicates discoveries in SQLite, ranks them and can publish Firebase Cloud Messaging notifications to the `ai-radar` topic.

## Run

```bash
cd server
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
uvicorn app:app --reload --port 8080
```

Endpoints: `GET /health`, `GET /projects`, `POST /scan`.

Set `FIREBASE_SERVICE_ACCOUNT_JSON` to the complete service-account JSON. Never commit credentials.
