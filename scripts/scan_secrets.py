"""Scans a branch's changes for secrets and account-specific values, without ever printing them.

Usage, from the project root:

    python -I scripts/scan_secrets.py [base]

Looks at every line added since `base` (default `main`): committed, uncommitted and untracked
files alike, except `.idea/`. Reports each match as `file:line  kind`. The matched text is
never shown, so the output is safe to paste anywhere. Exits 1 if anything matched, else 0.

Besides fixed patterns (API keys, emails, AWS ids), it searches for every value in the
git-ignored `local.properties` and `backend/cdk.context.json`. Those files hold the real
account-specific values; this script reads them so no person or agent has to open them.
"""

import json
import re
import subprocess
import sys
from pathlib import Path

PATTERNS = {
    "Google API key": re.compile(r"AIza[0-9A-Za-z_-]{20,}"),
    "AWS access key": re.compile(r"\b(AKIA|ASIA)[0-9A-Z]{16}\b"),
    "email address": re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"),
    "Cognito domain": re.compile(r"[a-z0-9-]+\.auth\.[a-z0-9-]+\.amazoncognito\.com"),
    "API Gateway URL": re.compile(r"[a-z0-9]+\.execute-api\.[a-z0-9-]+\.amazonaws\.com"),
    "Cognito pool id": re.compile(r"\b[a-z]{2}-[a-z]+-\d_[A-Za-z0-9]{6,}\b"),
    "12-digit number (AWS account id?)": re.compile(r"(?<!\d)\d{12}(?!\d)"),
}

# Addresses that are fine to commit: examples, and git's own attribution lines.
ALLOWED_EMAILS = {"noreply@anthropic.com", "alerts@example.com"}
ALLOWED_EMAIL_DOMAINS = ("example.com", "example.test", "example.org")

# Files whose 12-digit numbers are known not to be account ids.
NUMBER_OK_FILES = re.compile(r"^app/schemas/.*\.json$")  # Room's identityHash


def git(*args: str) -> str:
    return subprocess.run(["git", *args], capture_output=True, text=True, encoding="utf-8", check=True).stdout


def local_values() -> dict[str, str]:
    """Values from the git-ignored files, by name. Never printed."""
    values: dict[str, str] = {}
    props = Path("local.properties")
    if props.exists():
        for line in props.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = (part.strip() for part in line.split("=", 1))
                # sdk.dir is a path on this PC, not a secret.
                if key != "sdk.dir" and len(value) >= 6:
                    values[f"local.properties {key}"] = value
    context = Path("backend/cdk.context.json")
    if context.exists():
        for key, value in json.loads(context.read_text(encoding="utf-8")).items():
            if isinstance(value, str) and len(value) >= 6:
                values[f"cdk.context.json {key}"] = value
                # An email's name part alone can still identify someone.
                if "@" in value and len(value.split("@")[0]) >= 4:
                    values[f"cdk.context.json {key} (name part)"] = value.split("@")[0]
    return values


def added_lines(base: str):
    """(file, line number, text) for every line added since base, including uncommitted work."""
    diff = git("diff", "--unified=0", "--no-color", base)
    path, number = None, 0
    for line in diff.splitlines():
        if line.startswith("+++ "):
            path = line[6:] if line.startswith("+++ b/") else None
        elif line.startswith("@@"):
            number = int(re.search(r"\+(\d+)", line).group(1))
        elif line.startswith("+") and path:
            yield path, number, line[1:]
            number += 1
    for path in git("ls-files", "--others", "--exclude-standard").splitlines():
        if path.startswith(".idea/"):
            continue
        try:
            text = Path(path).read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue  # binary or unreadable: nothing to scan as text
        for number, line in enumerate(text.splitlines(), start=1):
            yield path, number, line


def is_allowed(kind: str, match: str, path: str) -> bool:
    if kind == "email address":
        return match.lower() in ALLOWED_EMAILS or match.lower().endswith(ALLOWED_EMAIL_DOMAINS)
    if kind.startswith("12-digit"):
        return bool(NUMBER_OK_FILES.match(path))
    return False


def main() -> int:
    base = sys.argv[1] if len(sys.argv) > 1 else "main"
    secrets = local_values()
    findings = []
    scanned = 0
    for path, number, text in added_lines(base):
        scanned += 1
        for kind, pattern in PATTERNS.items():
            for match in (m.group(0) for m in pattern.finditer(text)):
                if not is_allowed(kind, match, path):
                    findings.append((path, number, kind))
        for name, value in secrets.items():
            if value in text:
                findings.append((path, number, f"value of {name}"))

    print(f"Scanned {scanned} added lines since {base}, checking {len(PATTERNS)} patterns "
          f"and {len(secrets)} local values.")
    for path, number, kind in sorted(set(findings)):
        print(f"  {path}:{number}  {kind}")
    print("CLEAN" if not findings else f"{len(set(findings))} finding(s); values not shown.")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
