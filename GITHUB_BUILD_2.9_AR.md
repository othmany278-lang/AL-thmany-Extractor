# بناء AL-thmany Extractor Pro 2.9.0 على GitHub

1. ارفع **محتويات** هذا المجلد إلى جذر مستودع GitHub.
2. تأكد أن الفرع `main` يحتوي `.github/workflows/build-apk.yml`.
3. أي Push إلى `main` يشغل البناء تلقائيًا.
4. Workflow ينفذ:
   - Source Contract Validation
   - Unit Tests
   - Lint
   - `:app:assembleDebug`
5. عند النجاح افتح آخر Run ثم Artifacts وحمّل:
   `AL-thmany-Extractor-Pro-2.9.0-APK`

المصفوفة المثبتة في المشروع:
`AGP 8.13.2 / Gradle 8.13 / Kotlin 2.3.21 / JDK 17 / API 36`.
