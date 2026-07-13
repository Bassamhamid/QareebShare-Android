# البناء والفحص

## المسار المعتمد: GitHub Actions

ملف Workflow يعمل يدوياً فقط أثناء التطوير:

`Actions → Android Build → Run workflow`

ينفذ:

- فحص عدم وجود مكتبات تشغيل خارجية.
- Lint لنسخة Debug.
- بناء Debug APK.
- بناء Release APK مصغر بواسطة R8.
- رفع النواتج داخل Artifact باسم `qareeb-share-apks` لمدة 14 يوماً.

## الفحص من Termux دون Android SDK

```bash
cd "$HOME/QareebShare-Android"
./scripts/check-final.sh
```

هذا الفحص لا ينزّل Gradle ولا يبني APK ولا يشغّل GitHub Actions.

## بناء محلي اختياري

يتطلب JDK 17 وAndroid SDK ومنصة API 37:

```bash
./gradlew :app:verifyNoRuntimeDependencies :app:lintDebug :app:assembleDebug :app:assembleRelease
```
