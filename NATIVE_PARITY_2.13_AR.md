# آلية Native Parity في 2.13.0

## قاعدة مهمة

هذه النسخة لا تستخدم WhatsApp Web ولا DOM ولا WhatsApp Web Store. لا تدعي الوصول إلى `@g.us -> Store -> chat.sendMessage()` لأن تطبيق Android العادي لا يملك هذا الجسر الداخلي.

المقابل على Android هو:

- Accessibility hierarchy + Accessibility events للقراءة والتحكم المرئي.
- Gesture fallback للسحب عندما لا يقبل العنصر `ACTION_SCROLL_*`.
- Android intents لفتح روابط الدعوات والنسخة المحددة من واتساب.
- Android `ACTION_SEND` للمرفقات مثل VCF والصور.
- SQLite محلي للـqueue والنتائج وcheckpoints والحماية من التكرار.

## مسار الاستخراج

`اختيار واتساب -> مزامنة -> عرض العدد/الأسماء -> تحديد الكل/إلغاء/غير المقروء/النشط/القابل للنشر/غير المؤكد -> فتح القروب -> تحقق -> قراءة/استخراج -> تنظيف/تصنيف/Dedupe -> سحب للأقدم -> تحميل الأقدم إن ظهر -> End Proof -> Checkpoint/Commit -> القروب التالي`

مزامنة Android هي UI-derived. واتساب لا يكشف API رسميًا لقائمة القروبات لتطبيق طرف ثالث، لذلك الأسماء المكتشفة تلقائيًا تبقى **مرشحة/غير مؤكدة** إلى أن يفتحها المحرك ويتحقق من مؤشرات group-info. هذا يمنع ادعاء أن المحادثة الخاصة قروب.

## مسار الفحص

`Dedupe -> Network Guard -> Open exact URL -> Facts -> Stable Gate -> Classification -> Commit -> Close -> Next`

Timeout أو انقطاع الشبكة لا يعني Invalid. Invalid يحتاج دليلًا صريحًا من واجهة واتساب. لا توجد أي ضغطة Join/Request في هذا المسار.

## مسار النشر

النص:

`Preflight -> Target group -> Verify -> Composer -> Send -> Verify/Uncertain -> Commit -> pacing -> Next`

المرفقات:

`Preflight -> ACTION_SEND to selected WhatsApp -> Exact recipient -> Share/Preview -> Verify/Uncertain -> Commit -> Next`

`UNCERTAIN` حالة مقصودة: إذا حدثت ضغطة قد تكون أرسلت المحتوى ثم انقطع التطبيق قبل التحقق، لا تتم إعادة الإرسال تلقائيًا حتى لا تتكرر الرسالة.

## ما ليس موجودًا في 2.13

لا يوجد Shizuku backend حقيقي بعد. لا يوجد تجاوز Knox/Secure Folder. لا يوجد وصول إلى قاعدة بيانات واتساب الخاصة أو WhatsApp Web Store. إذا كانت Accessibility غير متاحة داخل Work Profile/Secure Folder، فهذه النسخة لا تستطيع ادعاء التحكم في تلك البيئة.
