# CLAUDE.md

Health Connect 経由でトレッドミル運動データ (`ExerciseSession` / `Distance` /
`Speed`) を読み出す自分用 Android アプリ。

このリポジトリで Claude Code セッションを動かす時の作業ガイド。本ファイルは
[ippoan/claude-md](https://github.com/ippoan/claude-md) の `CLAUDE.md.template`
から派生 — 共通項を直すときは template を更新する。

## まず読むもの

- [`README.md`](./README.md) — アプリの目的・ビルド方法・必要な secret 一覧
- [`app/src/main/java/com/ippoan/hcreader/HealthReader.kt`](./app/src/main/java/com/ippoan/hcreader/HealthReader.kt) — 読取ロジック本体
- [`app/src/main/java/com/ippoan/hcreader/MainActivity.kt`](./app/src/main/java/com/ippoan/hcreader/MainActivity.kt) — 権限フロー / UI
- [`.github/workflows/release.yml`](./.github/workflows/release.yml) — APK ビルド / GitHub Release / Pages 配信
- Issue #1 — 初期構築の方針・確定事項一覧

## ブランチ運用 / Worktree

- 作業は **`main` から切った短命ブランチ**上で行う。命名規則:
  - 推奨形式: `<issue-number>-<type>-<short-description>` (`type ∈ feat|fix|refactor|infra`)
  - Claude Code が自動採番する `claude/<topic>-<sha>` で実装に入った場合は、
    対応 issue を立てた上で上記形式に rename する
- **`main` に直接 push しない。** PR を開く → user が CI 結果を見て手動 merge する。
- `git push --force` / `git commit --amend` / `git rebase -i` は `claude-hooks`
  の `git-safe-push.sh` で block されている。

## ビルド / テスト / lint

PR を出す前に手元で全部 green であること:

```sh
# Debug ビルド (署名 secret 不要)
./gradlew assembleDebug

# lint
./gradlew lint
```

Release ビルドは secret (`RELEASE_KEYSTORE_BASE64` 等) が必要なので CI に任せる。
ローカルで release を組みたい場合は `app/release.keystore` と 2 つの環境変数を
手元に揃える必要がある (README 参照)。

## GitHub 自動化

### Auto-merge

このリポジトリは **workflow 側で auto-merge を enable する** 設計
(`release.yml` 内に `ippoan/ci-workflows/.github/workflows/auto-merge.yml@main`
を呼ぶ `auto-merge` job を持つ)。

- `build` job (APK build + 署名 + Pages deploy) が green になってから
  `gh pr merge --auto --squash` が queue される
- branch protection の required status check が無い repo でも、`needs: build`
  の DAG 制約により build 完走後にしか merge 試行されないので意図しない
  早期 merge は起こらない
- TAG_RELEASE_PAT を org-level に設定すれば、PR merge 後の `push: main`
  event が PAT actor で発火し main の release.yml run (stable APK 更新) が
  自動で chain する。未設定だと github.token actor になり main run が
  起動しないので、その場合は手動 workflow_dispatch する

`mcp__github__enable_pr_auto_merge` を Claude が **直接** 叩くのは引き続き
**user が明示指示した時のみ** (= reflex 違反防止)。通常は workflow 側に任せる。

### PR description / commit message のキーワード

- ❌ 使用禁止: `Closes #N` / `Fixes #N` / `Resolves #N` — auto-close されると release
  tag 時の close 確認 UI と整合しない
- ✅ 使用推奨: `Refs #N` / `Related to #N` / `Part of #N` — GitHub の Development
  セクションには紐付くが auto-close されない

### PR 作成後の CI 監視

PR を作成したら同じ turn で `mcp__github__subscribe_pr_activity` を呼んで CI を
watch する。`sleep` / `gh run watch` / 手動 polling は禁止 — webhook で起こされる
前提でそのターンは終了する。

### Release

`release.yml` が `main` への push (`app/**` or workflow 自身に変更があった時) で
APK を組んで GitHub Release + Pages を更新する。タグ名は
`v<versionName>+<run_number>` (例 `v0.1.0+42`)。

`versionName` は `app/build.gradle.kts` で管理。バージョンアップは PR で更新する。

## 実装上の注意

### Health Connect 読取

- `ReadRecordsRequest` は record class と time range を渡すだけで返ってくる。フィルタ
  なしだと「すべてのアプリ由来」のレコードが混ざる (Life Fitness 以外に端末本体由来の
  細切れレコードが入る)。
- packageName 確定後に `dataOriginFilter = setOf(DataOrigin("..."))` を足してフィルタ
  する。それまでは診断モードのまま全件出す。

### 権限

- `permissions` set に追加した record は **Manifest の `uses-permission` にも対応する
  `READ_*` を追加する**。両方揃わないと grant が失敗する (現状は EXERCISE / DISTANCE /
  SPEED の 3 種で揃っている)。
- 心拍 / カロリーを足す時は `READ_HEART_RATE` / `READ_TOTAL_CALORIES_BURNED` を
  Manifest と `permissions` set の両方に同時追加する。

### 署名

- v1+v2 のみ (v3/v4 off)、`--min-sdk-version 28`
- alias は `hcreader` で固定。`app/build.gradle.kts` の `keyAlias` と
  `release.yml` の `--ks-key-alias` の 2 箇所で必ず一致させる。
- keystore (`*.keystore` / `*.jks`) は `.gitignore` で除外済み。誤って commit
  しないよう注意。

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
