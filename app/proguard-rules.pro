# Xposed / LSPosed 相关类不能混淆，且必须保持 compileOnly
-keep class de.robv.android.xposed.** { *; }
-keep class io.wxbridge.xposed.** { *; }

# SQLCipher native 绑定
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }
