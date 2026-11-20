# DECISIONS.md — Architecture Decision Record Log
(GEN-CONST-3)

فرمت ثبت تصمیمات معماری:
`ADR-ID | Status [ACCEPTED | SUPERSEDED | REJECTED] | Supersedes: <ADR-ID>? | Description`

---

### ثبت تصمیمات بنیادین:

- **ADR-001** | **ACCEPTED** | Supersedes: None
  - **موضوع:** مدل زبانی واحد محلی (Single Local LLM).
  - **شرح:** استفاده‌ی انحصاری از Qwen3-4B با کوانتیزاسیون Q4_K_M برای اجرا در لبه، و رد کامل Dual-Model Routing یا بارگذاری هم‌زمان دو مدل به دلیل محدودیت بودجه حافظه (۸ گیگابایت).

- **ADR-002** | **ACCEPTED** | Supersedes: None
  - **موضوع:** معماری یادگیری صرفاً مبتنی بر بازیابی (Retrieval-based Learning).
  - **شرح:** حذف هرگونه Fine-tuning روی دستگاه به علت هزینه‌های حرارتی، انرژی و پردازش. تمام انطباق‌ها از طریق حافظه محلی (Memory/Prompt) صورت می‌پذیرد.

- **ADR-003** | **ACCEPTED** | Supersedes: None
  - **موضوع:** بودجه‌ی سه‌فراخوانی در هر نوبت شناختی (Max 3 Calls per Turn).
  - **شرح:** هر نوبت استدلال شامل حداکثر ۳ فراخوانی مقید است: فراخوانی شناختی ساختاریافته (Cognitive Call)، فراخوانی تولید پاسخ (Response Call)، و فراخوانی مشروط نقاد (Critic Call).

- **ADR-004** | **ACCEPTED** | Supersedes: None
  - **موضوع:** زیرساخت ذخیره‌سازی و بازیابی وکتوری.
  - **شرح:** پایگاه داده‌ی SQLite محلی + الگوریتم Brute-force Cosine Similarity + رمزنگاری AES-256 با Android Keystore. رد به‌کارگیری پایگاه‌های داده وکتوری بیرونی (مانند Qdrant/Milvus).

- **ADR-005** | **ACCEPTED** | Supersedes: None
  - **موضوع:** معماری گارد ایمنی مستقل (Independent Safety Guard).
  - **شرح:** گارد ایمنی در ماژول تفکیک‌شده‌ی `:safety` با دسترسی‌های مقید پیاده‌سازی شده و سطوح ریسک ۱، ۲ و ۳ برای تمامی ابزارها اجباری است. مسیرهای خودکار فاقد هرگونه دسترسی برای کاهش سطح ریسک هستند.

- **ADR-006** | **ACCEPTED** | Supersedes: None
  - **موضوع:** تزریق وابستگی استاندارد.
  - **شرح:** استفاده از Hilt (استاندارد رسمی اندروید) به‌جای Service Container دست‌ساز.

- **ADR-007** | **ACCEPTED** | Supersedes: None
  - **موضوع:** اتوبوس پیام‌رسانی درون‌فرآیندی.
  - **شرح:** استفاده از Kotlin Coroutines Flows/Channels استاندارد به‌جای پیاده‌سازی Message Busهای پیچیده یا پروتکل‌های توزیع‌شده بین‌نودی.

- **ADR-008** | **ACCEPTED** | Supersedes: None
  - **موضوع:** کنارگذاری آگاهانه‌ی فازهای XIV و XV.
  - **شرح:** عدم پیاده‌سازی فازهای توزیع‌شده‌ی Peer-to-Peer و اکوسیستم چندعاملی بیرونی به دلیل عدم نیاز برای یک دستیار تک‌کاربره.

- **ADR-009** | **ACCEPTED** | Supersedes: None | Date: 2026-10-20 | Scope: فقط debug، تصمیم کاربر
  - **موضوع:** اجازه‌ی گرفتن اسکرین‌شات در build دیباگ.
  - **شرح:** در build دیباگ `FLAG_SECURE` ست نمی‌شود؛ build ریلیز همچنان آن را اعمال می‌کند. دامنه فقط debug است و هیچ invariantی تغییر نمی‌کند.

- **ADR-010** | **ACCEPTED** | Supersedes: None | Date: 2026-11-20 | Scope: local chat (H6 follow-up)
  - **موضوع:** سیاست non-thinking برای Qwen3 محلی و تفکیک واقعی thinking از پاسخ.
  - **شرح:** سیستم‌پرامپت چت محلی اکنون شامل `/no_think` است و به مدل دستور می‌دهد پاسخ را فقط در همان زبان کاربر (فارسی روان در صورت پیام فارسی) بدهد و استدلال داخلی را فاش نکند. نشانگرهای `<think>...</think>` روی همه‌ی snapshotهای تجمعی و متن نهایی با `LocalChatThinkParser` حذف می‌شوند: پاسخ کاربر همیشه فاقد نشانگر است و thinking در `LocalChatMessage.thinkingContent` و `LocalChatMetrics.thinkingTokens` نگه‌داری می‌شود. بودجه‌ی thinking (۱۲۸ توکن) یک رزرو حسابداری در `maxOutputTokens` است، نه توقف سخت native؛ سقف سخت همان `maxOutputTokens` (در پیش‌فرض ۶۴۲ = ۱۲۸ + ۵۱۲ + ۲ رزرو نشانگر) است. sampling پیش‌فرض از راهنمای non-thinking کارت رسمی Qwen3-4B پیروی می‌کند: temperature=0.7، top_p=0.8، top_k=20، min_p=0، repeat_penalty=1.0، presence_penalty=1.5، seed تصادفی (`-1` ⇒ `LLAMA_DEFAULT_SEED`). زنجیره‌ی sampler در JNI اکنون penalties → top-k → top-p → min-p → temperature → dist است. `LocalChatMetrics` شامل `finishReason` (نگاشت از stop/max_tokens بومی) و `thinkingTokens` شمارش‌شده در JNI است. تنظیمات sampling کاربر در `LocalChatGenerationSettingsStore` (SharedPreferences خصوصی) persist می‌شود؛ اکنون هیچ UI ویرایشگری برای آن وجود ندارد. شکست نسل با پاسخ خالی به‌عنوان `GENERATION_FAILED` ثبت می‌شود. تأیید native/JNI فقط در CI انجام می‌شود.

- **ADR-011** | **ACCEPTED** | Supersedes: None | Date: 2026-11-20 | Scope: memory/diagnostics
  - **موضوع:** IV تولیدی Keystore برای AES-256-GCM و چک self-check صادقانه.
  - **شرح:** مسیر `AesGcmCipher.encryptBytes` از `cipher.iv` تولیدشده توسط Android Keystore استفاده می‌کند (`cipher.init` بدون `GCMParameterSpec`)؛ منبع محتمل `InvalidAlgorithmParameterException` حذف شد. مسیر decrypt همچنان `GCMParameterSpec` را با IV ذخیره‌شده می‌سازد. چک AES-GCM در DiagnosticsActivity اکنون علاوه بر round-trip، alias جداگانه‌ی Keystore، الگوریتم کلید، payload دوگانه با IVهای متفاوت و اندازه‌ی minimal payload را بررسی می‌کند. مقادیر thinking/sampling (اعداد و enum، بدون محتوای کاربر) در `CHAT_SEND_FINISHED` ثبت می‌شوند.
