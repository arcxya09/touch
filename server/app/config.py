import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    database_url: str = os.getenv("DATABASE_URL", "sqlite:///./data/touch.db")
    data_dir: Path = Path(os.getenv("DATA_DIR", "./data"))
    public_base_url: str = os.getenv("PUBLIC_BASE_URL", "http://127.0.0.1:8000").rstrip("/")
    secure_cookies: bool = os.getenv("SECURE_COOKIES", "true").lower() == "true"
    image_limit: int = 20 * 1024 * 1024
    file_limit: int = 100 * 1024 * 1024
    access_seconds: int = 1800
    refresh_seconds: int = 30 * 86400


settings = Settings()
