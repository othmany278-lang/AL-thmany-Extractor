# AL-thmany Extractor Pro 2.9.0

القاعدة: تطوير مباشر لـ **AL-thmany-Extractor-Android-v2.4-Publish** بدون دمج مكونات Joiner.

## الاستخراج
- الإبقاء على Deep / Smart / Links Tab / All Chats / New Only.
- Event-first timing matrix أسرع مع المحافظة على Strict End Proof.
- `eventSampleDelayMs` حسب السرعة بدل تأخير ثابت داخل كل UI mutation.
- Batch persistence: كل لقطة مرئية تجمع روابطها ثم تكتبها بعملية SQLite واحدة.
- WAL وفهارس مركبة لقراءة العدادات والنتائج أثناء استمرار الكتابة.
- Checkpoint / Pause / Resume / same-group retry لم تتغير وظيفيًا.

## الفحص
- يبقى **Read-only**: لا Join ولا Request-to-join.
- HYPER: preview timeout 3.2s / event wait 90ms / settle 25ms.
- ADAPTIVE: 5.2s / 145ms / 40ms.
- SAFE: 8.5s / 240ms / 75ms.
- تقليل stable rounds مع بقاء شرط استقرار الواجهة والثقة قبل إنهاء UNKNOWN.
- الاستكمال وإعادة فحص الحالات المؤقتة بقيت كما في Scan Pro.

## النشر
- الأقسام المستهدفة هي المجموعات التي يحددها المستخدم فقط.
- سرعة الانتقال: Fast 1.8s / Adaptive 3.0s / Safe 5.0s بين القروبات.
- فتح القروب يستخدم `uiTimeoutMs` الحقيقي للتحقق من الهوية بدل استخدام delay بين القروبات كمهلة فتح.
- التنقل والبحث Event-first أسرع.
- لا إعادة إرسال عمياء بعد تنفيذ Send؛ حماية duplicate-send محفوظة.
- Pause / Resume / SQLite per-group history محفوظة.

## تشغيل موحد
- إضافة `RuntimeOperationCoordinator` ذري.
- لا يمكن للاستخراج والفحص والنشر امتلاك واجهة واتساب في الوقت نفسه حتى لو ضغط المستخدم زرين بسرعة.

## الواجهة
- Bottom Navigation أصبح: **الاستخراج | الفحص | النشر**.
- إدارة المجموعات والنتائج/التصدير أصبحت أدوات داخل قسم الاستخراج.

## الخصوصية
- `android:allowBackup=false`.
- `android:usesCleartextTraffic=false`.
- جميع إشعارات العمليات `VISIBILITY_PRIVATE` على شاشة القفل.

## البناء
- AGP 8.13.2.
- Gradle 8.13.
- Kotlin 2.3.21.
- JDK 17.
- compileSdk/targetSdk 36.
- Compose BOM 2026.04.01.
- GitHub Actions: source validation -> unit tests -> lint -> APK.
