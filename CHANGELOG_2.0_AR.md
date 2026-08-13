# AL-thmany Extractor Android v2.0

- إعادة كتابة State Machine للاستخراج المباشر على WhatsApp Android.
- Deep extraction هو الوضع الافتراضي.
- إضافة SMART / DEEP / LINKS_TAB / ALL_CHATS / NEW_ONLY.
- إضافة HYPER / ADAPTIVE / SMART / BALANCED / SAFE بما يطابق مستويات أداء الأداة الأصلية بصورة أقرب.
- التقاط متداخل للروابط أثناء كل تغير في واجهة واتساب.
- ضغط تلقائي لعناصر تحميل الرسائل الأقدم.
- EndProofTracker مع إثبات نهاية محافظ ومتكرر.
- التحقق من بقاء عنوان القروب في رأس المحادثة أثناء الاستخراج وإثبات النهاية.
- إعادة نفس القروب حتى 3 مرات عند نهاية غير مثبتة.
- Structural gate يمنع تفسير نص عضو عادي كحالة نهاية نظام.
- مزامنة حتى 4000 اسم + دعم المؤرشفة best-effort.
- التحقق من القروب المكتشف تلقائيًا عبر معلومات المجموعة.
- SQLite schema v2 للـ checkpoints والlogs وحالة التحقق.
- حفظ مرحلي واستكمال سريع من Anchor.
- نتائج دقيقة بدون تضخيم تكرار الرابط بسبب إعادة رسم Accessibility.
- XLSX / CSV / TXT / JSON + نسخ الكل.
- عزل تكامل WhatsApp داخل WhatsAppUiAdapter.
