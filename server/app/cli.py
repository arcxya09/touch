import argparse
import getpass
import re

from sqlalchemy import select

from .database import SessionLocal
from .models import User
from .security import password_hasher


def main():
    parser = argparse.ArgumentParser(description="Touch 账号初始化")
    parser.add_argument("command", choices=["create-admin", "reset-admin"])
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password-stdin", action="store_true", help="从标准输入读取密码，勿放入命令行参数")
    args = parser.parse_args()
    password = input() if args.password_stdin else getpass.getpass("管理员密码（至少 12 位）: ")
    if len(password) < 12 or len(password) > 128 or not re.fullmatch(r"[a-z0-9_]{3,32}", args.username):
        parser.error("账号须为 3—32 位小写字母/数字/下划线，密码须为 12—128 位")
    with SessionLocal() as db:
        user = db.scalar(select(User).where(User.username == args.username))
        if args.command == "create-admin":
            if user:
                parser.error("账号已经存在")
            user = User(username=args.username, display_name="管理员", is_admin=True, must_change_password=False)
            db.add(user)
        elif not user or not user.is_admin:
            parser.error("管理员不存在")
        else:
            user.session_epoch += 1
        user.password_hash = password_hasher.hash(password)
        db.commit()
    print("管理员账号已保存。")


if __name__ == "__main__":
    main()
