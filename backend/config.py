from pathlib import Path
from pydantic_settings import BaseSettings

# Always resolve .env relative to this file — works from any launch directory
ENV_FILE = Path(__file__).parent / ".env"


class Settings(BaseSettings):
    JWT_SECRET: str
    JWT_ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 60
    DATABASE_URL: str
    DEVICE_SHARED_SECRET: str

    class Config:
        env_file = str(ENV_FILE)


settings = Settings()
