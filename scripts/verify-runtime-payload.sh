#!/usr/bin/env bash
set -euo pipefail

INPUT="${1:-}"
if [[ -z "$INPUT" || ! -e "$INPUT" ]]; then
  echo "usage: $0 path/to/dist-or.apk" >&2
  exit 2
fi

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
    names = {
        "minisd": "minisd-arm64-v8a",
        "rootfs": "ubuntu-arm64-rootfs.tar.gz",
        "manifest": "runtime-manifest.json",
    }
    def read(name):
        path = source / names[name]
        if not path.is_file():
            raise SystemExit(f"runtime payload is missing: {path}")
        return path.read_bytes()
else:
    names = {
        "minisd": "lib/arm64-v8a/libminisd.so",
        "rootfs": "assets/minis-runtime/ubuntu-arm64-rootfs.tar.gz",
        "manifest": "assets/minis-runtime/runtime-manifest.json",
    }
    archive = zipfile.ZipFile(source)
    def read(name):
        path = names[name]
        try:
            return archive.read(path)
        except KeyError:
            raise SystemExit(f"runtime payload is missing: {path}") from None

minisd = read("minisd")
rootfs = read("rootfs")
manifest_raw = read("manifest")
if not minisd.startswith(b"\x7fELF"):
    raise SystemExit("packaged minisd is not an ELF binary")
if len(minisd) < 20 or minisd[4] != 2 or minisd[5] != 1:
    raise SystemExit("packaged minisd is not little-endian ELF64")
if int.from_bytes(minisd[16:18], "little") != 3:
    raise SystemExit("packaged minisd is not PIE/ET_DYN")
if int.from_bytes(minisd[18:20], "little") != 183:
    raise SystemExit("packaged minisd is not AArch64")
if not rootfs.startswith(b"\x1f\x8b"):
    raise SystemExit("packaged rootfs is not gzip data")
try:
    with tarfile.open(fileobj=io.BytesIO(rootfs), mode="r:gz") as tar:
        required_rootfs = {
            "etc/os-release",
            "etc/passwd",
            "etc/group",
            "etc/minis/rootfs.json",
            "workspace",
            "memory",
            "skills",
            "shared",
            "proc",
            "sys",
            "dev",
            "tmp",
            "run",
            "var/minis",
        }
        required_real_directories = {
            "etc",
            "etc/minis",
            "workspace",
            "memory",
            "skills",
            "shared",
            "proc",
            "sys",
            "dev",
            "tmp",
            "run",
            "var",
            "var/minis",
        }
        optional_real_directories = {
            "dev/pts",
            "dev/shm",
            "mnt",
            "home",
            "home/minis",
            "root",
        }
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
            "var/run": "/run",
            "var/lock": "/run/lock",
            "var/minis/workspace": "/workspace",
            "var/minis/attachments": "/workspace/attachments",
            "var/minis/offloads": "/workspace/offloads",
            "var/minis/browser": "/workspace/browser",
            "var/minis/memory": "/memory",
            "var/minis/skills": "/skills",
            "var/minis/shared": "/shared",
        }

        def normalize_path(raw):
            value = raw.strip()
            while value.startswith("./"):
                value = value[2:]
            value = value.rstrip("/")
            if not value or value == ".":
                return None
            parts = value.split("/")
            if value.startswith("/") or any(
                not part or part in {".", ".."} or any(ord(char) < 32 for char in part)
                for part in parts
            ):
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
                if any(ord(char) < 32 for char in component):
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
            if member.isdir():
                kind = "dir"
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
            parent = name.rsplit("/", 1)[0] if "/" in name else ""
            reject_link_escape(parent, symlink_targets)
        for target in symlink_targets.values():
            if not target.startswith("/"):
                reject_link_escape(target, symlink_targets)
        for name, target in hard_links:
            if target is None or target not in regular_names:
                raise SystemExit(f"packaged rootfs hardlink target is not a regular file: {name} -> {target}")

        missing_rootfs = sorted(required_rootfs - members.keys())
        if missing_rootfs:
            raise SystemExit(f"packaged rootfs is missing layout entries: {', '.join(missing_rootfs)}")
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

minisd_sha = hashlib.sha256(minisd).hexdigest()
rootfs_sha = hashlib.sha256(rootfs).hexdigest()
required = {
    "schemaVersion": 2,
    "protocolVersion": 1,
    "layoutVersion": 2,
    "abi": "arm64-v8a",
    "rootfsProfile": "base",
}
for key, expected in required.items():
    if manifest.get(key) != expected:
        raise SystemExit(f"runtime manifest {key} must be {expected!r}")
if manifest.get("minisdSha256") != minisd_sha:
    raise SystemExit("runtime manifest minisd SHA-256 mismatch")
if manifest.get("rootfsSha256") != rootfs_sha:
    raise SystemExit("runtime manifest rootfs SHA-256 mismatch")
pinned_upstream = "7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048"
if manifest.get("rootfsUpstreamSha256") != pinned_upstream:
    raise SystemExit("runtime manifest does not use the pinned Ubuntu upstream SHA-256")
if rootfs_metadata.get("upstream_sha256") != pinned_upstream:
    raise SystemExit("packaged rootfs metadata does not use the pinned Ubuntu upstream SHA-256")
if (
    rootfs_metadata.get("distro") != "ubuntu"
    or not str(rootfs_metadata.get("version", "")).startswith("24.04")
    or rootfs_metadata.get("arch") != "arm64"
    or rootfs_metadata.get("profile") != "base"
    or not isinstance(rootfs_metadata.get("revision"), int)
    or isinstance(rootfs_metadata.get("revision"), bool)
    or rootfs_metadata["revision"] <= 0
):
    raise SystemExit("packaged rootfs metadata has an unsupported identity")
version = manifest.get("rootfsVersion", "")
if not re.fullmatch(r"ubuntu-24\.04-r[1-9][0-9]*-[0-9a-f]{16}", version):
    raise SystemExit("runtime manifest has invalid rootfsVersion")
if not version.endswith(rootfs_sha[:16]):
    raise SystemExit("runtime rootfsVersion does not match the rootfs digest")
rootfs_revision = int(version.split("-r", 1)[1].split("-", 1)[0])
if rootfs_metadata.get("revision") != rootfs_revision:
    raise SystemExit("runtime rootfs revision does not match rootfs metadata")
if not str(manifest.get("rootfsRelease", "")).startswith("24.04"):
    raise SystemExit("runtime manifest has unsupported rootfs release")
if manifest.get("rootfsRelease") != rootfs_metadata.get("release"):
    raise SystemExit("runtime manifest release does not match rootfs metadata")
if not isinstance(manifest.get("provisionRevision"), int) or manifest["provisionRevision"] <= 0:
    raise SystemExit("runtime manifest provisionRevision must be positive")
if manifest.get("requiredCommands") != ["python3", "git", "curl"]:
    raise SystemExit("runtime manifest requiredCommands mismatch")
if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", manifest.get("minisdVersion", "")):
    raise SystemExit("runtime manifest has invalid minisdVersion")

print(f"runtime payload verified: minisd={minisd_sha} rootfs={rootfs_sha}")
PY
