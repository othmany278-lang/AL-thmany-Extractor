# معمارية الربط 2.15.0

المبدأ: لا نستخدم WhatsApp Web، ولا نقرأ قاعدة واتساب الخاصة، ولا نحفظ AccessibilityNodeInfo. التشغيل يتم على واجهة تطبيق WhatsApp الحقيقي ضمن حدود Android.

```text
Android Profile الحالي
        ↓
Profile Key + WhatsApp discovery
        ↓
AUTO Backend Router
   ┌────┴────┐
Accessibility  Shizuku Probe
   ↓             ↓
Live UI Tree   UiAutomation Tree
   └────┬────┘
        ↓
Unified Group Database
   ┌────┼────┐
Extract Scan Publish
```

## سياسة الملفات الشخصية
- Personal: يعمل على النسخ المرئية للمستخدم الرئيسي.
- Work Profile: الربط محلي للملف؛ لا يتم التحكم عبر حدود DPC.
- Samsung isolated/Secure Folder: يتم كشف البيئة كProfile مستقل قدر الإمكان، ولا يُعتبر Shizuku bypass لـKnox.
- Secondary user: Target Lock بالـProfile Key والحزمة.

## سياسة الـClone
إذا كشف Android نسخة Clone كحزمة مستقلة (مثل com.whatsapp2 أو حزمة WhatsApp-capable مكتشفة)، تظهر كهدف مستقل ويُقفل التشغيل عليها. إذا كان Samsung Dual Messenger يستخدم نفس package عبر Resolver ولا يوفر مسارًا مستقلاً يمكن إثباته، فلا يتم تزوير دعم مباشر؛ يلزم اختبار Resolver/الرؤية على الجهاز.
