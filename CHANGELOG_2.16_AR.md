# AL-thmany Extractor 2.16.0 — Root Fix + Neon Control Center

## الهدف
هذا الإصدار يعالج نقطة التعطل بين مزامنة القروبات وبدء الاستخراج، ويعيد ضبط واجهة التطبيق لتقترب من لوحة AL-thmany Extractor الداكنة/الخضراء المعتمدة، مع الحفاظ على محركات الاستخراج والفحص والنشر.

## إصلاحات الاستخراج الجذرية
- إلغاء Search من مسار الاستخراج: بعد المزامنة لا يكتب التطبيق اسم القروب في مربع البحث.
- الوصول للقروب من GroupRecord المحفوظ ثم المطابقة من قائمة القروبات/المحادثات والتمرير فقط.
- تطبيع اسم القروب قبل المطابقة: Unicode NFKC + إزالة Bidi marks + التشكيل + Variation Selectors + توحيد المسافات.
- العثور على صف القروب حتى عندما يكون TextView غير clickable والـparent هو هدف الضغط الحقيقي.
- التحقق بعد الفتح لم يعد يعتمد على اسم Header فقط؛ يقبل أيضًا بنية محادثة مؤكدة بعد الضغط على صف مطابق.
- تحسين التعرف على شاشة قائمة محادثات واتساب عبر Bottom Navigation عندما لا تظهر Filter Chips لخدمة Accessibility.
- منع Recovery من تنفيذ Back متكرر والخروج من واتساب فقط بسبب غياب فلتر الواجهة.

## Shizuku / Hybrid Profiles
- نفس تطبيع أسماء القروبات أضيف إلى Shizuku UI runtime.
- إضافة Structural Conversation Confirmation بعد الضغط على صف القروب.
- حذف Search fallback من استخراج Shizuku كذلك.
- تحسين كشف شاشة قائمة المحادثات عبر Bottom Navigation أو صفوف محادثات حقيقية.
- إصلاح حالة بيئة تحتوي عددًا قليلًا من المحادثات؛ لا يشترط وجود محادثتين لتأكيد قائمة واتساب.
- واجهة التطبيق تسمح ببدء الاستخراج والفحص والنشر عندما يكون Shizuku جاهزًا حتى لو Accessibility غير متصلة.

## الواجهة الجديدة
- هوية AL-thmany Extractor مع شارة v2.16.0.
- Dark/Neon Control Center بألوان أسود/رمادي داكن + أخضر تركوازي.
- شارات Event-first / Hybrid Profiles / Package Guard.
- تبويبات علوية للاستخراج والفحص والنشر مع خط تفعيل أخضر.
- بطاقات أكثر إحكامًا وأقل فراغًا أبيض.
- WhatsApp target selector أفقي واضح مع Package Guard.
- زر بدء رئيسي بلون أخضر قوي وبحالة جاهزية حقيقية للـBackend.
- توحيد شكل مؤشرات الأداء، التقدم، النتائج، الاختيارات والأزرار.

## الفحص والنشر
- لم تُحذف أي وظيفة قائمة.
- Scan + Join بقي داخل محرك الفحص نفسه.
- النشر ما زال يعيد استخدام GroupRecord وGroupAccessRouter.
- Search يبقى متاحًا فقط للمسارات التي تحتاجه خارج استخراج القروبات، وليس كطريقة استخراج أساسية.

## التحقق
- Source Contract Validation: PASS.
- Pure Engine Checks: PASS.
- Pure Scan Checks: PASS.
- Pure Publish Checks: PASS.
- Pure Runtime Checks: PASS.
- Pure Profile Checks: PASS.
- الحزمة لا تحتوي Gradle Wrapper؛ GitHub Actions يستخدم Gradle 8.13 مباشرة للبناء واختبارات Android/Lint/APK.
