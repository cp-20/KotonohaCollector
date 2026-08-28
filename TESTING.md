# テスト戦略

原則は「Androidを必要としない判定を、端末テストへ持ち込まない」です。日常の変更ではホスト単体テストを実行し、IME・InputConnection・Mozc JNIという代替できない境界だけを小さなPixelスモークテストで確認します。

## 1. ホスト単体テスト（既定・43件）

エミュレータ不要のJUnitです。パッケージ依存方向、Composition状態遷移、部分確定／全確定の不変条件、Editor呼び出し回数・確定拒否・suffix fallback、長文と分割位置、訂正系列、Undo位置アンカー、候補状態遷移、ローマ字変換、濁点／小書き、Unicode削除境界、語削除判定、削除／カーソルリピート速度、全角空白、角丸上限、フォールバック変換、プロセス内クリップボード履歴、プライバシー判定、パッケージID、Unicode絵文字カタログ解析、schema v3のキーと型を対象にします。

```bash
./gradlew testDebugUnitTest
```

この層へ置く基準:

- 入出力を通常のKotlin値で表現できる
- Androidのライフサイクル、描画、JNI、実InputConnectionを必要としない
- 不具合の再現条件を純粋な関数や小さな状態クラスへ抽出できる

Robolectricは現在使いません。Android APIを模倣する層を増やすより、判定ロジックをAndroid Viewから分離して通常のJUnitで実行する方が小さく速いためです。

## 2. Pixelスモーク（既定・必要最小限）

次の9シナリオだけを、1080×2424・density 420のAPI 36 Pixel AVDでADB実行します。

- フリック入力→未確定文字→1文字削除が実InputConnectionで同期する
- Mozc JNIが初期化され、変換キーで候補が切り替わる
- 部分候補「大学」の確定後も、未消費の読み「きた」が未確定のまま残る
- Enterによる全確定後はcomposing spanが消え、未確定末尾が複製されない
- 候補バーで「今日」を確定後、「あした」が古い読みを使わず「明日」へ変換される
- 削除キー900ms長押しが実タッチ経路で加速する
- かな面の空白がU+3000になる
- 10回の高速タップに欠落・重複がない
- 実フリックと訂正系列を暗号化保存・JSONL出力し、schema v3の全メタデータを検証する

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell wm size 1080x2424
adb shell wm density 420
ADB_BIN=/path/to/adb tools/run_pixel_ime_tests.sh smoke
```

引数を省略した場合も `smoke` です。エミュレータは `-no-window` で起動でき、テストスクリプト自体は画面を開きません。

## 3. GitHub ActionsエミュレータCI

`.github/workflows/android-ime.yml` はAndroid関連のpush／pull requestで43件のホストテスト後にAPI 36・x86_64 AVDを起動し、Pixelスモークを実行します。毎日03:17 JSTのscheduled runでは `all`、手動実行では `smoke`／`all`を選択できます。AVDへdebug APKを新規インストールしてIMEを有効化するため、ローカル端末の状態には依存しません。失敗時はlogcat、`dumpsys input_method`、UI XMLをartifactとして保存します。

## 4. Pixel全回帰（リリース前・定期）

選択範囲削除、Undo、カーソル移動、語削除、候補自動確定、句読点、複合絵文字などを含む従来の全回帰です。純粋ロジックと重なるケースも、最終リリースのシステム統合確認としてだけ残します。

```bash
ADB_BIN=/path/to/adb tools/run_pixel_ime_tests.sh all
```

絞り込みグループは `telemetry`、`stale_reading`、`partial_conversion`、`v012`、`delete_gestures`、`cursor_gestures`、`selection_delete` です。`cursor_gestures` は左右カーソルキーを上下へフリックしたまま保持して選択方向へ複数回移動すること、上下左右の文書端へ到達しても編集欄からフォーカスが出ないこと、空文字・折り返し長文・スペーススワイプの境界を実タッチ経路で検証します。

## 境界ごとの役割

| 対象 | ホストJUnit | Pixelスモーク | Pixel全回帰 |
|---|---:|---:|---:|
| 候補循環・自動確定の状態 | 必須 | 代表1経路 | リリース前 |
| ローマ字・かな修飾 | 必須 | フリック代表経路 | リリース前 |
| Unicode／語削除境界 | 必須 | 1文字＋長押し | リリース前 |
| 空白・削除速度・角丸ポリシー | 必須 | 空白＋実長押し | 任意 |
| Clipboard履歴・Privacy・Emoji解析 | 必須 | 不要 | UI変更時のみ |
| InputMethodService／InputConnection | 不可 | 必須 | リリース前 |
| MozcネイティブJNI／プロファイル | 不可 | 必須 | リリース前 |
| 暗号化EventStore／JSONLエクスポート | スキーマのみ | 必須 | リリース前 |
| 実座標のフリック／長押し | 不可 | 代表経路 | リリース前 |
| 見た目・アニメーション | 不可 | 不要 | スクリーンショット／手動確認 |

これにより通常の変更は43件のホストテストで素早く判定し、Android関連変更では続けてPixelスモークを実行します。Android起動が必要なのはIMEフレームワーク、Mozc JNI、Keystore／SQLite／JSONL境界へ触れた変更とリリース候補だけです。「つ → っ → づ」の循環順と全角句読点の未確定方針は純粋Kotlinの単体テストでも確認するため、専用のエミュレータケースを増やしていません。
