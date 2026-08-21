# ことのは収集IME

普段の日本語入力から、変換・確定・修正の履歴を端末内に蓄積する個人向けIMEです。将来の変換精度や誤字訂正の改善に使えるデータを、実際の入力体験を崩さず集めることを目的にしています。

開発中の試作版です。GoogleおよびGboardとは関係のないクリーンルーム実装です。

## Android版

- 日本語12キーフリック、半角英字・記号QWERTY、絵文字、クリップボードに対応
- 公式OSS版Mozcによる、完全オフラインの予測・かな漢字変換と端末内学習
- 読み、表示候補、選択順位、確定、未確定編集、削除、訂正系列、フリック操作をイベントとして記録
- 収集データをJSONL（schema v3）でエクスポート
- 収集は初期状態でOFF。パスワード等の入力欄は自動除外し、保存内容はAndroid Keystoreの鍵で暗号化
- `INTERNET` 権限なし。入力データやMozcの学習履歴は端末外へ送信しません

## Windows版

公式OSS版MozcのTSFクライアントをベースに、入力・候補・確定・削除・前後文脈を同じschema v3で収集します。データはWindows DPAPIで暗号化し、Mozcの入力処理とは別スレッドで保存します。詳細は [windows/README.md](windows/README.md) を参照してください。

## ビルド

JDK 17とAndroid SDK 34を用意し、Android Studioで開くか次を実行します。

```bash
./gradlew assembleDebug
```

APKは `app/build/outputs/apk/debug/app-debug.apk` に生成されます。Mozcのネイティブライブラリと辞書は同梱済みです。

## Androidでの使い方

1. APKをインストールしてアプリを開く
2. Android設定で「ことのは収集IME」を有効にし、使用するIMEとして選択する
3. アプリで「入力イベントを収集する」をONにする
4. 必要に応じて保存データをJSONLとして書き出す

## テスト

エミュレータ不要の単体テストと、Pixelエミュレータを使うIME境界テストがあります。ケースと実行方法は [TESTING.md](TESTING.md) を参照してください。

## Mozcと第三者ライセンス

Mozcは公式リポジトリのコミット `851c3fe33060d2a6090363e4d7ec44fafde2c03d` を使用しています。OSS版の辞書はGoogle日本語入力やGboardの辞書とは異なるため、候補や順位は一致しません。

詳細は [NOTICE-MOZC.txt](NOTICE-MOZC.txt) と [NOTICE-MATERIAL-ICONS.txt](NOTICE-MATERIAL-ICONS.txt) を参照してください。
