# إصلاح تشغيل الاستخراج – v2.15.1 Hotfix

هذا الإصلاح يستهدف نقطة التعطل بين مزامنة القروبات وبدء استخراج المحادثة.

## التعديلات
- تطبيع أسماء القروبات قبل المطابقة لإزالة فروقات RTL/Bidi والمسافات والتشكيل وVariation Selectors.
- اكتشاف اسم القروب من TextView ثم الصعود إلى أقرب parent قابل للنقر بدل اشتراط أن يكون TextView نفسه clickable.
- توسيع توافق أبعاد صفوف القروبات مع اختلاف واجهات WhatsApp/Samsung.
- إضافة تحقق بنيوي للمحادثة بعد الضغط على القروب: Header مطابق أو Message Composer أو بنية محادثة قابلة للتمرير.
- استخدام التحقق البنيوي أثناء الاستخراج حتى لا يتوقف المحرك إذا أخفى WhatsApp اسم الـHeader عن Accessibility.
- الإبقاء على Search كـ fallback أخير فقط.
- عدم تخزين AccessibilityNodeInfo؛ تبقى قاعدة البيانات محتفظة ببيانات القروب المستقرة فقط.

## مسار التشغيل المستهدف
SYNC -> GroupRecord -> Visible list/scroll match -> click row -> structural chat verification -> extractDeep -> collectVisibleUrls -> scroll -> next group.

## التحقق
- scripts/run_pure_checks.sh: PASS
- scripts/validate_release.py: PASS

ملاحظة: المصدر الأصلي لا يتضمن Gradle Wrapper، لذلك يحتاج البناء النهائي إلى GitHub Actions أو بيئة Gradle/Android SDK متوافقة.
