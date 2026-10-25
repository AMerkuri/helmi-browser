plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 compiles Kotlin itself; declaring KGP here pins the compiler version it uses.
    // LiteRT-LM is built with Kotlin 2.4, so the AGP default (2.2) cannot read its metadata.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
