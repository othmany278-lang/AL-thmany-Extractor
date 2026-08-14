# AL-thmany Extractor 2.14.1 — Runtime Access Fix

هذا تحديث إصلاح تشغيلي لخاصيتي الاستخراج والنشر على واتساب Android الحقيقي.

- تخفيف شرط مطابقة صف القروب: لم يعد يحتاج ظهور الوقت/عداد غير المقروء بجوار الاسم.
- مسح قائمة القروبات في الاتجاهين بدل البحث للأسفل فقط.
- Gesture tap احتياطي عندما لا ينفذ WhatsApp ACTION_CLICK على صف/عنوان القروب.
- تحسين العثور على مربع كتابة الرسالة عبر View IDs + editable fallback.
- Focus/Click قبل ACTION_SET_TEXT لتحسين النشر على إصدارات واتساب المختلفة.
- تحسين العثور على زر Send عبر View ID ثم النص/الوصف.
- Search بقي آخر fallback فقط.
- لا يتم حفظ AccessibilityNodeInfo في قاعدة البيانات.
