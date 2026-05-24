# Default ProGuard rules for HealthConnectReader.
# isMinifyEnabled = false なので現状は無効化されているが、
# 将来 minify を on にした時に Health Connect 関連 class を保護するための placeholder。

-keep class androidx.health.connect.client.** { *; }
