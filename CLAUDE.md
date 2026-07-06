# CLAUDE.md

Health Connect 経由でトレッドミル運動データ (`ExerciseSession` / `Distance` /
`Speed`) を読み出す自分用 Android アプリ。

詳細 (アーキテクチャ・経緯・gotcha) は `HealthConnectReader-map` skill を参照。

## ビルド / テスト / lint

PR を出す前に手元で全部 green であること:

```sh
# Debug ビルド (署名 secret 不要)
./gradlew assembleDebug

# lint
./gradlew lint
```

Release ビルドは secret (`RELEASE_KEYSTORE_BASE64` 等) が必要なので CI に任せる。

## GitHub / ブランチ運用

- 作業は `main` から切った短命ブランチで行い、PR を開く → user が CI 結果を見て
  手動 merge する (直 push / force push / amend / rebase -i 禁止、詳細は下記)。
- PR / commit に `Closes #N` / `Fixes #N` / `Resolves #N` を使わない → `Refs #N`。
- PR 作成後は同じ turn で `mcp__github__subscribe_pr_activity` を呼ぶ。`sleep` /
  `gh run watch` / 手動 polling は禁止。
- `mcp__github__enable_pr_auto_merge` を Claude が直接叩くのは user が明示指示した
  時のみ。

## やってはいけないこと

- **`main` に直 push しない**
- **force push / amend / rebase -i しない** — `git-safe-push.sh` が block する
- **keystore (`*.keystore` / `*.jks`) や secret (`.env`, `*.pem`, `*.key`) を
  コミットしない** — `.gitignore` が守っているが、`git add -f` で強制追加しない
- **`READ_HEART_RATE` / `READ_TOTAL_CALORIES_BURNED` 等 issue で未承認の権限を勝手に
  足さない** — Issue #1 の scope は 3 種に限定されている
- **CI を green にするためだけのテスト無効化 / skip flag を入れない** — root cause
  を直す

---

_このファイルは [`ippoan/claude-md`](https://github.com/ippoan/claude-md) の
`CLAUDE.md.template` から派生したもの。共通部分の変更は template 側に PR を出すこと。_
