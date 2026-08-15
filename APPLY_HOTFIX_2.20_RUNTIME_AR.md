# AL-thmany v2.20.0 Runtime Wiring Hotfix

يكمل الربط الناقص بعد تحديث v2.20.0:
- MainActivity.kt: Bottom Navigation + WorkspaceGlobalMiniBar wiring.
- AppViewModel.kt: pauseActiveOperation / resumeActiveOperation / stopAllOperations.
- SmartWorkspaceScreens.kt: الواجهة المصغرة وأزرار التحكم العامة.
- ShizukuUiRuntime.kt: openArchived ومسار مزامنة المؤرشفة.

طبّق الملفات فوق جذر المستودع ثم شغّل:
`python3 scripts/validate_release.py`
