"""Shared validation for app and web profiles. Avatars never have a public URL."""
import io
import warnings

from fastapi import HTTPException, UploadFile
from fastapi.responses import Response
from PIL import Image, ImageOps, UnidentifiedImageError
from pydantic import BaseModel, ConfigDict, Field, field_validator
from sqlalchemy import or_, select

from .models import Contact, Conversation, User, uid
from .services import emit


class Profile(BaseModel):
    model_config = ConfigDict(extra="forbid")
    display_name: str = Field(min_length=1, max_length=64)
    bio: str = Field(default="", max_length=160)

    @field_validator("display_name")
    @classmethod
    def nonempty_name(cls, value):
        if not value.strip():
            raise ValueError("昵称不能为空")
        return value.strip()


def read_avatar(file: UploadFile) -> bytes:
    data = file.file.read(5 * 1024 * 1024 + 1)
    if len(data) > 5 * 1024 * 1024:
        raise HTTPException(413, "头像不能超过 5 MiB")
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(data)) as source:
                if source.width * source.height > 20_000_000:
                    raise HTTPException(413, "头像像素过大")
                source.load()
                image = ImageOps.fit(ImageOps.exif_transpose(source).convert("RGB"), (512, 512))
                output = io.BytesIO()
                image.save(output, format="JPEG", quality=80)
                return output.getvalue()
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError,
            Image.DecompressionBombWarning) as exc:
        raise HTTPException(400, "请选择有效的图片作为头像") from exc


def set_avatar(user, data):
    user.avatar = data
    user.avatar_version = uid() if data is not None else None


def avatar_response(user, version=None):
    if not user or user.deleted_at or not user.avatar or (version and version != user.avatar_version):
        raise HTTPException(404, "头像不存在")
    return Response(user.avatar, media_type="image/jpeg", headers={"Cache-Control": "private, no-store",
                                                                 "X-Content-Type-Options": "nosniff"})


def profile_changed(db, user):
    # Callers hold all account locks in stable order before editing (<=100 users).
    peers = {user.id}
    for model in (Contact, Conversation):
        for row in db.scalars(select(model).where(or_(model.a == user.id, model.b == user.id))):
            peers.add(row.b if row.a == user.id else row.a)
    emit(db, [p for p in peers if db.get(User, p).deleted_at is None], "profile_changed", {})
