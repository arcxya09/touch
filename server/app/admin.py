import secrets
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import RedirectResponse
from fastapi.templating import Jinja2Templates
from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session
from sqlalchemy.exc import IntegrityError
from pydantic import ValidationError

from .config import settings
from .account_deletion import delete_account, lock_accounts
from .database import get_db
from .models import AdminSession, Attachment, Audit, MobileSession, User, now
from .security import digest, dummy_hash, limiter, password_hasher, token, verify_password
from .services import emit
from .profiles import Profile, avatar_response, profile_changed, read_avatar, set_avatar
from .passwords import replace_password
from .operations import operational_status

router = APIRouter(prefix="/admin", include_in_schema=False)
templates = Jinja2Templates(directory=str(Path(__file__).parent / "templates"))
templates.env.filters["local_time"] = lambda value: (
    datetime.fromtimestamp(value, timezone(timedelta(hours=8))).strftime("%m-%d %H:%M") if value else "尚无记录")
NOTICES = {"created": "账号已创建，请妥善交付临时密码。", "profile": "资料已保存。",
           "reset": "密码已重置，原登录会话已退出。", "disable": "账号已停用。", "enable": "账号已恢复。",
           "deleted": "账号已删除。", "password": "密码已更新，其他登录会话已退出。"}
AUDIT_LABELS = {"create_admin": "创建管理员", "create_user": "创建账号", "edit_profile": "编辑资料",
               "reset": "重置密码", "disable": "停用账号", "enable": "恢复账号", "delete_user": "删除账号",
               "change_admin_password": "修改密码"}


def dashboard_response(request, db, csrf, error=None, values=None, status=200):
    users = db.scalars(select(User).where(User.deleted_at.is_(None)).order_by(User.created_at.desc())).all()
    audits = db.scalars(select(Audit).order_by(Audit.created_at.desc()).limit(30)).all()
    names = {user.id: user.username for user in db.scalars(select(User))}
    size = db.scalar(select(func.coalesce(func.sum(Attachment.size), 0)))
    return templates.TemplateResponse(request=request, name="dashboard.html", status_code=status,
        context={"users": users, "csrf": csrf, "size_mb": round(size / 1024 / 1024, 1), "audits": audits,
                 "names": names, "audit_labels": AUDIT_LABELS, "operations": operational_status(),
                 "version": settings.version, "error": error, "values": values or {},
                 "notice": NOTICES.get(request.query_params.get("notice"))})


def authenticated_admin(request: Request, db: Session = Depends(get_db)):
    sid = request.cookies.get("touch_admin", "")
    session = db.get(AdminSession, digest(sid)) if sid else None
    user = db.get(User, session.user_id) if session else None
    if (not session or session.expires <= now() or not user or not user.active or not user.is_admin
            or session.epoch != user.session_epoch):
        raise HTTPException(303, headers={"Location": "/admin/login"})
    return session, user


def admin_session(auth=Depends(authenticated_admin)):
    if auth[1].must_change_password:
        raise HTTPException(303, headers={"Location": "/admin/password"})
    return auth


def check_csrf(request: Request, given: str, expected: str | None):
    origin = request.headers.get("origin")
    if (not expected or not secrets.compare_digest(given.encode(), expected.encode())
            or (origin and origin.rstrip("/") != settings.public_base_url)):
        raise HTTPException(403, "页面已过期，请刷新后重试")


@router.get("/profile")
def own_profile(auth=Depends(admin_session)):
    return RedirectResponse(f"/admin/users/{auth[1].id}/profile", 303)


@router.get("/users/{user_id}/avatar")
def admin_avatar(user_id: str, auth=Depends(admin_session), db: Session = Depends(get_db)):
    return avatar_response(db.get(User, user_id))


@router.get("/users/{user_id}/profile")
def profile_page(user_id: str, request: Request, auth=Depends(admin_session), db: Session = Depends(get_db)):
    user = db.get(User, user_id)
    if not user or user.deleted_at:
        raise HTTPException(404, "账号不存在")
    return templates.TemplateResponse(request=request, name="profile.html",
                                      context={"user": user, "csrf": auth[0].csrf})


@router.post("/users/{user_id}/profile")
def save_profile(user_id: str, request: Request, display_name: str = Form(max_length=64),
                 bio: str = Form(default="", max_length=160), avatar: UploadFile | None = File(default=None),
                 remove_avatar: bool = Form(default=False), csrf: str = Form(),
                 auth=Depends(admin_session), db: Session = Depends(get_db)):
    check_csrf(request, csrf, auth[0].csrf)
    user = db.get(User, user_id)
    if not user or user.deleted_at:
        raise HTTPException(404, "账号不存在")
    try:
        profile = Profile(display_name=display_name, bio=bio)
        data = read_avatar(avatar) if avatar and avatar.filename else None
    except (ValidationError, HTTPException) as error:
        return templates.TemplateResponse(request=request, name="profile.html", status_code=getattr(error, "status_code", 400),
            context={"user": user, "csrf": csrf, "values": {"display_name": display_name, "bio": bio},
                     "error": error.detail if isinstance(error, HTTPException) else "昵称不能为空，且不超过 64 字。"})
    user = lock_accounts(db).get(user_id)
    if not user or user.deleted_at:
        raise HTTPException(404, "账号不存在")
    user.display_name, user.bio = profile.display_name, profile.bio.strip()
    if data is not None or remove_avatar:
        set_avatar(user, data)
    profile_changed(db, user)
    db.add(Audit(actor_id=auth[1].id, action="edit_profile", target_id=user.id))
    db.commit()
    return RedirectResponse("/admin?notice=profile", 303)


@router.get("/login")
def login_page(request: Request):
    csrf = token()
    response = templates.TemplateResponse(request=request, name="login.html", context={"csrf": csrf})
    response.set_cookie("touch_login_csrf", csrf, httponly=True, secure=settings.secure_cookies,
                        samesite="strict", path="/admin", max_age=600)
    return response


@router.post("/login")
def admin_login(request: Request, username: str = Form(max_length=32), password: str = Form(max_length=128),
                csrf: str = Form(), db: Session = Depends(get_db)):
    check_csrf(request, csrf, request.cookies.get("touch_login_csrf"))
    limiter.check("admin-login:" + request.client.host, 5, 300)
    user = db.scalar(select(User).where(User.username == username.strip().lower()))
    valid = verify_password(user.password_hash if user else dummy_hash, password)
    if not user or not valid or not user.active or not user.is_admin:
        return templates.TemplateResponse(request=request, name="login.html", status_code=401,
            context={"csrf": csrf, "username": username, "error": "账号或密码不正确，请重新输入。"})
    raw = token()
    db.add(AdminSession(token_hash=digest(raw), user_id=user.id, csrf=token(),
                        expires=now() + 8 * 3600, epoch=user.session_epoch))
    db.commit()
    response = RedirectResponse("/admin/password" if user.must_change_password else "/admin", 303)
    response.set_cookie("touch_admin", raw, httponly=True, secure=settings.secure_cookies,
                        samesite="strict", path="/admin", max_age=8 * 3600)
    response.delete_cookie("touch_login_csrf", path="/admin")
    return response


@router.get("")
def dashboard(request: Request, auth=Depends(admin_session), db: Session = Depends(get_db)):
    return dashboard_response(request, db, auth[0].csrf)


@router.post("/users")
def create_user(request: Request, username: str = Form(pattern=r"^[a-zA-Z0-9_]{3,32}$"),
                display_name: str = Form(min_length=1, max_length=64), password: str = Form(min_length=10, max_length=128),
                role: Literal["user", "admin"] = Form(default="user"),
                csrf: str = Form(), auth=Depends(admin_session), db: Session = Depends(get_db)):
    check_csrf(request, csrf, auth[0].csrf)
    name = username.lower()
    values = {"username": username, "display_name": display_name, "role": role}
    if not display_name.strip():
        return dashboard_response(request, db, csrf, "显示名称不能为空。", values, 400)
    if db.scalar(select(User).where(User.username == name)):
        return dashboard_response(request, db, csrf, "账号已存在，请换一个账号名。", values, 409)
    user = User(username=name, display_name=display_name.strip(), password_hash=password_hasher.hash(password),
                is_admin=role == "admin")
    db.add(user)
    try:
        db.flush()
    except IntegrityError:
        db.rollback()
        return dashboard_response(request, db, csrf, "账号已存在，请换一个账号名。", values, 409)
    db.add(Audit(actor_id=auth[1].id, action="create_admin" if user.is_admin else "create_user", target_id=user.id))
    db.commit()
    return RedirectResponse("/admin?notice=created", 303)


@router.post("/users/{user_id}")
def update_user(user_id: str, request: Request, action: str = Form(), csrf: str = Form(),
                password: str = Form(default="", max_length=128), auth=Depends(admin_session), db: Session = Depends(get_db)):
    check_csrf(request, csrf, auth[0].csrf)
    user = db.scalar(select(User).where(User.id == user_id).with_for_update())
    if not user or user.is_admin or user.deleted_at is not None:
        raise HTTPException(400, "此账号不可通过该页面修改")
    if action == "reset":
        if len(password) < 10:
            raise HTTPException(400, "临时密码至少 10 位")
        user.password_hash = password_hasher.hash(password)
        user.must_change_password = True
    elif action in ("disable", "enable"):
        user.active = action == "enable"
    else:
        raise HTTPException(400, "未知操作")
    user.session_epoch += 1
    db.execute(delete(MobileSession).where(MobileSession.user_id == user.id))
    emit(db, [user.id], "account_changed", {})
    db.add(Audit(actor_id=auth[1].id, action=action, target_id=user.id))
    db.commit()
    return RedirectResponse("/admin?notice=" + action, 303)


def deletable_user(user):
    if not user or user.deleted_at is not None:
        raise HTTPException(404, "账号不存在或已删除")
    if user.is_admin:
        raise HTTPException(400, "管理员账号不能删除")
    return user


@router.get("/users/{user_id}/delete")
def delete_user_page(user_id: str, request: Request, auth=Depends(admin_session), db: Session = Depends(get_db)):
    user = deletable_user(db.get(User, user_id))
    return templates.TemplateResponse(request=request, name="delete_user.html",
        context={"user": user, "csrf": auth[0].csrf})


@router.post("/users/{user_id}/delete")
def delete_user(user_id: str, request: Request, confirmation: str = Form(max_length=32), csrf: str = Form(),
                auth=Depends(admin_session), db: Session = Depends(get_db)):
    check_csrf(request, csrf, auth[0].csrf)
    user = deletable_user(lock_accounts(db).get(user_id))
    if confirmation != user.username:
        return templates.TemplateResponse(request=request, name="delete_user.html", status_code=400,
            context={"user": user, "csrf": auth[0].csrf, "error": "账号名不匹配，请输入下方显示的完整账号名。"})
    delete_account(db, user)
    db.add(Audit(actor_id=auth[1].id, action="delete_user", target_id=user.id))
    db.commit()
    return RedirectResponse("/admin?notice=deleted", 303)


@router.post("/logout")
def admin_logout(request: Request, csrf: str = Form(), auth=Depends(authenticated_admin), db: Session = Depends(get_db)):
    check_csrf(request, csrf, auth[0].csrf)
    db.delete(auth[0])
    db.commit()
    response = RedirectResponse("/admin/login", 303)
    response.delete_cookie("touch_admin", path="/admin")
    return response


@router.get("/password")
def password_page(request: Request, auth=Depends(authenticated_admin)):
    return templates.TemplateResponse(request=request, name="password.html", context={"csrf": auth[0].csrf})


@router.post("/password")
def change_admin_password(request: Request, current_password: str = Form(max_length=128),
                          new_password: str = Form(min_length=10, max_length=128),
                          confirmation: str = Form(max_length=128), csrf: str = Form(),
                          auth=Depends(authenticated_admin), db: Session = Depends(get_db)):
    check_csrf(request, csrf, auth[0].csrf)
    limiter.check("admin-password:" + auth[1].id, 5, 60)
    user = db.scalar(select(User).where(User.id == auth[1].id).with_for_update()
                     .execution_options(populate_existing=True))
    fresh = db.get(AdminSession, auth[0].token_hash, populate_existing=True)
    if not fresh or fresh.epoch != user.session_epoch or fresh.expires <= now() or not user.active or not user.is_admin:
        raise HTTPException(303, headers={"Location": "/admin/login"})
    try:
        if new_password != confirmation:
            raise HTTPException(400, "两次输入的新密码不一致。")
        replace_password(db, user, current_password, new_password)
    except HTTPException as error:
        return templates.TemplateResponse(request=request, name="password.html", status_code=400,
            context={"csrf": auth[0].csrf, "error": error.detail})
    raw = token()
    db.add(AdminSession(token_hash=digest(raw), user_id=user.id, csrf=token(),
                        expires=now() + 8 * 3600, epoch=user.session_epoch))
    db.add(Audit(actor_id=user.id, action="change_admin_password", target_id=user.id))
    db.commit()
    response = RedirectResponse("/admin?notice=password", 303)
    response.set_cookie("touch_admin", raw, httponly=True, secure=settings.secure_cookies,
                        samesite="strict", path="/admin", max_age=8 * 3600)
    return response
