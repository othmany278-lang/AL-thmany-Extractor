# تحديث مستودع AL-thmany إلى v2.17.0

هذا الملف يحتوي **ملفات التحديث فقط** للانتقال من v2.15.x إلى v2.17.0.

## ما الذي يتضمنه التحديث؟
- الإصلاح الجذري لفتح القروبات من الذاكرة بدون كتابة الاسم في Search أثناء الاستخراج.
- تطبيع أسماء القروبات وتحسين مطابقة صفوف واتساب والـHeader.
- Structural Chat Verification لبدء الاستخراج عند فتح المحادثة فعليًا.
- Event-first extraction + 95ms fallback poll + deduplication + scroll recovery.
- تحسين دعم Shizuku/Accessibility والـHybrid Profiles.
- واجهة Exact Dashboard الجديدة المبنية بـJetpack Compose.
- تحديث GitHub Actions إلى v2.17.0.
- تحديث اختبارات المصدر وملفات التحقق.

## طريقة الدمج
1. افتح جذر المستودع الحالي.
2. فك ضغط هذا الملف **داخل جذر المستودع** مع السماح باستبدال الملفات الموجودة.
3. لا تحذف الملفات الأخرى من المستودع؛ هذا ZIP يحتوي فقط الملفات المضافة/المعدلة.
4. نفذ:

```bash
git status
git add .
git commit -m "Update AL-thmany to v2.17.0 Exact Dashboard + Root Fix"
git push origin main
```

5. افتح GitHub Actions وشغّل `Build AL-thmany APK` إذا لم يبدأ تلقائيًا.

## التحقق قبل الرفع
تم تشغيل:
- Pure Engine/Profile checks
- Scan checks
- Publish checks
- Runtime checks
- `scripts/validate_release.py`

والنتيجة: **PASS** على عقد المصدر v2.17.0.

> ملاحظة: نجاح اختبارات المصدر لا يغني عن تجربة APK على الهاتف مع إصدار واتساب الفعلي، خصوصًا Work Profile / Secure Folder.
