# تقرير التحقق — AL-thmany Extractor Android v2.0

## ناجح في هذه البيئة

- LinkExtractor يلتقط HTTP/HTTPS وwww وينظف علامات الترقيم النهائية.
- Normalization يوحد scheme/host/www/trailing slash بشكل محافظ.
- Anchor matching يحتاج تداخلًا مفيدًا ولا يعتمد على token واحد عند توفر أكثر.
- EndProofTracker لا يبدأ شهادة النهاية من فشل تمرير واحد.
- Older-loader يعيد ضبط تقدم إثبات النهاية.
- Structural terminal boundary يقبل الحالة البنيوية فقط.
- Empty/non-scrollable exception تحتاج 3 تمريرات.
- ترتيب السرعة: HYPER < ADAPTIVE < SMART < BALANCED < SAFE.
- حد المزامنة = 4000.
- محرك ExtractionController + Accessibility adapter اجتاز compile دلالي باستخدام Android stubs.
- طبقة SQLite/Repository اجتازت compile دلالي باستخدام SQLite stubs.

## غير قابل للاختبار في هذه البيئة

- بناء APK حقيقي: Android SDK/Gradle binary غير متوفر محليًا.
- اختبار شجرة Accessibility على إصدار WhatsApp حي.
- قياس سرعة جهاز حقيقي وتحميل تاريخ محادثة كبير.

## شرط قبول النسخة ميدانيًا

ابدأ بـ 3-5 قروبات: صغير، متوسط، ضخم، وقروب فيه زر تحميل أقدم إن أمكن. قارن عدد الروابط يدويًا في عينة من الرسائل. إذا كشف WhatsApp بنية مختلفة، عدّل WhatsAppUiAdapter فقط ثم أعد الاختبار قبل التشغيل واسع النطاق.
