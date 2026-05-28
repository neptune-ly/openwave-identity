#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BLOCKED_REGEX='AGENT_CONTEXT|Codex|OpenAI|ChatGPT|X-Astro-Key|AI[ -]generated|(^|[^[:alnum:]_])GPT([^[:alnum:]_]|$)'

matches=0
found_files=0
while IFS= read -r file; do
  found_files=1
  if rg -n -i --pcre2 "$BLOCKED_REGEX" "$file" >/tmp/openwave-identity-public-scan.out 2>/dev/null; then
    echo "Blocked public-surface marker found in $file"
    cat /tmp/openwave-identity-public-scan.out
    matches=1
  fi
done <<EOF
$(git ls-files \
  '*.md' '*.html' '*.htm' '*.yaml' '*.yml' '*.json' '*.js' '*.ts' '*.css' '*.txt' \
  ':!:openwave-ui/package-lock.json' \
  ':!:openwave-ui/.svelte-kit/**' \
  ':!:openwave-ui/node_modules/**' \
  ':!:openwave-ui/src/assets/*.png' \
  ':!:docs/*.png')
EOF

rm -f /tmp/openwave-identity-public-scan.out

if ((found_files == 0)); then
  echo "No tracked public text files found."
  exit 0
fi

if ((matches)); then
  exit 1
fi

echo "Public surface check passed."
