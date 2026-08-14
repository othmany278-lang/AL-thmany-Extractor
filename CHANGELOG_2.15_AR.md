# AL-thmany Extractor 2.15.0 — Hybrid Profiles

هذا الإصدار يدمج أفكار الربط متعددة البيئات من مشروع AL-thmany 2.8.0 داخل خط Extractor الحالي، مع إبقاء المنتج Extractor/Scanner/Publisher وعدم إعادة محرك QuickJoin القديم.

## محرك التشغيل الموحد
- AUTO يفضّل Accessibility المحلية داخل نفس Android Profile عندما تكون متصلة.
- إذا لم تتصل Accessibility محليًا، يمكن استخدام Shizuku كمسار بديل بعد الإذن والـprobe.
- Shizuku لا يُعتبر دليلاً على إمكانية التحكم لمجرد وجود Binder؛ كل عملية تتحقق فعليًا أن UIAutomation يرى حزمة واتساب المحددة.
- لا يوجد تجاوز لـ Knox أو DPC أو حدود Work Profile/Secure Folder.

## الشخصي / العمل / المجلد الآمن
- Profile Key منفصل لكل Android user/profile لمنع خلط حالة المستخدم الرئيسي مع Work/Secure.
- Accessibility heartbeat والأحداث وroot snapshot تحفظ محليًا لكل Profile.
- اكتشاف واتساب يتم من Context الملف الحالي فقط.
- في Work Profile أو Secure Folder يلزم وجود AL-thmany ونسخة WhatsApp المطلوبة في البيئة التي تسمح Android بالوصول إليها؛ عند غياب الوصول يتوقف المحرك Fail-Closed.

## WhatsApp / Business / Clone / Dual App
- دعم com.whatsapp وcom.whatsapp.w4b وcom.whatsapp2.
- اكتشاف ديناميكي لحزم WhatsApp/Clone التي تظهر في Launcher أو تستطيع معالجة روابط الدعوة.
- تم إلغاء packageNames الثابتة في إعداد Accessibility حتى لا تُحجب حزمة Clone يغيّر اسمها المصنع/تطبيق الاستنساخ؛ التصفية تتم داخل Runtime.
- تم نقل DualMessengerMatcher من أفكار 2.8.0 للتعرف على تسميات Samsung Dual Messenger/نسخة واتساب. النسخ التي لا تظهر كحزمة مستقلة تظل خاضعة لما يسمح به Resolver/Android ولا يتم ادعاء وصول مضمون لها.

## Shizuku Hybrid
- Shizuku API/Provider 13.1.5 + AIDL UserService.
- Persistent UiAutomation: rootInActiveWindow + event sequence + compact snapshots.
- Tap / Click node / Swipe / Back / ACTION_SET_TEXT.
- Extraction: مزامنة القروبات + فتح ثنائي الاتجاه + Event-first scroll + link extraction + checkpoint + Back ثم next group.
- Scan: فتح الرابط وتصنيف الحالة، مع Scan/Join modes الموجودة أصلًا.
- Publish: فتح القروب من GroupRecord، كتابة وإرسال والتحقق مع duplicate-safe uncertainty.

## ما تم الحفاظ عليه من 2.14.2
- Group-only sync وعدم حفظ عناصر الواجهة كقروبات.
- القروبات المكتشفة تبدأ غير محددة.
- sync_generation لتنظيف السجلات القديمة الخاطئة.
- GroupAccessRouter موحد بين الاستخراج والنشر.
- Search حل أخير بعد المطابقة والتمرير في الاتجاهين.
- Event-first + 95ms fallback في Accessibility.
- Strict End Proof + checkpoints + Pause/Resume/Recovery.
