"""
auth/security.py — Security primitives

Responsibilities:
- Hash and verify passwords using bcrypt
- Create signed JWT access tokens
- Verify/decode incoming JWT tokens
- Provide the FastAPI dependency `get_current_user`
"""

from datetime import datetime, timedelta, timezone
from typing import Optional

from fastapi import Depends, HTTPException, status
from fastapi.security import OAuth2PasswordBearer
from jose import JWTError, jwt
from passlib.context import CryptContext
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select

from backend.config import settings
from backend.database.db import get_db
from backend.models.tables import User

# ── Password hashing ──────────────────────────────────────────────────────────
# bcrypt is the industry standard — it adds a salt automatically and is slow
# enough to resist brute-force attacks.
pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")


def hash_password(plain: str) -> str:
    """Return a bcrypt hash of the given plain-text password."""
    return pwd_context.hash(plain)


def verify_password(plain: str, hashed: str) -> bool:
    """Return True if the plain-text password matches the stored hash."""
    return pwd_context.verify(plain, hashed)


# ── JWT ───────────────────────────────────────────────────────────────────────
# The OAuth2PasswordBearer tells FastAPI where to look for the Bearer token.
# `tokenUrl` is the endpoint the frontend POSTs credentials to.
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/auth/login")


def create_access_token(data: dict, expires_delta: Optional[timedelta] = None) -> str:
    """
    Create a signed JWT.

    `data` is copied and a 'exp' (expiry) claim is added.
    The token is signed with the secret from .env using HS256.
    """
    payload = data.copy()
    expire = datetime.now(timezone.utc) + (
        expires_delta or timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)
    )
    payload.update({"exp": expire})
    return jwt.encode(payload, settings.JWT_SECRET, algorithm=settings.JWT_ALGORITHM)


async def get_current_user(
    token: str = Depends(oauth2_scheme),
    db: AsyncSession = Depends(get_db),
) -> User:
    """
    FastAPI dependency — decode the Bearer token and return the User row.

    Raises 401 if:
    - token is missing / malformed
    - signature is invalid
    - token is expired
    - the user in the token no longer exists
    """
    credentials_error = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Invalid or expired token",
        headers={"WWW-Authenticate": "Bearer"},
    )
    try:
        payload = jwt.decode(
            token, settings.JWT_SECRET, algorithms=[settings.JWT_ALGORITHM]
        )
        username: str = payload.get("sub")
        if username is None:
            raise credentials_error
    except JWTError:
        raise credentials_error

    result = await db.execute(select(User).where(User.username == username))
    user = result.scalar_one_or_none()
    if user is None:
        raise credentials_error
    return user
