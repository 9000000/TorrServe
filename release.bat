@echo off & python -x "%~f0" %* & exit /b %ERRORLEVEL%
# -*- coding: utf-8 -*-
"""
release.bat / Python polyglot script
Tự động biên dịch (build), ký (sign), tạo git tag và đẩy bản Release APK lên GitHub.
"""

import os
import sys

if sys.stdout.encoding != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    except Exception:
        pass

import argparse
import json
import re
import shutil
import subprocess
import urllib.request
import urllib.error
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BUILD_GRADLE = ROOT / "app" / "build.gradle"
LOCAL_PROPS = ROOT / "local.properties"
KEYSTORE_PROPS = ROOT / "keystore.properties"
APK_RELEASE_DIR = ROOT / "app" / "build" / "outputs" / "apk" / "release"


def print_step(step: str, title: str):
    print(f"\n\033[1;36m[{step}]\033[0m \033[1m{title}\033[0m")


def print_success(msg: str):
    print(f"\033[1;32m[OK]\033[0m {msg}")


def print_warn(msg: str):
    print(f"\033[1;33m[WARN]\033[0m {msg}")


def print_error(msg: str):
    print(f"\033[1;31m[ERROR]\033[0m {msg}")


def read_property_from_file(file_path: Path, key: str) -> str:
    if not file_path.exists():
        return ""
    try:
        with open(file_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith("#") or not line:
                    continue
                if line.startswith(f"{key}=") or line.startswith(f"{key} ="):
                    return line.split("=", 1)[1].strip()
    except Exception:
        pass
    return ""


def get_token() -> str:
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        return token.strip()
    for prop_file in [LOCAL_PROPS, KEYSTORE_PROPS]:
        val = read_property_from_file(prop_file, "GITHUB_TOKEN") or read_property_from_file(prop_file, "GH_TOKEN")
        if val:
            return val
    return ""


def get_git_info():
    branch = "master"
    try:
        res = subprocess.run(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=True
        )
        b = res.stdout.strip()
        if b and b != "HEAD":
            branch = b
    except Exception:
        pass

    owner, repo = "9000000", "TorrServe"
    try:
        res = subprocess.run(
            ["git", "config", "--get", "remote.origin.url"],
            cwd=ROOT,
            capture_output=True,
            text=True
        )
        url = res.stdout.strip()
        m = re.search(r"github\.com[/:]([^/]+)/([^/\.]+)(?:\.git)?", url)
        if m:
            owner, repo = m.group(1), m.group(2)
    except Exception:
        pass

    return branch, owner, repo


def read_app_version():
    version_name = "MatriX.143.Client"
    version_code = 143
    if BUILD_GRADLE.exists():
        try:
            content = BUILD_GRADLE.read_text(encoding="utf-8")
            m_vn = re.search(r'versionName\s+["\']([^"\']+)["\']', content)
            if m_vn:
                version_name = m_vn.group(1).strip()
            m_vc = re.search(r'versionCode\s+(\d+)', content)
            if m_vc:
                version_code = int(m_vc.group(1).strip())
        except Exception as e:
            print_warn(f"Không thể đọc thông tin version từ app/build.gradle: {e}")
    return version_name, version_code


def get_recent_commits(count=5) -> str:
    try:
        res = subprocess.run(
            ["git", "log", f"-n{count}", "--oneline", "--no-merges"],
            cwd=ROOT,
            capture_output=True,
            text=True
        )
        lines = res.stdout.strip().splitlines()
        notes = []
        for line in lines:
            parts = line.split(" ", 1)
            if len(parts) == 2:
                notes.append(f"- {parts[1]}")
            else:
                notes.append(f"- {line}")
        return "\n".join(notes)
    except Exception:
        return "- Cập nhật TorrServe Client"


def build_release_apk():
    print_step("1/4", "Đang biên dịch bản Release APK...")
    gradle_bat = ROOT / "gradlew.bat"
    cmd = [str(gradle_bat) if gradle_bat.exists() else "gradlew", ":app:assembleRelease"]
    print(f"   Thực thi: {' '.join(cmd)}")
    res = subprocess.run(cmd, cwd=ROOT)
    if res.returncode != 0:
        print_error("Biên dịch Release APK thất bại! Vui lòng kiểm tra log lỗi ở trên.")
        sys.exit(res.returncode)
    print_success("Biên dịch Release APK thành công!")


def prepare_apk(version_name: str) -> Path:
    print_step("2/4", "Kiểm tra và chuẩn hóa tên file APK...")
    expected_name = f"TorrServe_{version_name}-release.apk"
    target_apk = APK_RELEASE_DIR / expected_name

    default_apk = APK_RELEASE_DIR / "app-release.apk"
    if default_apk.exists():
        shutil.copy2(default_apk, target_apk)
        print_success(f"Đã sao chép sang: {expected_name}")
    elif not target_apk.exists():
        # Tìm bất kỳ file apk release nào trong thư mục
        apks = list(APK_RELEASE_DIR.glob("*.apk"))
        if not apks:
            print_error(f"Không tìm thấy file APK trong thư mục {APK_RELEASE_DIR}")
            sys.exit(1)
        shutil.copy2(apks[0], target_apk)
        print_success(f"Đã sao chép {apks[0].name} sang {expected_name}")

    size_mb = target_apk.stat().st_size / (1024 * 1024)
    print(f"   File: {target_apk.name} ({size_mb:.2f} MB)")
    return target_apk


def git_tag_and_push(tag: str, branch: str):
    print_step("3/4", f"Kiểm tra và đẩy Git Tag '{tag}' lên nhánh '{branch}'...")

    # Nếu có thay đổi chưa commit (ví dụ release.bat, .gitignore), commit và push lên nhánh trước
    st_res = subprocess.run(["git", "status", "--porcelain"], cwd=ROOT, capture_output=True, text=True)
    if st_res.stdout.strip():
        print("   Phát hiện thay đổi trong repository, đang commit trước khi gắn tag...")
        subprocess.run(["git", "add", "."], cwd=ROOT)
        subprocess.run(["git", "commit", "-m", f"chore: release {tag}"], cwd=ROOT)
        subprocess.run(["git", "push", "origin", branch], cwd=ROOT)
        print_success(f"Đã push commit mới lên nhánh '{branch}'")

    # Kiểm tra tag đã tồn tại cục bộ chưa
    res = subprocess.run(["git", "tag", "-l", tag], cwd=ROOT, capture_output=True, text=True)
    if not res.stdout.strip():
        subprocess.run(["git", "tag", "-a", tag, "-m", f"Release {tag}"], cwd=ROOT, check=True)
        print_success(f"Đã tạo git tag cục bộ: {tag}")
    else:
        print(f"   Git tag '{tag}' đã tồn tại cục bộ.")

    # Đẩy tag lên origin
    print(f"   Đang đẩy tag lên origin...")
    res = subprocess.run(["git", "push", "origin", tag], cwd=ROOT, capture_output=True, text=True)
    if res.returncode == 0:
        print_success(f"Đã đẩy git tag '{tag}' lên origin thành công!")
    else:
        if "already exists" in res.stderr:
            print(f"   Tag '{tag}' đã tồn tại trên remote origin.")
        else:
            print_warn(f"Cảnh báo khi đẩy tag: {res.stderr.strip()}")


def upload_to_github_release(token: str, owner: str, repo: str, branch: str, tag: str, title: str, notes: str, apk_path: Path, prerelease=False):
    print_step("4/4", f"Tạo GitHub Release và tải lên file APK ({owner}/{repo})...")

    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "User-Agent": "TorrServe-Release-Tool",
        "X-GitHub-Api-Version": "2022-11-28"
    }

    # 1. Kiểm tra xem release cho tag này đã tồn tại chưa
    get_url = f"https://api.github.com/repos/{owner}/{repo}/releases/tags/{tag}"
    req = urllib.request.Request(get_url, headers=headers, method="GET")
    release_data = None
    try:
        with urllib.request.urlopen(req) as resp:
            if resp.status == 200:
                release_data = json.loads(resp.read().decode("utf-8"))
                print(f"   Đã tìm thấy Release có sẵn cho tag '{tag}'.")
    except urllib.error.HTTPError as e:
        if e.code != 404:
            print_warn(f"Không thể kiểm tra release: {e}")

    # 2. Nếu chưa có, tạo release mới
    if not release_data:
        create_url = f"https://api.github.com/repos/{owner}/{repo}/releases"
        payload = json.dumps({
            "tag_name": tag,
            "target_commitish": branch,
            "name": title,
            "body": notes,
            "draft": False,
            "prerelease": prerelease
        }).encode("utf-8")
        req = urllib.request.Request(create_url, data=payload, headers={**headers, "Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(req) as resp:
                release_data = json.loads(resp.read().decode("utf-8"))
                print_success(f"Đã tạo mới GitHub Release: {title}")
        except urllib.error.HTTPError as e:
            err_body = e.read().decode("utf-8", errors="replace")
            print_error(f"Lỗi tạo release trên GitHub: {e.code} - {err_body}")
            sys.exit(1)

    release_id = release_data["id"]
    html_url = release_data.get("html_url", f"https://github.com/{owner}/{repo}/releases/tag/{tag}")
    upload_url_template = release_data.get("upload_url", "")
    upload_base = upload_url_template.split("{")[0]

    # 3. Kiểm tra xem file apk đã tồn tại trong release chưa, nếu có thì xóa asset cũ đi
    assets = release_data.get("assets", [])
    for asset in assets:
        if asset.get("name") == apk_path.name:
            del_url = f"https://api.github.com/repos/{owner}/{repo}/releases/assets/{asset['id']}"
            del_req = urllib.request.Request(del_url, headers=headers, method="DELETE")
            try:
                with urllib.request.urlopen(del_req) as del_resp:
                    print(f"   Đã xóa file cũ trùng tên trên release: {apk_path.name}")
            except Exception as e:
                print_warn(f"Không thể xóa file cũ: {e}")

    # 4. Tải file APK lên release asset
    upload_url = f"{upload_base}?name={urllib.parse.quote(apk_path.name)}"
    file_size = apk_path.stat().st_size
    print(f"   Đang tải lên {apk_path.name} ({file_size / (1024*1024):.2f} MB)...")

    with open(apk_path, "rb") as apk_file:
        apk_bytes = apk_file.read()

    upload_headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/vnd.android.package-archive",
        "Content-Length": str(file_size),
        "User-Agent": "TorrServe-Release-Tool",
        "X-GitHub-Api-Version": "2022-11-28"
    }

    upload_req = urllib.request.Request(upload_url, data=apk_bytes, headers=upload_headers, method="POST")
    try:
        with urllib.request.urlopen(upload_req) as up_resp:
            asset_data = json.loads(up_resp.read().decode("utf-8"))
            download_url = asset_data.get("browser_download_url", "")
            print_success("Tải file APK lên GitHub Release thành công!")
            print(f"\n=======================================================")
            print(f"  \033[1;32mPHÁT HÀNH THÀNH CÔNG:\033[0m")
            print(f"  Release URL : {html_url}")
            print(f"  Tải APK     : {download_url}")
            print(f"=======================================================\n")
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        print_error(f"Lỗi tải file APK lên release: {e.code} - {err_body}")
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description="Tự động build và push Release APK lên GitHub")
    parser.add_argument("--skip-build", action="store_true", help="Bỏ qua bước biên dịch Gradle")
    parser.add_argument("--title", type=str, default="", help="Tiêu đề Release")
    parser.add_argument("--notes", type=str, default="", help="Nội dung ghi chú Release")
    parser.add_argument("--tag", type=str, default="", help="Tên tag tùy chọn (mặc định lấy versionName)")
    parser.add_argument("--prerelease", action="store_true", help="Đánh dấu bản pre-release")
    args = parser.parse_args()

    version_name, version_code = read_app_version()
    tag = args.tag or version_name
    title = args.title or f"TorrServe {version_name}"
    branch, owner, repo = get_git_info()

    print("=" * 60)
    print(f"  TORRSERVE AUTO RELEASE TOOL")
    print(f"  Ứng dụng  : {repo} ({owner})")
    print(f"  Phiên bản : {version_name} (Code: {version_code})")
    print(f"  Git Tag   : {tag}")
    print(f"  Nhánh     : {branch}")
    print("=" * 60)

    token = get_token()
    if not token:
        print_error("Không tìm thấy GITHUB_TOKEN!")
        print("Vui lòng cấu hình 'GITHUB_TOKEN=ghp_...' trong file local.properties hoặc biến môi trường.")
        sys.exit(1)

    # 1. Build APK
    if not args.skip_build:
        build_release_apk()
    else:
        print_step("1/4", "Bỏ qua bước build APK (--skip-build)")

    # 2. Chuẩn bị APK
    apk_path = prepare_apk(version_name)

    # 3. Tạo Git tag và đẩy lên git remote
    git_tag_and_push(tag, branch)

    # 4. Ghi chú phát hành
    notes = args.notes
    if not notes:
        recent_log = get_recent_commits(5)
        notes = f"## TorrServe {version_name}\n\n### Thay đổi:\n{recent_log}\n"

    # 5. Đẩy Release lên GitHub
    upload_to_github_release(
        token=token,
        owner=owner,
        repo=repo,
        branch=branch,
        tag=tag,
        title=title,
        notes=notes,
        apk_path=apk_path,
        prerelease=args.prerelease
    )


if __name__ == "__main__":
    main()
