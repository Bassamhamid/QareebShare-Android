# البناء

## GitHub Actions

المسار الموصى به لهذا المشروع. يكفي دفع الملفات إلى فرع `main`.

## Linux أو Termux

الملف `gradlew` مشغّل خفيف يتحقق من SHA-256 ثم ينزّل Gradle 9.4.1 عند أول استخدام فقط:

```bash
pkg install openjdk-17 curl unzip -y
./gradlew :app:assembleDebug
```

يتطلب البناء المحلي Android SDK مع منصة API 37. لذلك يظل GitHub Actions هو المسار الأبسط من الهاتف.
