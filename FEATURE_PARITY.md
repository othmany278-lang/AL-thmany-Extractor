# Feature parity — WA-Workspace extraction -> Android v2.0

| ميزة الاستخراج في WA-Workspace | Android v2.0 | الملاحظة |
|---|---|---|
| استخراج HTTP/HTTPS | نعم | من Accessibility UI بدل DOM/Store |
| سحب سريع للرسائل القديمة | نعم | ACTION_SCROLL_BACKWARD + Gesture fallback |
| التقاط أثناء السحب/التحميل | نعم | captureBurst على أحداث Accessibility |
| ضغط تحميل الرسائل الأقدم | نعم | أنماط عربية/إنجليزية + structural click |
| منع الانتقال عند نهاية غير مؤكدة | نعم | EndProofTracker + quiet end proof مرتين |
| إعادة نفس القروب عند فشل شهادة النهاية | نعم | 3 محاولات افتراضيًا |
| بدء القروب التالي فور الاكتمال | نعم | لا يوجد delay اصطناعي طويل |
| Checkpoint / Pause / Resume | نعم | SQLite + SharedPreferences anchors |
| مزامنة حتى 4000 | نعم، best-effort | أسماء محادثات Android ثم تحقق Group Info |
| المؤرشفة | نعم، best-effort | تفحص قبل تمرير القائمة الرئيسية |
| وضع تبويب الروابط | نعم | عبر Group info / Media links docs |
| وضع جميع الدردشات | نعم | سحب متداخل عالي الإنتاجية داخل كل القروبات المحددة |
| وضع الجديد فقط | نعم | Anchor سابق / unread divider / bootstrap |
| الوضع الذكي | نعم | Deep-first ثم Links-tab fallback |
| نسخ النتائج | نعم | من شاشة النتائج |
| Excel / CSV / TXT / JSON | نعم | XLSX خفيف بدون مكتبة Excel ضخمة |
| بيانات Store الداخلية | لا | غير متاحة لتطبيق Android آخر بسبب sandbox |
| Message ID داخلي موثوق | لا | لذلك لا نضخم occurrences عند إعادة رسم نفس الرسالة |
| استخراج والشاشة مطفأة | لا | UI automation يتطلب شاشة واتساب متاحة |

## نقطة الاختلاف الأساسية

WA-Workspace يعمل داخل WhatsApp Web ويستفيد من DOM وواجهات Store الداخلية التي حمّلها WhatsApp Web. APK مستقل على Android لا يملك تلك الواجهات ولا قاعدة بيانات WhatsApp. لذلك تم نقل **منهجية الاستخراج والموثوقية**، وليس الادعاء بوجود صلاحيات Android غير متاحة.
