#!/usr/bin/env bash
set -euo pipefail
INPUT="${1:-}"
[[ -n "$INPUT" && -e "$INPUT" ]] || { echo "usage: $0 path/to/dist-or.apk" >&2; exit 2; }

python3 - "$INPUT" <<'PY'
import hashlib
import io
import json
import pathlib
import re
import sys
import tarfile
import zipfile

source = pathlib.Path(sys.argv[1])
if source.is_dir():
    rootfs_path = source / "ubuntu-arm64-rootfs.tar.gz"
    manifest_path = source / "runtime-manifest.json"
    if not rootfs_path.is_file() or not manifest_path.is_file():
        raise SystemExit("rootfs runtime payload is incomplete")
    if (source / "minisd-arm64-v8a").exists():
        raise SystemExit("obsolete minisd payload is still present")
    rootfs = rootfs_path.read_bytes()
    manifest_raw = manifest_path.read_bytes()
else:
    with zipfile.ZipFile(source) as archive:
        names = set(archive.namelist())
        if "lib/arm64-v8a/libminisd.so" in names:
            raise SystemExit("obsolete libminisd.so is still packaged")
        try:
            rootfs = archive.read("assets/minis-runtime/ubuntu-arm64-rootfs.tar.gz")
            manifest_raw = archive.read("assets/minis-runtime/runtime-manifest.json")
        except KeyError as error:
            raise SystemExit(f"rootfs runtime payload is missing: {error.args[0]}") from None

if not rootfs.startswith(b"\x1f\x8b"):
    raise SystemExit("packaged rootfs is not gzip data")

required_rootfs = {
    "etc/os-release", "etc/passwd", "etc/group", "etc/minis/rootfs.json",
    "workspace", "memory", "skills", "shared", "proc", "sys", "dev", "tmp", "run", "var/minis",
}
required_real_directories = {
    "etc", "etc/minis", "workspace", "memory", "skills", "shared", "proc", "sys", "dev", "tmp", "run", "var", "var/minis",
}
optional_real_directories = {"dev/pts", "dev/shm", "mnt", "home", "home/minis", "root"}
required_regular_files = {"etc/passwd", "etc/group", "etc/minis/rootfs.json"}
allowed_absolute_links = {
    "etc/alternatives/awk": "/usr/bin/mawk",
    "etc/alternatives/nawk": "/usr/bin/mawk",
    "etc/alternatives/pager": "/bin/more",
    "etc/alternatives/rmt": "/usr/sbin/rmt-tar",
    "etc/alternatives/which": "/usr/bin/which.debianutils",
    "etc/rmt": "/usr/sbin/rmt",
    "usr/bin/awk": "/etc/alternatives/awk",
    "usr/bin/nawk": "/etc/alternatives/nawk",
    "usr/bin/pager": "/etc/alternatives/pager",
    "usr/bin/which": "/etc/alternatives/which",
    "usr/sbin/rmt": "/etc/alternatives/rmt",
    "etc/systemd/system/multi-user.target.wants/e2scrub_reap.service": "/lib/systemd/system/e2scrub_reap.service",
    "etc/systemd/system/timers.target.wants/apt-daily-upgrade.timer": "/lib/systemd/system/apt-daily-upgrade.timer",
    "etc/systemd/system/timers.target.wants/apt-daily.timer": "/lib/systemd/system/apt-daily.timer",
    "etc/systemd/system/timers.target.wants/dpkg-db-backup.timer": "/lib/systemd/system/dpkg-db-backup.timer",
    "etc/systemd/system/timers.target.wants/e2scrub_all.timer": "/lib/systemd/system/e2scrub_all.timer",
    "etc/systemd/system/timers.target.wants/fstrim.timer": "/lib/systemd/system/fstrim.timer",
    "etc/systemd/system/timers.target.wants/motd-news.timer": "/lib/systemd/system/motd-news.timer",
    "var/run": "/run", "var/lock": "/run/lock",
    "var/minis/workspace": "/workspace", "var/minis/attachments": "/workspace/attachments",
    "var/minis/offloads": "/workspace/offloads", "var/minis/browser": "/workspace/browser",
    "var/minis/memory": "/memory", "var/minis/skills": "/skills", "var/minis/shared": "/shared",
}

def normalize_path(raw):
    value = raw.strip()
    while value.startswith("./"):
        value = value[2:]
    value = value.rstrip("/")
    if not value or value == ".":
        return None
    parts = value.split("/")
    if value.startswith("/") or any(not part or part in {".", ".."} or any(ord(c) < 32 for c in part) for part in parts):
        raise SystemExit(f"packaged rootfs contains an unsafe path: {raw!r}")
    return "/".join(parts)

def normalize_link(entry, target):
    if not target or "\x00" in target:
        raise SystemExit(f"packaged rootfs contains an unsafe link: {entry} -> {target}")
    if target.startswith("/"):
        if allowed_absolute_links.get(entry) != target:
            raise SystemExit(f"packaged rootfs contains an unsafe link: {entry} -> {target}")
        return target
    parts = entry.split("/")[:-1]
    for component in target.split("/"):
        if not component or component == ".":
            continue
        if any(ord(c) < 32 for c in component):
            raise SystemExit(f"packaged rootfs contains an unsafe link: {entry} -> {target}")
        if component == "..":
            if not parts:
                raise SystemExit(f"packaged rootfs contains an unsafe link: {entry} -> {target}")
            parts.pop()
        else:
            parts.append(component)
    if not parts:
        raise SystemExit(f"packaged rootfs contains an unsafe link: {entry} -> {target}")
    return "/".join(parts)

def reject_link_escape(path, symlinks):
    if not path:
        return
    components = path.split("/")
    visited = set()
    while True:
        replaced = False
        for index in range(1, len(components) + 1):
            prefix = "/".join(components[:index])
            target = symlinks.get(prefix)
            if target is None:
                continue
            if target.startswith("/"):
                raise SystemExit(f"packaged rootfs path resolves through an absolute link: {path}")
            if prefix in visited:
                raise SystemExit(f"packaged rootfs path contains a symlink cycle: {path}")
            visited.add(prefix)
            components = target.split("/") + components[index:]
            replaced = True
            break
        if not replaced:
            return

try:
    with tarfile.open(fileobj=io.BytesIO(rootfs), mode="r:gz") as tar:
        members = {}
        regular_names = set()
        hard_links = []
        symlink_targets = {}
        expanded_bytes = 0
        for member in tar.getmembers():
            name = normalize_path(member.name)
            if name is None:
                if not member.isdir():
                    raise SystemExit("packaged rootfs has a non-directory root entry")
                continue
            if name in members:
                raise SystemExit(f"packaged rootfs contains duplicate entry: {name}")
            if member.isdir(): kind = "dir"
            elif member.isfile():
                kind = "file"
                regular_names.add(name)
                expanded_bytes += member.size
                if expanded_bytes > 2 * 1024 * 1024 * 1024:
                    raise SystemExit("packaged rootfs expands beyond 2 GiB")
            elif member.issym():
                kind = "symlink"
                symlink_targets[name] = normalize_link(name, member.linkname)
            elif member.islnk():
                kind = "hardlink"
                hard_links.append((name, normalize_path(member.linkname)))
            else:
                raise SystemExit(f"packaged rootfs contains unsupported node: {name}")
            if name in required_real_directories and kind != "dir":
                raise SystemExit(f"packaged rootfs layout entry is not a real directory: {name}")
            if name in optional_real_directories and kind != "dir":
                raise SystemExit(f"packaged rootfs optional directory is not real: {name}")
            if name in required_regular_files and kind != "file":
                raise SystemExit(f"packaged rootfs required file is not regular: {name}")
            members[name] = member
        for name in members:
            reject_link_escape(name.rsplit("/", 1)[0] if "/" in name else "", symlink_targets)
        for target in symlink_targets.values():
            if not target.startswith("/"):
                reject_link_escape(target, symlink_targets)
        for name, target in hard_links:
            if target is None or target not in regular_names:
                raise SystemExit(f"packaged rootfs hardlink target is not a regular file: {name} -> {target}")
        missing = sorted(required_rootfs - members.keys())
        if missing:
            raise SystemExit(f"packaged rootfs is missing layout entries: {', '.join(missing)}")
        if "bin/bash" not in members and "usr/bin/bash" not in members and "bin/sh" not in members:
            raise SystemExit("packaged rootfs has no shell")
        metadata_file = tar.extractfile(members["etc/minis/rootfs.json"])
        if metadata_file is None:
            raise SystemExit("packaged rootfs metadata is not a regular file")
        rootfs_metadata = json.load(metadata_file)
except (tarfile.TarError, json.JSONDecodeError) as error:
    raise SystemExit(f"packaged rootfs archive is invalid: {error}") from None

try:
    manifest = json.loads(manifest_raw.decode("utf-8"))
except (UnicodeDecodeError, json.JSONDecodeError) as error:
    raise SystemExit(f"runtime manifest is invalid JSON: {error}") from None
if not isinstance(manifest, dict):
    raise SystemExit("runtime manifest is not an object")
forbidden = {"minisdVersion", "minisdSha256", "protocolVersion"}
present = sorted(forbidden & manifest.keys())
if present:
    raise SystemExit(f"runtime manifest contains obsolete broker fields: {', '.join(present)}")

rootfs_sha = hashlib.sha256(rootfs).hexdigest()
required = {"schemaVersion": 3, "distro": "ubuntu", "arch": "arm64", "profile": "base"}
for key, expected in required.items():
    if manifest.get(key) != expected:
        raise SystemExit(f"runtime manifest {key} must be {expected!r}")
if manifest.get("rootfsSha256") != rootfs_sha:
    raise SystemExit("runtime manifest rootfs SHA-256 mismatch")
pinned = "7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048"
if manifest.get("upstreamSha256") != pinned or rootfs_metadata.get("upstream_sha256") != pinned:
    raise SystemExit("runtime payload does not use pinned Ubuntu upstream SHA-256")
if (
    rootfs_metadata.get("distro") != "ubuntu"
    or not str(rootfs_metadata.get("version", "")).startswith("24.04")
    or rootfs_metadata.get("arch") != "arm64"
    or rootfs_metadata.get("profile") != "base"
    or not isinstance(rootfs_metadata.get("revision"), int)
    or isinstance(rootfs_metadata.get("revision"), bool)
    or rootfs_metadata["revision"] <= 0
):
    raise SystemExit("packaged rootfs metadata has unsupported identity")
if manifest.get("release") != rootfs_metadata.get("release") or not str(manifest.get("release", "")).startswith("24.04"):
    raise SystemExit("runtime manifest release does not match Ubuntu 24.04 metadata")
if manifest.get("revision") != rootfs_metadata.get("revision"):
    raise SystemExit("runtime manifest revision does not match rootfs metadata")
version = manifest.get("rootfsVersion", "")
if not re.fullmatch(r"ubuntu-24\.04-r[1-9][0-9]*-[0-9a-f]{16}", version):
    raise SystemExit("runtime manifest has invalid rootfsVersion")
if not version.endswith(rootfs_sha[:16]):
    raise SystemExit("runtime rootfsVersion does not match rootfs digest")
revision = int(version.split("-r", 1)[1].split("-", 1)[0])
if revision != manifest.get("revision"):
    raise SystemExit("runtime rootfsVersion revision does not match manifest")
if not isinstance(manifest.get("provisionRevision"), int) or isinstance(manifest.get("provisionRevision"), bool) or manifest["provisionRevision"] <= 0:
    raise SystemExit("runtime manifest provisionRevision must be positive")
if manifest.get("requiredCommands") != ["python3", "git", "curl"]:
    raise SystemExit("runtime manifest requiredCommands mismatch")

print(f"rootfs runtime payload verified: rootfs={rootfs_sha}")
PY
