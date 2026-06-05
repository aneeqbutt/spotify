"""
routers/auth.py — Authentication endpoints

POST /auth/register  — create a new user account
POST /auth/login     — exchange credentials for a JWT access token
GET  /auth/me        — return the currently authenticated user's profile
"""

import logging
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select

from backend.database.db import get_db
from backend.models.tables import User
from backend.models.schemas import LoginRequest, TokenResponse
from backend.auth.security import (
    hash_password,
    verify_password,
    create_access_token,
    get_current_user,
)

logger = logging.getLogger("auth")
router = APIRouter(prefix="/auth", tags=["Auth"])


# ── Register ──────────────────────────────────────────────────────────────────
@router.post("/register", status_code=status.HTTP_201_CREATED)
async def register(body: LoginRequest, db: AsyncSession = Depends(get_db)):
    """
    Create a new user.

    - Rejects duplicate usernames with 409 Conflict
    - Stores a bcrypt hash — never the plain password
    """
    result = await db.execute(select(User).where(User.username == body.username))
    if result.scalar_one_or_none():
        raise HTTPException(status_code=409, detail="Username already taken")

    user = User(username=body.username, password_hash=hash_password(body.password))
    db.add(user)
    await db.commit()
    await db.refresh(user)

    logger.info(f"[AUTH] New user registered: {user.username} (id={user.id})")
    return {"id": user.id, "username": user.username}


# ── Login ─────────────────────────────────────────────────────────────────────
@router.post("/login", response_model=TokenResponse)
async def login(body: LoginRequest, db: AsyncSession = Depends(get_db)):
    """
    Exchange username + password for a JWT access token.

    The React frontend stores this token in localStorage and sends it as
    `Authorization: Bearer <token>` on every subsequent request.

    Returns 401 if credentials are wrong — intentionally vague to avoid
    leaking whether the username exists.
    """
    result = await db.execute(select(User).where(User.username == body.username))
    user = result.scalar_one_or_none()

    if not user or not verify_password(body.password, user.password_hash):
        logger.warning(f"[AUTH] Failed login attempt for username='{body.username}'")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid username or password",
        )

    token = create_access_token({"sub": user.username})
    logger.info(f"[AUTH] Login OK: username={user.username}")
    return {"access_token": token, "token_type": "bearer"}


# ── Me ────────────────────────────────────────────────────────────────────────
@router.get("/me")
async def me(current_user: User = Depends(get_current_user)):
    """
    Return the authenticated user's profile.

    The React dashboard calls this on load to verify the token is still valid
    and to display the logged-in username.
    """
    return {"id": current_user.id, "username": current_user.username}
