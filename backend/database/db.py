from pathlib import Path
from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine
from sqlalchemy.orm import sessionmaker, DeclarativeBase
from backend.config import settings

# Resolve DB path to backend/ folder regardless of launch directory
_db_url = settings.DATABASE_URL
if _db_url.startswith("sqlite"):
    _db_path = Path(__file__).parent.parent / "spotify_automation.db"
    _db_url = f"sqlite+aiosqlite:///{_db_path}"

engine = create_async_engine(_db_url, echo=False)

AsyncSessionLocal = sessionmaker(
    bind=engine,
    class_=AsyncSession,
    expire_on_commit=False,
)


class Base(DeclarativeBase):
    pass


async def get_db():
    """Dependency — yields a DB session per request, always closes it."""
    async with AsyncSessionLocal() as session:
        try:
            yield session
        finally:
            await session.close()


async def init_db():
    """Called once on startup — creates all tables if they don't exist."""
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
