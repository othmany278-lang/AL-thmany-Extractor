# Architecture v2

```text
Compose UI
  │
  ├── AppViewModel
  │     └── ExtractorRepository
  │            └── SQLiteOpenHelper
  │                 ├── target_groups
  │                 ├── extracted_links
  │                 ├── extraction_checkpoints
  │                 └── extraction_logs
  │
  └── ExtractionController
        ├── ExtractionSettingsStore
        ├── ExtractionStateStore
        ├── ExtractionPolicy
        ├── EndProofTracker
        ├── WhatsAppUiAdapter
        │      └── AccessibilityNodeInfo tree
        ├── WhatsAppAccessibilityService
        │      └── ACTION_SCROLL + GestureDescription
        └── ExtractionNotifier
```

## Deep flow

```text
SEARCH_GROUP
 -> OPEN_GROUP
 -> VERIFY_HEADER_IDENTITY
 -> [auto-discovered? VERIFY_GROUP_INFO]
 -> CAPTURE_VISIBLE_URLS
 -> [OLDER_LOADER? CLICK + CAPTURE_BURST]
 -> SCROLL_OLDER
 -> CAPTURE_ON_EVERY_UI_MUTATION
 -> SNAPSHOT + END_EVIDENCE
 -> CHECKPOINT
 -> STRICT_END_PROOF x2
 -> COMPLETE
 -> NEXT_GROUP IMMEDIATELY
```

## Failure containment

- فشل نهاية غير مؤكدة: نفس القروب، لا زيادة للمؤشر.
- حتى 3 retries افتراضيًا.
- بعد استنفادها فقط يصبح FAILED_FINAL وينتقل للعنصر التالي.
- الخروج من WhatsApp: auto-recover يعيده للواجهة.
- Pause: يحفظ checkpoint ثم يتوقف عند نقطة آمنة.

## Android boundary

لا توجد قراءة لقاعدة بيانات WhatsApp أو Store داخلي. كل البيانات تأتي من Accessibility tree التي يكشفها WhatsApp في اللحظة الحالية.
