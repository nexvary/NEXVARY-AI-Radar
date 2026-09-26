import asyncio
import hashlib
import hmac
import json
import os
import sqlite3
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import feedparser
import httpx
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from fastapi import FastAPI, HTTPException, Request
from firebase_admin import credentials, initialize_app, messaging

DB_PATH = Path(os.getenv("RADAR_DB", "radar.db"))
SCAN_SECONDS = max(30, int(os.getenv("SCAN_SECONDS", "60")))
FCM_TOPIC = os.getenv("FCM_TOPIC", "ai-radar")
GITHUB_TOKEN = os.getenv("GITHUB_TOKEN", "")
HF_TOKEN = os.getenv("HF_TOKEN", "")
HF_WEBHOOK_SECRET = os.getenv("HF_WEBHOOK_SECRET", "")
GITHUB_WEBHOOK_SECRET = os.getenv("GITHUB_WEBHOOK_SECRET", "")

scheduler = AsyncIOScheduler(timezone="UTC")
firebase_ready = False

def db() -> sqlite3.Connection:
    c = sqlite3.connect(DB_PATH)
    c.row_factory = sqlite3.Row
    c.execute("""
        CREATE TABLE IF NOT EXISTS projects (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            description TEXT NOT NULL,
            purpose TEXT NOT NULL DEFAULT '',
            purpose_ar TEXT NOT NULL DEFAULT '',
            url TEXT NOT NULL,
            source TEXT NOT NULL,
            category TEXT NOT NULL,
            published_at TEXT NOT NULL,
            popularity INTEGER NOT NULL DEFAULT 0,
            score INTEGER NOT NULL DEFAULT 0,
            local_friendly INTEGER NOT NULL DEFAULT 0,
            first_seen_at TEXT NOT NULL
        )
    """)
    columns = {row["name"] for row in c.execute("PRAGMA table_info(projects)").fetchall()}
    if "purpose" not in columns:
        c.execute("ALTER TABLE projects ADD COLUMN purpose TEXT NOT NULL DEFAULT ''")
    if "purpose_ar" not in columns:
        c.execute("ALTER TABLE projects ADD COLUMN purpose_ar TEXT NOT NULL DEFAULT ''")
    c.execute("""
        CREATE TABLE IF NOT EXISTS webhook_deliveries (
            delivery_id TEXT PRIMARY KEY,
            source TEXT NOT NULL,
            received_at TEXT NOT NULL
        )
    """)
    c.commit()
    return c

def remember_delivery(delivery_id: str, source: str) -> bool:
    if not delivery_id:
        return True
    with db() as c:
        try:
            c.execute(
                "INSERT INTO webhook_deliveries(delivery_id,source,received_at) VALUES(?,?,?)",
                (delivery_id, source, datetime.now(timezone.utc).isoformat()),
            )
            return True
        except sqlite3.IntegrityError:
            return False

def init_firebase() -> None:
    global firebase_ready
    raw = os.getenv("FIREBASE_SERVICE_ACCOUNT_JSON", "").strip()
    if not raw:
        firebase_ready = False
        return
    try:
        initialize_app(credentials.Certificate(json.loads(raw)))
        firebase_ready = True
    except Exception:
        firebase_ready = False

def category(text: str) -> str:
    s = text.lower()
    if any(x in s for x in ("video", "diffusion", "motion")): return "Video AI"
    if any(x in s for x in ("agent", "agentic", "autonomous")): return "Agents"
    if any(x in s for x in ("security", "cyber", "malware", "forensic")): return "Cybersecurity AI"
    if any(x in s for x in ("audio", "voice", "speech", "tts")): return "Audio AI"
    if any(x in s for x in ("vision", "image", "ocr")): return "Vision AI"
    if any(x in s for x in ("code", "coding", "developer")): return "Coding AI"
    if any(x in s for x in ("llm", "language model", "transformer")): return "LLM"
    return "General AI"

def purpose_text(category_name: str, description: str, task: str | None = None, space: bool = False, arabic: bool = False) -> str:
    task = (task or "").lower()
    en = {
        "text-generation": "Generates, completes, or rewrites text from a prompt.",
        "text2text-generation": "Transforms input text into new text, including summarization, rewriting, or translation.",
        "text-to-image": "Generates images from written prompts.",
        "image-to-image": "Transforms or edits an input image using AI.",
        "image-to-text": "Analyzes images and produces text descriptions or answers.",
        "image-classification": "Classifies images into categories.",
        "object-detection": "Detects and locates objects inside images.",
        "automatic-speech-recognition": "Converts spoken audio into text.",
        "text-to-speech": "Converts written text into synthetic speech.",
        "audio-classification": "Classifies or recognizes content in audio.",
        "sentence-similarity": "Measures semantic similarity between pieces of text.",
        "feature-extraction": "Creates embeddings/features for search, clustering, or downstream AI tasks.",
        "question-answering": "Answers questions from supplied text or context.",
        "summarization": "Creates shorter summaries of longer text.",
        "translation": "Translates text between languages.",
    }
    ar = {
        "text-generation": "نموذج لتوليد النصوص أو إكمالها أو إعادة صياغتها انطلاقًا من تعليمات المستخدم.",
        "text2text-generation": "نموذج يحوّل النص إلى نص آخر مثل التلخيص أو إعادة الصياغة أو الترجمة.",
        "text-to-image": "نموذج لإنشاء الصور من الأوامر والوصف النصي.",
        "image-to-image": "نموذج لتعديل الصور أو تحويلها بالذكاء الاصطناعي.",
        "image-to-text": "نموذج لتحليل الصور وتحويل محتواها إلى وصف أو إجابة نصية.",
        "image-classification": "نموذج لتصنيف الصور والتعرف على نوع محتواها.",
        "object-detection": "نموذج لاكتشاف الأجسام داخل الصور وتحديد مواقعها.",
        "automatic-speech-recognition": "نموذج لتحويل الكلام والتسجيلات الصوتية إلى نص.",
        "text-to-speech": "نموذج لتحويل النص المكتوب إلى صوت اصطناعي.",
        "audio-classification": "نموذج للتعرف على محتوى الصوت وتصنيفه.",
        "sentence-similarity": "نموذج لقياس التشابه في المعنى بين النصوص.",
        "feature-extraction": "نموذج لاستخراج تمثيلات رقمية تستخدم في البحث والتصنيف وتطبيقات الذكاء الاصطناعي.",
        "question-answering": "نموذج للإجابة عن الأسئلة اعتمادًا على نص أو سياق مقدم.",
        "summarization": "نموذج لتلخيص النصوص الطويلة إلى خلاصة أقصر.",
        "translation": "نموذج لترجمة النصوص بين اللغات.",
    }
    if task in (ar if arabic else en):
        return (ar if arabic else en)[task]
    if arabic:
        prefix = {
            "Video AI": "مشروع ذكاء اصطناعي لإنشاء الفيديو أو فهمه أو معالجته.",
            "Agents": "مشروع وكلاء ذكاء اصطناعي لأتمتة المهام وتنفيذها بصورة شبه مستقلة.",
            "Cybersecurity AI": "مشروع يستخدم الذكاء الاصطناعي في الأمن السيبراني أو التحليل أو الكشف أو التحقيق الرقمي.",
            "Audio AI": "مشروع لمعالجة الصوت أو الكلام بالذكاء الاصطناعي.",
            "Vision AI": "مشروع للرؤية الحاسوبية أو فهم الصور أو توليدها أو OCR.",
            "Coding AI": "مشروع لمساعدة المطورين أو توليد وتحليل الشفرة البرمجية.",
            "LLM": "مشروع نموذج لغوي كبير لفهم النصوص أو توليدها.",
            "AI Apps": "تطبيق ذكاء اصطناعي تفاعلي جاهز للتجربة.",
        }.get(category_name, "مشروع جديد في مجال الذكاء الاصطناعي.")
        return prefix + " الوصف الأصلي: " + description[:220]
    if space:
        return "Interactive AI application hosted on Hugging Face Spaces. " + description[:220]
    return description[:320] or "Artificial-intelligence project."

def is_local(text: str) -> bool:
    s = text.lower()
    return any(x in s for x in ("gguf", "onnx", "quantized", "local", "cpu", "edge", "mobile", "llama.cpp"))

def score(popularity: int, published_at: str, local: bool) -> int:
    freshness = 15
    try:
        dt = datetime.fromisoformat(published_at.replace("Z", "+00:00"))
        age_h = max(0, int((datetime.now(timezone.utc) - dt).total_seconds() / 3600))
        freshness = max(0, min(45, 45 - age_h // 6))
    except Exception:
        pass
    momentum = 40 if popularity >= 5000 else 34 if popularity >= 1000 else 27 if popularity >= 250 else 20 if popularity >= 50 else 12 if popularity >= 10 else 5
    return max(0, min(100, freshness + momentum + (15 if local else 5)))

async def fetch_github(client: httpx.AsyncClient) -> list[dict[str, Any]]:
    since = (datetime.now(timezone.utc) - timedelta(days=7)).date().isoformat()
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "NEXVARY-AI-Radar"}
    if GITHUB_TOKEN:
        headers["Authorization"] = f"Bearer {GITHUB_TOKEN}"
    queries = [
        f"topic:artificial-intelligence created:>={since}",
        f"topic:llm created:>={since}",
        f"topic:generative-ai created:>={since}",
        f"topic:ai-agents created:>={since}",
    ]
    out = []
    for q in queries:
        r = await client.get("https://api.github.com/search/repositories", params={"q": q, "sort": "stars", "order": "desc", "per_page": 30}, headers=headers)
        if r.status_code != 200:
            continue
        for o in r.json().get("items", []):
            text = " ".join([o.get("full_name", ""), o.get("description") or "", " ".join(o.get("topics") or [])])
            local = is_local(text)
            published = o.get("created_at") or datetime.now(timezone.utc).isoformat()
            stars = int(o.get("stargazers_count") or 0)
            out.append({
                "id": f"gh:{o.get('id')}",
                "name": o.get("full_name") or "GitHub project",
                "description": o.get("description") or "New AI project on GitHub",
                "purpose": purpose_text(category(text), o.get("description") or "New AI project on GitHub"),
                "purpose_ar": purpose_text(category(text), o.get("description") or "New AI project on GitHub", arabic=True),
                "url": o.get("html_url") or "",
                "source": "GitHub",
                "category": category(text),
                "published_at": published,
                "popularity": stars,
                "score": score(stars, published, local),
                "local_friendly": local,
            })
    return out

async def fetch_huggingface(client: httpx.AsyncClient) -> list[dict[str, Any]]:
    headers = {"Authorization": f"Bearer {HF_TOKEN}"} if HF_TOKEN else {}
    out = []
    for endpoint, prefix in (("models", ""), ("spaces", "spaces/")):
        r = await client.get(f"https://huggingface.co/api/{endpoint}", params={"sort": "lastModified", "direction": "-1", "limit": 60}, headers=headers)
        if r.status_code != 200:
            continue
        for o in r.json():
            item_id = o.get("id")
            if not item_id:
                continue
            tags = o.get("tags") or []
            pipeline = o.get("pipeline_tag")
            text = " ".join([item_id, " ".join(tags), pipeline or ""])
            local = is_local(text)
            published = o.get("lastModified") or datetime.now(timezone.utc).isoformat()
            likes = int(o.get("likes") or 0)
            description = " • ".join(tags[:6]) or f"New Hugging Face {endpoint[:-1]}"
            cat = "AI Apps" if endpoint == "spaces" else category(text)
            out.append({
                "id": f"hf:{endpoint}:{item_id}",
                "name": item_id,
                "description": description,
                "purpose": purpose_text(cat, description, pipeline, space=endpoint == "spaces"),
                "purpose_ar": purpose_text(cat, description, pipeline, space=endpoint == "spaces", arabic=True),
                "url": f"https://huggingface.co/{prefix}{item_id}",
                "source": "Hugging Face",
                "category": cat,
                "published_at": published,
                "popularity": likes,
                "score": score(likes * 2, published, local),
                "local_friendly": local,
            })
    return out

async def fetch_arxiv(client: httpx.AsyncClient) -> list[dict[str, Any]]:
    url = "https://export.arxiv.org/api/query?search_query=cat:cs.AI+OR+cat:cs.LG+OR+cat:cs.CL+OR+cat:cs.CV&sortBy=submittedDate&sortOrder=descending&max_results=50"
    r = await client.get(url, headers={"User-Agent": "NEXVARY-AI-Radar/1.0"})
    if r.status_code != 200:
        return []
    feed = feedparser.loads(r.text)
    out = []
    for e in feed.entries:
        identity = hashlib.sha256(e.link.encode()).hexdigest()[:24]
        text = f"{e.title} {getattr(e, 'summary', '')}"
        local = is_local(text)
        try:
            published = datetime(*e.published_parsed[:6], tzinfo=timezone.utc).isoformat()
        except Exception:
            published = datetime.now(timezone.utc).isoformat()
        out.append({
            "id": f"arxiv:{identity}",
            "name": " ".join(e.title.split()),
            "description": " ".join(getattr(e, "summary", "").split())[:500],
            "purpose": " ".join(getattr(e, "summary", "").split())[:320],
            "purpose_ar": "بحث جديد في مجال " + category(text) + " يدرس موضوع: " + " ".join(e.title.split()) + ".",
            "url": e.link,
            "source": "arXiv",
            "category": category(text),
            "published_at": published,
            "popularity": 0,
            "score": score(0, published, local),
            "local_friendly": local,
        })
    return out

def save_new(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    now = datetime.now(timezone.utc).isoformat()
    new_items = []
    with db() as c:
        for p in items:
            exists = c.execute("SELECT 1 FROM projects WHERE id=?", (p["id"],)).fetchone()
            c.execute("""
                INSERT INTO projects(id,name,description,purpose,purpose_ar,url,source,category,published_at,popularity,score,local_friendly,first_seen_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    name=excluded.name, description=excluded.description, purpose=excluded.purpose,
                    purpose_ar=excluded.purpose_ar, url=excluded.url,
                    category=excluded.category, published_at=excluded.published_at,
                    popularity=excluded.popularity, score=excluded.score,
                    local_friendly=excluded.local_friendly
            """, (p["id"], p["name"], p["description"], p.get("purpose", ""), p.get("purpose_ar", ""),
                  p["url"], p["source"], p["category"], p["published_at"], p["popularity"], p["score"],
                  1 if p["local_friendly"] else 0, now))
            if not exists:
                new_items.append(p)
    return new_items

def push(items: list[dict[str, Any]]) -> int:
    if not firebase_ready or not items:
        return 0
    sent = 0
    for p in sorted(items, key=lambda x: x["score"], reverse=True)[:5]:
        msg = messaging.Message(
            topic=FCM_TOPIC,
            notification=messaging.Notification(title=f"New AI • {p['category']}", body=p["name"][:120]),
            data={"id": p["id"], "url": p["url"], "source": p["source"], "score": str(p["score"])},
            android=messaging.AndroidConfig(priority="high"),
        )
        try:
            messaging.send(msg)
            sent += 1
        except Exception:
            pass
    return sent

async def scan_once() -> dict[str, int]:
    async with httpx.AsyncClient(timeout=20.0, follow_redirects=True) as client:
        parts = await asyncio.gather(fetch_github(client), fetch_huggingface(client), fetch_arxiv(client), return_exceptions=True)
    merged = []
    for part in parts:
        if isinstance(part, list):
            merged.extend(part)
    unique = {p["url"]: p for p in merged if p.get("url")}
    new_items = save_new(list(unique.values()))
    return {"fetched": len(unique), "new": len(new_items), "pushed": push(new_items)}

@asynccontextmanager
async def lifespan(app: FastAPI):
    db().close()
    init_firebase()
    scheduler.add_job(scan_once, "interval", seconds=SCAN_SECONDS, id="radar-scan", max_instances=1, coalesce=True)
    scheduler.start()
    asyncio.create_task(scan_once())
    yield
    scheduler.shutdown(wait=False)

app = FastAPI(title="NEXVARY AI Radar API", version="0.2.0", lifespan=lifespan)

@app.get("/health")
async def health():
    return {"ok": True, "firebase": firebase_ready, "scan_seconds": SCAN_SECONDS}

@app.get("/projects")
async def projects(limit: int = 100, source: str | None = None, category_name: str | None = None):
    limit = max(1, min(limit, 500))
    sql = "SELECT * FROM projects"
    args: list[Any] = []
    where = []
    if source:
        where.append("source=?")
        args.append(source)
    if category_name:
        where.append("category=?")
        args.append(category_name)
    if where:
        sql += " WHERE " + " AND ".join(where)
    sql += " ORDER BY first_seen_at DESC, score DESC LIMIT ?"
    args.append(limit)
    with db() as c:
        return [dict(r) for r in c.execute(sql, args).fetchall()]

@app.post("/scan")
async def scan():
    try:
        return await scan_once()
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))


@app.post("/webhooks/huggingface")
async def huggingface_webhook(request: Request):
    if HF_WEBHOOK_SECRET and request.headers.get("X-Webhook-Secret") != HF_WEBHOOK_SECRET:
        raise HTTPException(status_code=401, detail="Invalid webhook secret")
    delivery_id = request.headers.get("Webhook-Id", "")
    if not remember_delivery(delivery_id, "huggingface"):
        return {"ok": True, "duplicate": True}
    payload = await request.json()
    event = payload.get("event") or {}
    repo = payload.get("repo") or {}
    if event.get("scope") != "repo" or event.get("action") not in {"create", "update"}:
        return {"ok": True, "ignored": True}
    name = repo.get("name")
    web_url = ((repo.get("url") or {}).get("web"))
    repo_type = repo.get("type") or "model"
    if not name or not web_url:
        raise HTTPException(status_code=400, detail="Missing repo fields")
    text = f"{name} {repo_type}"
    item = {
        "id": f"hf:webhook:{repo.get('id') or name}",
        "name": name,
        "description": f"Hugging Face {repo_type} webhook event",
        "purpose": purpose_text("AI Apps" if repo_type == "space" else category(text), f"Hugging Face {repo_type} webhook event", space=repo_type == "space"),
        "purpose_ar": purpose_text("AI Apps" if repo_type == "space" else category(text), f"Hugging Face {repo_type} webhook event", space=repo_type == "space", arabic=True),
        "url": web_url,
        "source": "Hugging Face",
        "category": "AI Apps" if repo_type == "space" else category(text),
        "published_at": datetime.now(timezone.utc).isoformat(),
        "popularity": 0,
        "score": 65,
        "local_friendly": is_local(text),
    }
    new_items = save_new([item])
    return {"ok": True, "new": len(new_items), "pushed": push(new_items)}

@app.post("/webhooks/github")
async def github_webhook(request: Request):
    body = await request.body()
    signature = request.headers.get("X-Hub-Signature-256", "")
    if GITHUB_WEBHOOK_SECRET:
        expected = "sha256=" + hmac.new(
            GITHUB_WEBHOOK_SECRET.encode(), body, hashlib.sha256
        ).hexdigest()
        if not hmac.compare_digest(signature, expected):
            raise HTTPException(status_code=401, detail="Invalid webhook signature")
    delivery_id = request.headers.get("X-GitHub-Delivery", "")
    if not remember_delivery(delivery_id, "github"):
        return {"ok": True, "duplicate": True}
    event_name = request.headers.get("X-GitHub-Event", "")
    payload = json.loads(body.decode("utf-8") or "{}")
    if event_name != "repository" or payload.get("action") not in {"created", "publicized"}:
        return {"ok": True, "ignored": True}
    repo = payload.get("repository") or {}
    name = repo.get("full_name")
    url = repo.get("html_url")
    if not name or not url:
        raise HTTPException(status_code=400, detail="Missing repository fields")
    desc = repo.get("description") or "New GitHub repository"
    topics = repo.get("topics") or []
    text = " ".join([name, desc, " ".join(topics)])
    local = is_local(text)
    stars = int(repo.get("stargazers_count") or 0)
    item = {
        "id": f"gh:webhook:{repo.get('id') or name}",
        "name": name,
        "description": desc,
        "purpose": purpose_text(category(text), desc),
        "purpose_ar": purpose_text(category(text), desc, arabic=True),
        "url": url,
        "source": "GitHub",
        "category": category(text),
        "published_at": repo.get("created_at") or datetime.now(timezone.utc).isoformat(),
        "popularity": stars,
        "score": score(stars, repo.get("created_at") or datetime.now(timezone.utc).isoformat(), local),
        "local_friendly": local,
    }
    new_items = save_new([item])
    return {"ok": True, "new": len(new_items), "pushed": push(new_items)}
