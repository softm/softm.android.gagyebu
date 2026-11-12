#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd "$script_dir/.." && pwd)"
source_dir="${1:-"$project_dir/../gagyebu"}"
target_dir="$project_dir/app/src/main/assets/web"

for required in index.html manifest.webmanifest sw.js src icons; do
  if [[ ! -e "$source_dir/$required" ]]; then
    echo "필수 웹 자산이 없습니다: $source_dir/$required" >&2
    exit 1
  fi
done

mkdir -p "$target_dir/src" "$target_dir/icons"
rsync -a --exclude='.DS_Store' --exclude='app.js' --exclude='styles.css' --exclude='cloud.js' --exclude='firebase-config.js' "$source_dir/src/" "$target_dir/src/"
rsync -a --exclude='.DS_Store' "$source_dir/icons/" "$target_dir/icons/"
cp "$source_dir/index.html" "$source_dir/manifest.webmanifest" "$source_dir/sw.js" "$target_dir/"

echo "공통 웹 자산을 갱신했습니다. Android 브리지와 Cloudflare 전용 app.js, styles.css, cloud.js를 보존하고 Firebase 설정 복사를 차단했습니다."
echo "Android 전용 파일의 upstream 변경은 수동 병합한 뒤 전체 테스트를 실행하세요."

