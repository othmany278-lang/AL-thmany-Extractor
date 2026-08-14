# تقرير اختبار AL-thmany Extractor 2.14.0

تم تنفيذ فحص عقد المصدر وPure Kotlin regressions بعد تعديل ذاكرة القروبات الموحدة ومحرك الوصول والفحص + الانضمام. جميع الفحوص المحلية المتاحة نجحت.

الاختبارات التي تمت: التحقق من build matrix، version 2.14.0، قاعدة البيانات الموحدة DB v10، package scoping، GroupAccessRouter، Search fallback المتأخر، Event-first extraction، تصنيف الروابط وبيانات المصدر، أوضاع ScanActionMode، منع إعادة الإجراء غير المحسوم، مشاركة GroupRecord مع Publisher، Run Token، وSQLite/runtime safety.

لا تتوفر Android SDK/Gradle كاملة في بيئة التسليم، لذلك `testDebugUnitTest + lint + assembleDebug` يجب أن ينفذها GitHub Actions عند رفع المصدر. ملف workflow محدث لرفع Artifact باسم `AL-thmany-Extractor-UnifiedGroupMemory-2.14.0-APK`.
