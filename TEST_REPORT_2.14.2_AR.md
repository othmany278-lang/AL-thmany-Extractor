# تقرير اختبار AL-thmany Extractor 2.14.2

## سبب التعديل
الفيديو العملي أظهر أن الاستخراج والنشر كانا يعرضان مئات العناصر المحددة ثم يفتحان واتساب ويستمران في تمرير قائمة الدردشات بدون فتح القروب المستهدف. تمت معالجة السبب المشترك في Sync + GroupAccessRouter بدل زيادة المهلات.

## الفحوص المنفذة
- `scripts/validate_release.py`: PASS.
- `scripts/run_pure_checks.sh`: PASS.
- Pure Engine/Profile: PASS.
- Pure Scan: PASS.
- Pure Publish: PASS.
- Pure Runtime: PASS.
- فحص بنية المصدر: DB v11 + group-only filter + stale generation + discovered unselected + bidirectional group route + post-click header verification.

## ما يحتاج اختبار الهاتف
بيئة التسليم لا تحتوي Android SDK/Gradle كاملين، لذلك البناء النهائي واختبار Accessibility مع نسخة WhatsApp الفعلية يتمان عبر GitHub Actions والهاتف. المطلوب بعد تثبيت 2.14.2: إعادة **مزامنة القروبات** مرة واحدة، ثم اختيار عدة قروبات معروفة وتجربة Deep Extraction ونشر نص قصير.
