# AL-thmany Extractor 2.13.0 — Native Parity

## الهدف

مواءمة وظائف **الاستخراج + الفحص + النشر** مع التسلسل الذي تم اعتماده من WA-Workspace، لكن على تطبيقات WhatsApp الحقيقية في Android وبدون WhatsApp Web.

## الاستخراج

- مزامنة قائمة المحادثات من نسخة واتساب المحددة مع حفظ metadata ظاهرة: غير المقروء، النشاط، قابلية النشر المبدئية، وCommunity Parent hint.
- بعد المزامنة يحاول المحرك إعادة قائمة الدردشات إلى الموضع الذي بدأت منه بدل تركها عند آخر القائمة.
- فلاتر اختيار: تحديد الكل، إلغاء التحديد، غير المقروءة، النشطة، القابلة للنشر، وغير المؤكدة.
- التحقق من أن المحادثة قروب قبل استخراجها إذا كانت مكتشفة تلقائيًا.
- Deep Event-first: قراءة + استخراج أثناء كل تغير واجهة + Scroll إلى الأقدم + Gesture fallback.
- التقاط URL من text/contentDescription/hint/tooltip/URLSpan/public extras.
- تنظيف HTML entities والرموز غير المرئية، ثم توحيد الرابط وتصنيفه: WhatsApp/Telegram/Instagram/Facebook/Google/PDF/Other.
- Older-message loader عندما تكشفه شجرة Accessibility.
- Strict End Proof متعدد الجولات قبل إعلان اكتمال القروب.
- Checkpoint مرحلي ومكتمل، ثم انتقال تلقائي للقروب التالي.

## الفحص

- قائمة روابط deduplicated في SQLite.
- Single-Flight: رابط واحد فقط في كل لحظة.
- فتح مرئي في نسخة واتساب المحددة.
- Connectivity Guard: انقطاع الإنترنت لا يصبح Invalid؛ ينتظر ثم يعيد نفس الرابط دون تحريك المؤشر.
- Stable Result Gate حتى للإشارات القوية، بدل تثبيت أول mutation نصية فورًا.
- Adaptive preview ceiling = 5600ms للحالات غير الواضحة؛ الحالات الواضحة تنتهي قبل ذلك عند ثبات الدليل.
- Commit للنتيجة قبل إغلاق الدعوة والانتقال.
- لا يوجد Join أو Request Join داخل محرك الفحص.

## النشر

- Preflight فعلي لنسخة واتساب وAccessibility قبل إنشاء الحملة.
- النشر فقط للقروبات المحددة مع استبعاد Community Parent وغير القابل للنشر.
- Run Token لكل حملة، وسجل SQLite لكل target.
- نص واحد.
- رسائل متعددة مفصولة بـ `---` وتوزع Round-Robin.
- جهات اتصال كنص: `الاسم | الرقم` وتنسيق تلقائي.
- VCF وVCF + نص عبر Android native share.
- صورة + تعليق عبر Android native share مع `EXTRA_TEXT` كتعليق احتياطي ومسار preview عندما يظهر.
- Turbo pacing الحقيقي = 1000ms بين القروبات.
- بعد بدء Send/Share إذا لم يمكن إثبات النتيجة تصبح `UNCERTAIN` ولا يعاد الإرسال تلقائيًا.
- Pause/Resume/Stop وحفظ التقدم.

## Build

- versionCode: 2130
- versionName: 2.13.0
- AGP: 8.13.2
- Gradle: 8.13
- Kotlin: 2.3.21
- JDK: 17
- compileSdk/targetSdk: 36
- Compose BOM: 2026.04.01
- إصلاح compile: إزالة import `foundation.layout.weight` غير المتوافق مع Kotlin/Compose الحالية.
