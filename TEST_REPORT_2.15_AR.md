# تقرير اختبار AL-thmany Extractor 2.15.0 Hybrid Profiles

## اختبارات منفذة محليًا
- Source contract validator: PASS.
- Pure extraction/profile checks: PASS.
- Pure scanner checks: PASS.
- Pure publisher checks: PASS.
- Runtime coordinator checks: PASS.
- Profile/Dual matcher checks: PASS.
- فحص Kotlin syntax-like diagnostics للملفات المعدلة: لم تظهر diagnostics من نوع expecting/unclosed/illegal escape.

## ما يحتاج اختبار جهاز/CI
لا يتوفر Android SDK/Gradle كامل في بيئة التسليم الحالية، لذلك compileDebugKotlin / lint / assembleDebug النهائي يجب أن ينفذ عبر GitHub Actions الموجود داخل المشروع.

يجب اختبار runtime على جهاز فعلي لكل بيئة مستهدفة: Personal، Work Profile، Secure Folder، وClone/Dual. Shizuku يجب أن ينجح في probe لرؤية واجهة واتساب قبل اعتباره Backend صالحًا؛ وجود Shizuku وحده لا يعني إمكانية عبور Knox/DPC.
