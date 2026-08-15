# تطبيق تحديث v2.20.0

فك ملف `AL-thmany-v2.20.0-Repository-Update.zip` في جذر المستودع مع استبدال الملفات المتطابقة، ثم شغّل:

```bash
python3 scripts/validate_release.py
bash scripts/run_pure_checks.sh
git status --short
git add .
git commit -m "AL-thmany v2.20.0 Final Runtime Fix"
git push origin main
```

بعدها استخدم GitHub Actions لبناء APK واختبر المزامنة والاستخراج والفحص+الانضمام والنشر على الجهاز.
