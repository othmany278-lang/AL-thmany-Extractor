# تقرير اختبار AL-thmany Extractor v2.1

## الاختبارات المنفذة في بيئة التطوير

- PASS — اختبارات LinkExtractor وتنظيف الروابط ومنع التكرار.
- PASS — Checkpoint Anchor وEndProofTracker وسياسات السرعة.
- PASS — ProfileLaunchPolicy: اختيار تلقائي عند وجود نسخة واحدة فقط.
- PASS — ProfileLaunchPolicy: منع الاختيار التلقائي عند وجود WhatsApp وWhatsApp Business معًا.
- PASS — حفظ الاختيار الصريح لواتساب الأعمال.
- PASS — Package/Profile mismatch guard.
- PASS — ترجمة/Compilation لمحرك الاستخراج + Accessibility + Profile classes باستخدام Android stubs.
- PASS — موازنة الأقواس والبنية الأساسية لملفات Compose المعدلة.

## ما لم يتم اختباره هنا

- لم يتم إنشاء APK في هذه البيئة لعدم وجود Android SDK/Gradle Android toolchain كامل.
- لم يتم تشغيل النسخة على هاتف Samsung حقيقي داخل Secure Folder أو Work Profile.
- دعم Accessibility داخل Secure Folder يعتمد على إصدار One UI/Knox وسياسات الجهاز. التطبيق لا يحاول تجاوز قيود النظام؛ إذا لم تتصل الخدمة فسيمنع البدء.

## اختبار الهاتف المطلوب

1. تثبيت AL-thmany داخل Secure Folder بجانب WhatsApp/WhatsApp Business.
2. فتح AL-thmany من داخل Secure Folder.
3. التأكد أن بطاقة «بيئة التشغيل» تعرض بيئة معزولة/سامسونج.
4. التأكد أن قائمة واتساب تعرض النسخ الموجودة داخل البيئة الحالية.
5. اختيار WhatsApp Business ثم الضغط «فتح نسخة واتساب المحددة» والتأكد أنه لا يخرج لواتساب الشخصي.
6. تكرار الاختبار مع WhatsApp العادي.
7. تفعيل Accessibility داخل نفس البيئة إن سمح النظام، ثم تشغيل مزامنة قروب واحد واستخراج قصير قبل جلسة طويلة.
