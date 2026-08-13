# AL-thmany Extractor 2.12.0 — Neon Real UI

هذا تحديث واجهة حقيقي داخل كود Android/Jetpack Compose وليس صورًا تجريبية.

- إعادة بناء واجهات الاستخراج والفحص والنشر بالكامل بتصميم داكن/نيون أخضر.
- الأزرار الجديدة مرتبطة مباشرةً بالـ callbacks والمحركات الموجودة فعليًا.
- زر فتح واتساب مرتبط بـ ExtractionController.openWhatsApp.
- بدء/إيقاف/استكمال الاستخراج مرتبط بمحرك ExtractionController.
- شاشة الفحص مرتبطة بمحرك ScanController والاستيراد والتصدير الحقيقيين.
- شاشة النشر مرتبطة بمحرك PublishController واختيار القروبات والرسالة والتصدير.
- اختيار WhatsApp/Business ما زال مرتبطًا بـ AppViewModel.setTargetWhatsApp.
- لا تغيير في قاعدة البيانات أو منطق الحماية من التكرار أو Strict End Proof.
