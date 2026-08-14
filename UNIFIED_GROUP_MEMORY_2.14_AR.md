# تصميم ذاكرة القروبات الموحدة — 2.14.0

## المسار الرئيسي

```text
SYNC WHATSAPP
    ↓
GROUP DATABASE
    ↓
┌──────────────┬──────────────┬──────────────┐
│ EXTRACTOR    │ PUBLISHER    │ SCANNER      │
└──────────────┴──────────────┴──────────────┘
```

## GroupRecord

يحفظ التطبيق بيانات القروب فقط، مثل `internalId` واسم القروب وWhatsApp package وJID/Group ID عندما يكون متاحًا بصورة حقيقية، إضافة إلى آخر/أفضل طريقة وصول وحالات الاستخراج والنشر. لا يتم الاحتفاظ بأي `AccessibilityNodeInfo` بين تغييرات الشاشة.

## فتح القروب

```text
CURRENT/CACHED ACCESS
        ↓
VISIBLE LIST MATCH
        ↓
SCROLL + MATCH
        ↓
SEARCH FALLBACK
```

يتم طلب Root جديد في كل محاولة. المسارات التي لا يدعمها WhatsApp Android رسميًا (مثل JID direct) لا يتم تمثيلها كأنها ناجحة.

## الاستخراج

```text
OPEN_GROUP → VERIFY → ACCESSIBILITY EVENT → FRESH ROOT
→ TRAVERSE → URL PARSER → CLASSIFY → DEDUPE → SAVE
→ SCROLL OLDER → EVENT → SCAN AGAIN → END PROOF → NEXT GROUP
```

## النشر

يستخدم الناشر نفس GroupRecord وGroupAccessRouter. فشل قروب واحد يمر عبر Recovery/Retry ثم يسجل النتيجة وينتقل بدل إيقاف الحملة كاملة.

## الفحص + الانضمام

```text
OPEN ONCE → SCAN → CLASSIFY
→ JOIN / REQUEST (حسب الإعداد)
→ VERIFY → COMMIT RESULT → NEXT
```

`REQUEST_TO_JOIN` لا ينفذ تلقائيًا إلا إذا كان خيار إرسال طلبات الموافقة مفعّلًا.
