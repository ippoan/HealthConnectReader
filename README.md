# HealthConnectReader

Android アプリ。Health Connect 経由で Life Fitness 等のトレッドミルが書き込んだ
運動データ (`ExerciseSession` / `Distance` / `Speed`) を読み出して画面と Logcat に
出力する自分用テストツール。

## 何ができるか

- ボタン 1 つで「今日 0 時から現在まで」の Health Connect レコードを読み出す
- 3 種類のレコードに対応:
  - `ExerciseSessionRecord` — 運動セッション (時刻 / 種目 / タイトル)
  - `DistanceRecord` — 距離 (km)
  - `SpeedRecord` — 速度 (各 sample の時刻と km/h)
- 読み出した各レコードの `dataOrigin.packageName` を併記。これを見て Life Fitness の
  正確な package を確定し、後段でフィルタするのに使う。

## 必要環境

- Android 9 (API 28) 以上
- `compileSdk` / `targetSdk` は 36 (`connect-client:1.1.0-rc02` が API 36 を要求するため)
- Health Connect (Android 14+ は OS 同梱、それ以前は Play ストアからインストール)
- 端末側で Life Fitness アプリと Health Connect の連携が ON

## ビルド

ローカルビルド (debug):

```sh
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

リリースビルドは GitHub Actions (`.github/workflows/release.yml`) で行う:

- **`main` への push**: `v<versionName>+<run>` 正式タグ → GitHub Release + `gh-pages` 配信
- **PR push**: `dev-pr<N>-<run>` prerelease タグ → GitHub Release のみ (Pages は触らない)
  - PR ごとに APK を端末で試せるよう、PR コメントに APK link を自動投稿
  - tag に `-` を含むので `releases/latest` API には出ない (安定 channel と分離)
  - fork PR は keystore secret が無いので skip (同 repo の PR のみ走る)

### 必要な GitHub Secrets

ippoan org-level secrets (全 `HCREADER_` プレフィックス、ippoan/secrets-inventory
の `PUT /mcp/secret-upload/:name` 経由で投入):

| secret 名 | 中身 |
|---|---|
| `HCREADER_RELEASE_KEYSTORE_BASE64` | 署名鍵 keystore (PKCS12) を `base64 -w0` した文字列 |
| `HCREADER_RELEASE_STORE_PASSWORD` | keystore パスワード (= key パスワードも兼用) |

PKCS12 形式は JDK の仕様で `keypass == storepass` 強制 (= `keytool -keypass` を
別値で指定しても silently 無視される) のため、secret は 1 個だけ用意し、
`build.gradle.kts` の `storePassword` / `keyPassword` 両方と `apksigner` の
`--ks-pass` / `--key-pass` 両方に同じ値を流す。

keystore 生成 (ローカルで 1 回):

```sh
PW=$(openssl rand -base64 24)
keytool -genkeypair -v \
  -keystore release.keystore \
  -storetype PKCS12 \
  -alias hcreader \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$PW" -keypass "$PW" \
  -dname "CN=HealthConnectReader, O=ippoan, C=JP"
base64 -w0 release.keystore   # この出力を HCREADER_RELEASE_KEYSTORE_BASE64 に
echo "$PW"                    # この値を HCREADER_RELEASE_STORE_PASSWORD に
```

alias は `hcreader` で `app/build.gradle.kts` と `release.yml` の `--ks-key-alias` の
2 箇所に合わせる。

## 初回 setup の残作業

- [ ] GitHub Secrets に上記 3 つを登録
- [ ] `gh-pages` ブランチを空コミットで初期化 (無いと release.yml の Deploy step が失敗)
- [ ] Settings → Pages のソースを `gh-pages` に設定
- [ ] 実機で 1 回起動して Life Fitness の packageName を確定 → `HealthReader.kt` に
      `dataOriginFilter` を追加 (任意)

## 設計メモ

- `HealthReader` は client を DI で受け取る薄いラッパー。フィルタなしで全件読んで
  packageName を出すだけの「診断モード」が現状の実装。
- Health Connect は package を区別せず返すので、距離レコードに Life Fitness 由来と
  端末本体由来 ("Health") が混ざる。出所フィルタは packageName 確定後に実装する。
- 署名は v1+v2 のみ (v3/v4 off)、`--min-sdk-version 28` に合わせる。

## ライセンス

private (個人用ツール)。
