# AL-thmany Extractor 2.20.0 — Final Runtime Fix

- تشديد مزامنة القروبات: لا تعتبر قائمة Groups مؤكدة إلا عند إثبات حالة الفلتر. وإلا يتم استخدام Group Info verification صفًا بصف.
- حذف مسار Search القديم من الاستخراج نهائيًا.
- إضافة سرعة نشر فورية 0ms بين القروبات (بدون Delay صناعي؛ يبقى انتظار واجهة واتساب/التحقق فقط).
- Semi-Hidden للمرفقات: يحاول المطابقة المرئية + Scroll داخل Share Picker، ولا يستخدم Search إلا إذا كان وضع النشر يسمح به.
- توسيع حالات النشر: READ_ONLY / GROUP_NOT_FOUND / LEFT / BLOCKED / UI_ERROR / TIMEOUT.
- الحفاظ على Event-first + dedupe + recovery + unified GroupRecord database + Pause/Resume/Stop.

ملاحظة: JID/ID يتم حفظه إن توفر، لكن لا يتم تزوير Direct JID route غير مدعوم من WhatsApp Android.
