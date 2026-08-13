# AL-thmany Extractor v2.1 — Secure Folder / Work Profile

## تغييرات هذه النسخة

- إضافة `RuntimeProfileDetector` للتعرف قدر الإمكان على: الملف الشخصي، Work Profile، وملفات Android المعزولة/بيئات سامسونج.
- إضافة `WhatsAppInstanceRegistry` لاكتشاف WhatsApp وWhatsApp Business داخل **الـProfile الحالي فقط**.
- لم يعد التطبيق يختار `com.whatsapp` تلقائيًا إذا وجد واتساب وواتساب أعمال معًا؛ يطلب اختيارًا صريحًا لمنع فتح الحساب الخطأ.
- حفظ اختيار واتساب بشكل مستقل داخل كل Profile عبر SharedPreferences الخاصة بنسخة التطبيق هناك.
- `openWhatsApp()` أصبح Explicit Package Launch ولا يستخدم Intent عام.
- إضافة `Profile Guard`: إذا ظهرت نسخة واتساب غير المحددة أثناء المهمة، لا يكمل الاستخراج عليها ويعيد النسخة المختارة قبل المتابعة.
- المزامنة، البحث، التحقق من عنوان القروب، السحب، وإثبات النهاية كلها أصبحت مرتبطة بالـpackage المحدد.
- واجهة جديدة تعرض بيئة التشغيل الحالية ونسخ واتساب المتاحة، مع اختيار WhatsApp / WhatsApp Business.
- قاعدة البيانات والـcheckpoints تبقى منفصلة تلقائيًا بين Personal / Secure Folder / Work Profile لأن Android يفصل بيانات التطبيق لكل مستخدم/Profile.
- لا توجد صلاحية `INTERACT_ACROSS_PROFILES`: التصميم لا يحاول تجاوز عزل Android/Knox، بل يشغل نسخة AL-thmany داخل نفس البيئة التي تحتوي واتساب المستهدف.

## التشغيل داخل المجلد الآمن

1. أضف/ثبت AL-thmany Extractor داخل Secure Folder نفسه.
2. تأكد أن WhatsApp أو WhatsApp Business المستهدف موجود داخل Secure Folder.
3. افتح AL-thmany من داخل Secure Folder.
4. فعّل Accessibility للنسخة الموجودة في هذه البيئة إذا سمح نظام Samsung بذلك.
5. اختر نسخة واتساب الظاهرة في شاشة AL-thmany، ثم اختبر زر «فتح نسخة واتساب المحددة».

إذا منع إصدار One UI/Knox تشغيل Accessibility داخل Secure Folder، سيظهر أن الخدمة غير متصلة ولن يدعي التطبيق أنه قادر على الاستخراج. هذا قيد للنظام وليس مسارًا يتم تجاوزه.

## التشغيل داخل Work Profile

ثبت نسخة AL-thmany داخل Work Profile بجانب واتساب الخاص بالعمل. قد يمنع مسؤول المؤسسة تثبيت التطبيق أو تفعيل Accessibility؛ في هذه الحالة يلزم السماح من سياسة العمل.
