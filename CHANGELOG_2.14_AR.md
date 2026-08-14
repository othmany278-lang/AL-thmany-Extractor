# AL-thmany Extractor 2.14.0 — Unified Group Memory

## ما تغير فعليًا

- أصبحت مزامنة القروبات تبني **ذاكرة قروبات موحدة** مشتركة بين الاستخراج والنشر.
- لا يتم حفظ `AccessibilityNodeInfo`؛ المحرك يطلب Root جديدًا من Accessibility عند كل خطوة ويحفظ فقط بيانات مستقرة في `GroupRecord`.
- أضيفت هوية/ذاكرة وصول لكل قروب: package، JID/ID إن توفر، ترتيب المزامنة، آخر/أفضل طريقة وصول، وعدادات نجاح وفشل الوصول.
- فتح القروبات لم يعد Search-first. الأولوية أصبحت: المحادثة الحالية/المسار المحفوظ → المطابقة في القائمة → Scroll + Match → Search كحل أخير.
- الاستخراج بقي Event-first ويجمع الروابط أثناء التمرير، مع تصنيف أوسع وحفظ Source Group وWhatsApp package وInvite Code.
- النشر يعيد استخدام نفس `GroupRecord` ونفس `GroupAccessRouter` بدل البحث عن القروب من الصفر.
- شاشة الفحص أضيف لها: **فحص فقط / انضمام فقط / فحص + انضمام**، مع خيار مستقل لإرسال طلبات الموافقة.
- فحص + انضمام يعمل كدورة واحدة: OPEN ONCE → SCAN → CLASSIFY → ACTION إن سمح الإعداد → VERIFY → SAVE → NEXT.
- نتيجة الإجراء غير المحسومة تسجل `ACTION_UNCERTAIN` ولا يعاد الضغط بشكل أعمى.
- عمليات الاختيار والاستخراج أصبحت مقيدة بنسخة WhatsApp المحددة حتى لا تختلط مجموعات WhatsApp وWhatsApp Business.

## قيود مقصودة

- لا يوجد في WhatsApp Android مسار رسمي ثابت لفتح محادثة قروب بواسطة JID؛ لذلك يتم حفظ JID إن توفر ولا يتم ادعاء وجود Direct JID route غير مدعوم.
- الإصدار ما زال Native Android عبر Accessibility/Android UI. لم تتم إضافة Backend حقيقي لـShizuku في هذا الإصدار.
- Search موجود فقط كـfallback متأخر عند فشل المطابقة من قائمة الدردشات.
