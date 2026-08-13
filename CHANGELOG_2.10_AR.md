# AL-thmany Extractor 2.10.0 — TurboCore

## الهدف
رفع سرعة الاستجابة الحقيقية للمحركات الثلاثة **الاستخراج | الفحص | النشر** مع المحافظة على الدقة، الحماية من التكرار، إثبات النهاية، وحماية package/profile.

## ما تغير

1. توجيه أحداث Accessibility للمحرك النشط فقط بدل بثها للمحركات الثلاثة.
2. تقليص مخزن الأحداث إلى أحدث حدث واحد مع DROP_OLDEST لمنع تراكم أحداث قديمة أثناء السحب السريع.
3. تسريع TimingPolicy في HYPER / ADAPTIVE / SMART بدون تغيير عدادات Strict End Proof.
4. تقليل settle الثابت بعد UI event في الاستخراج من 24ms إلى 10ms، وداخل burst من 16ms إلى 8ms.
5. عدم إعادة تشغيل واتساب إذا كانت النسخة المطلوبة ظاهرة بالفعل.
6. قفل مزامنة القروبات بنفس RuntimeOperationCoordinator لمنع التداخل مع Scan/Publish.
7. إضافة Batch Scan Import داخل SQLite transaction واحدة.
8. إضافة WAL tuning: synchronous=NORMAL، temp_store=MEMORY، busy_timeout=2500، wal_autocheckpoint=512.
9. تسريع Scan Pro eventWait/settle مع الإبقاء على preview timeout واستقرار التوقيع قبل UNKNOWN.
10. تسريع Publish settle فقط؛ بقيت مهلة الانتقال بين القروبات غير صفرية، وحماية عدم إعادة الإرسال بعد Send محفوظة.
11. تحديث الإصدار إلى versionCode 2100 / versionName 2.10.0.
12. تحديث Workflow وArtifact names للإصدار 2.10.0.

## ما لم يتغير عمدًا

- لا يوجد QuickJoin أو Join Queue.
- Scan لا يضغط Join/Request.
- Strict End Proof لم يتم تعطيله.
- النشر لا يعيد إرسال الرسالة عشوائيًا بعد قبول ضغطة Send.
- لا يوجد تجاوز Knox/MDM أو عزل Android profile.
- لا يوجد تجاوز لقيود WhatsApp أو rate limits.

## التحقق

- `python3 scripts/validate_release.py`
- `bash scripts/run_pure_checks.sh`
- `unzip -t` للحزمة النهائية.
- البناء النهائي Android يبقى عبر GitHub Actions/Android Studio لأن بيئة التسليم لا تحتوي Android SDK كاملًا.
