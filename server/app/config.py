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
    minimum_free_bytes: int = 256 * 1024 * 1024
    upload_concurrency: int = 2
    backup_max_age_seconds: int = 36 * 3600

    @property
    def version(self) -> str:
        override = os.getenv("TOUCH_VERSION")
        if override:
            return override
        for path in (Path(__file__).resolve().parents[2] / "version.properties",
                     Path(__file__).resolve().parents[1] / "version.properties"):
            if path.is_file():
                for line in path.read_text(encoding="utf-8").splitlines():
                    if line.startswith("versionName="):
                        return line.split("=", 1)[1]
        return "development"

    @property
    def build(self) -> str:
        return os.getenv("TOUCH_BUILD", "local")


settings = Settings()
