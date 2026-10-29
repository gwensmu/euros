# Android app

Kotlin, Compose and Room. Use JDK 21 and Android SDK 36; set ANDROID_HOME or an untracked local.properties.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK: app/build/outputs/apk/debug/app-debug.apk. Bundled activity plans initialize missing records on first launch. They are example prescriptions, not completed observations. Customize assets and plan-generation inputs before personal use.

Connect your own backend in Settings, using its app credential. Health Connect requires device support and user permission. Tests use synthetic fixtures; real device permissions/background scheduling need device verification. Provider keys stay on the backend.
