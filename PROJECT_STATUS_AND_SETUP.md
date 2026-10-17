# مستند جامع وضعیت پروژه و راهنمای نصب و راه‌اندازی (Project Status & Setup Guide)

> **تاریخ گزارش:** ۱۶ اکتبر ۲۰۲۶
> **مرجع نقشه‌راه:** `MASTER-ROADMAP-v4-FINAL.md` در workspace فعلی موجود نیست؛ مواردی که بدون آن قابل اثبات نیستند با برچسب «اثبات‌نشده»/باز نگه داشته می‌شوند.
> **وضعیت Git جاری:** شاخه‌ی فعلی `checkpoint/phases-a-to-e` و `HEAD=01773bb642583072b2c46204fc52aca94cdf249d` است. شاخه/commit هدف `checkpoint/phases-a-to-f`/`3fae3a3` در refs یا object database این clone نیست؛ bundle کامل `/home/user/Agent-all-branches-2026-10-13.bundle` نیز فقط refs هم‌ارز با `01773bb` را دارد. تغییرهای قبلی A–F همچنان stage‌شده‌اند؛ تغییرهای G و H در working tree به‌صورت unstaged/untracked/modified باقی‌اند. در این نوبت هیچ branch، index، commit، push یا Git config تغییر نکرده است؛ تغییرهای جدید stage نشده‌اند و commit تا تعیین base معتبر انجام نمی‌شود.
> **وضعیت Phase G:** GEN-SEC-2، GEN-SEC-3، GEN-579/580 و GEN-EDGE-1 را کامل نمی‌خوانیم؛ پیاده‌سازی/آزمون JVM فاز G انجام شده، اما تأیید روی دستگاه و Android Keystore واقعی باز است. Panic Reset از UI محافظت‌شده درخواست می‌شود؛ منطق موجود reset در این turn تغییر نکرده است.
> **آخرین verification کامل:** پس از Phase H، `:app:testDebugUnitTest` = ۱۵۶ تست، `:safety:test` = ۱۷؛ مجموع ۱۷۳، صفر failure/error/skipped. نسبت به baseline پیش از H (app=۱۴۸, safety=۱۷)، هشت JVM test جدید افزوده شده است؛ هیچ تستی حذف یا disabled نشده است. `localVerify` موفق: ۴۴ actionable tasks (۷ اجرا، ۳۷ up-to-date). `:app:verifySafetyIntegrity --rerun-tasks` جداگانه موفق (۱ task). `:app:lintDebug --rerun-tasks` موفق: ۳۳/۳۳ task، ۰ error، ۱۲ warning. گزارش شمارش JUnit `native/verify-jvm-test-counts.py` نیز با همین XMLها موفق شد. جزئیات و محدودیت‌های Phase H در بخش انتهایی آمده‌اند.
> **Device/native verification:** JUnit/Robolectric/JVM شواهد device verification نیستند. Instrumented Android tests، اجرای llama.cpp/JNI، import واقعی GGUF، inference روی گوشی و benchmark دستگاه اجرا نشده‌اند. Native build و APK packaging فقط در GitHub Actions تعریف شده‌اند؛ workflow این clone اجرا نشده، remote در Git تنظیم نیست و `.so`/APK تولید یا upload نشده‌اند.

---

## ۱. جمع‌بندی وضعیت پروژه

### الف) فهرست ۱۳ گام/فاز گزارش‌شده (فاز 0 تا فاز XVI؛ وضعیت جاری هر مورد در بخش‌های زیر)

1. **گام ۱ — زیرساخت ساخت و قراردادهای پایه (Contract Freeze v1.0 & Infrastructure):**  
   ایجاد ساختار چندماژوله Gradlew با دو ماژول مستقل `:app` و `:safety`، قفل و تثبیت کلیه قراردادهای ۶‌گانه داده‌ای به صورت قطعی و آزمون‌های سریال‌سازی KotlinX.
2. **گام ۲ — استقلال ماژول ایمنی و بستن رخنه‌های پیکربندی (Phase IX Safety Guard & Gap Closure):**  
   پیاده‌سازی `SafetyGuard` مستقل و غیرقابل دورزدن، ذخیره‌ساز یکنواخت `SafetyInternalStore` با Checksum مستقل، نیازمندی توکن کاربر برای تغییر سقف‌ها و رد هرگونه تلاش برای کاهش سطح ریسک.
3. **گام ۳ — هسته ران‌تایم و سیستم عصبی درون‌فرآیندی (Phase 0 & VI Runtime Infrastructure):**  
   پیاده‌سازی چرخه بوت، تولید هویت رمزنگاری‌شده، مدل خطای استاندارد و گذرگاه پیام درون‌فرآیندی بدون وابستگی به لایه‌های UI (`NervousSystem`).
4. **گام ۴ — لایه حافظه، رمزنگاری دیتابیس و بازیابی شباهت (Phase V Memory Tier):**  
   پیاده‌سازی پایگاه‌داده امن SQLite با الگوریتم سخت‌افزاری AES-256 GCM (`AesGcmCipher`)، مدل حافظه جلسه‌ای فرار در RAM، فرمول بازیابی یکپارچه شباهت کسینوسی ۳ جزئی (`Relevance`, `Recency`, `Importance`) و الگوی مهاجرت امن جداول.
5. **گام ۵ — موتور استدلال شناختی و سقف بودجه توکن (Phase VII Structured Reasoning Engine):**  
   پیاده‌سازی چرخه شناختی تک‌نوبتی، کنترل‌کننده سقف بودجه سخت ۳ فراخوانی LLM در هر نوبت (`TurnBudgetGovernor`)، گرامر خروجی ساختاریافته و ماژول منتقد شرطی (`ConditionalCriticCall`).
6. **گام ۶ — انتزاع مدل، مدیریت حافظه لبه و دانلود امن استاب (Phase VIII Model Abstraction & Edge):**  
   انتزاع `InferenceEngine` محلی، برآورد حافظه KV-Cache، سیاست سهمیه‌بندی بودجه کانتکست (`ContextWindowBudgetPolicy`)، انتخابگر امنیتی سخت‌افزار (`HardwareDelegateSelector` بر پایه برتری ایمنی بر سرعت) و پروتکل اعتبارسنجی هش SHA-256 دانلود استاب مدل.
7. **گام ۷ — امنیت اجرای مهارت‌ها و ابزارها (Phase IX Skill Execution Security):**  
   موتور اجرای مهارت‌ها (`SkillExecutionEngine`) با شبیه‌سازی پیش از اجرا (`ActionSimulator`)، تولید پرامپت دقیق تأیید سطح ۳ (`ConfirmationPromptGenerator`)، محدودکننده نرخ تراکنش ۵ اقدام در ساعت (`ActionRateLimiter`)، الگوی رزرو Idempotency Key و پیش‌بینی مستقیم رکوردهای تجربه.
8. **گام ۸ — خط‌لوله یادگیری مبتنی بر بازیابی (Phase X Retrieval-based Learning System):**  
   کانال دریافت سیگنال‌های رفتاری مستقل از UI (مانند نسبت ویرایش خروجی)، تثبیت رویه‌ای تکرار الگوها در دستورالعمل‌ها، فیلتر فاصله ویرایشی، و پایپ‌لاین به‌روزرسانی تطبیقی اوزان (`GEN-363`).
9. **گام ۹ — تست‌های یکپارچگی گارد امنیتی و رجیستری ابزارها (Phase IX Guard Adversarial Testing):**  
   ایجاد مجموعه آزمون‌های متخاصم هفت‌گانه برای اثبات غیرقابل دورزدن بودن گارد، بازرسی خودکار `verifySkillRegistry` در فرآیند بیلد و نگاشت تمام ابزارها به سطح ریسک.
10. **گام ۱۰ — زیرساخت مشترک مدیریت تغییرات (Shared Change Management Infrastructure):**  
    پیاده‌سازی هر ۸ ماژول مدیریت تغییرات (`GEN-CM-1` تا `GEN-CM-12`): مجموعه آزمون طلایی تفکیک‌شده، سندباکس ایزوله، اعتبارسنجی پیش از تثبیت (`PreCommitValidator`) با الزام عدم رگرسیون، مدیریت بازگشت به عقب (`RollbackManager`)، لاگ‌های تغییرات فقط‌افزودنی با نسخه‌بندی سطری، گاردریل مجاز لیست و قفل ضد تلاطم ۳ بازگشت متوالی.
11. **گام ۱۱ — قابلیت اطمینان، پایش سلامت، SLI/SLO و خودترمیمی (Phase XI Reliability & SRE & GEN-MODE-1):**  
    تعاریف ۳ سطحی SLI/SLO (شامل محاسبه کران پایین ویلسون در سطح اطمینان ۹۵٪)، تجمیع‌گر سلامت، سیاست انجماد تغییرات در زمان اتمام بودجه خطا (`ErrorBudgetController`)، ماشین حالت کارکرد ۶ گانه عامل (`AgentOperatingModeManager`) با اولویت‌دهی به Provider خارجی در وضعیت حرارتی و تزریق خطای ایمن تست.
12. **گام ۱۲ — لایه امنیت، اعتماد و ضدتزریق پرامپت (Phase XIII Security & Trust):**  
    مدل تهدید ۳ محوره، ایزولاسیون کلیدهای API ارائه‌دهندگان در گاوصندوق Keystore، مهار تزریق پرامپت (`GEN-SEC-1`) در لایه سازنده کانتکست با مرزبندی `<untrusted_content>`، قفل سطح برنامه (`AppLockManager`). GEN-SEC-3 Panic Reset در Phase F به AgentContainer و hookهای production سیم‌کشی و در JVM آزموده شده، اما **کامل نیست**؛ جزئیات و محدودیت‌ها در بخش Phase F ثبت شده‌اند.
13. **گام ۱۳ — هماهنگی چندعاملی و هوش مصنوعی بیرونی (Phase XVI Multi-Agent & External AI):**  
    اجرای نوبتی نقش‌های تخصصی (`Concept -> Production -> Refinement`)، طرح چندفرضیه‌ای سبک، مدیر کارهای ناهمگام در پس‌زمینه (`AsyncJobManager`) با اتصال به SLIهای فاز ۱۱، استخر ارائه‌دهندگان رسمی هوش مصنوعی با مدارشکن، فضای کاری مشترک رمزنگاری‌شده (`SharedWorkspaceManager`) و الگوی یادگیری انتخاب ابزار بر پایه کران پایین ویلسون.

---

### ب) وضعیت اعتبارسنجی تست‌ها

- **Baseline پیش از فاز ج:** `:app` = ۹۳ تست، `:safety` = ۱۷ تست.
- **اجرای فاز ج پیش از این اصلاح:** `:app` = ۹۶ تست، `:safety` = ۱۷ تست.
- **اجرای فاز ج (۸ اکتبر ۲۰۲۶):** `:app` = ۱۰۰ تست (+۴ نسبت به ۹۶)، `:safety` = ۱۷ تست (+۰)، مجموع = ۱۱۷؛ JUnit XMLها صفر failure، error و skipped نشان می‌دادند.
- **اجرای موفق قبلی پیش از اصلاحات فعلی (۹ اکتبر ۲۰۲۶):** `:app` = ۱۱۰ تست، `:safety` = ۱۷ تست، ۴۴ actionable tasks؛ در فاصله‌ی قبل از اجرای نهایی زیر یک اجرای کامل به‌علت OOM ناموفق شد، اما آن نتیجه با اجرای نهایی موفق فعلی جایگزین شده است.
- **مرجع بلافاصله پیش از افزودن شش مورد جدید (۱۰ اکتبر):** `:app` = ۱۲۶ و `:safety` = ۱۷؛ صفر failure/error/skipped.
- **اجرای نهایی (۱۱ اکتبر ۲۰۲۶):** اجرای جداگانه‌ی `:app:testDebugUnitTest` = ۱۳۲ و `:safety:test` = ۱۷؛ مجموع = ۱۴۹، در هر دو ماژول صفر failure/error/skipped. اختلاف app نسبت به مرجع بلافاصله قبل +۶ است؛ شمار safety ثابت است.
- **شش تست افزوده نسبت به مرجع ۱۲۶:** `inferenceDiagnosticsNeverPersistMarkedPromptContextOrResponseText`, `rollingWarningsAcrossSessionsLockOnlyAtProvisionalThreshold`, `transientWarningsExpireOutsideRollingWindow`, `hardLockReopensOnlyForQualifyingPostWaitProfileAndRestartsCanary`, `freshProfileUnlockIsRecordedWithCurrentGpuStateAndUnlockCondition`, و `canaryMemoryPressureAlsoWarnsWithoutImmediateDurableLock` (همگی در `HardwareDelegateSafetyTest`). تست marker تشخیصی از منطق تولیدی استفاده می‌کند و برای افزودنش منطق production تغییر نکرد.
- **ناوردایی‌های انتخاب CPU/GPU:** `HardwareDelegateSafetyTest.invariantCpuUntilFreshSafeStableProfileAndGpuDisabledIsFingerprintScoped` وجودنداشتن profile را همراه با نبود latch صریحاً CPU می‌سنجد و نتیجه‌ی `UNSTABLE`/profile نامعتبر را نیز با safety store بدون latch به CPU می‌بندد. `PhaseVIIIModelAbstractionTest.testHardwareDelegateSelectionAndCaching` نیز null/unknown profile و sample count ناکافی را CPU می‌کند. هر دو مورد از قبل موجود بودند؛ این نوبت فقط assertion «بدون GPU_DISABLED» را به تست invariant موجود اضافه کرد، تست تازه‌ای ساخته نشد.
- **حذف/نادیده‌گرفتن تست:** هیچ آزمونی حذف یا disabled نشده است؛ در diff آزمون‌های tracked هیچ `@Test` حذف نشد، جست‌وجوی `@Ignore`/`@Disabled`/`Assume` در source tests موردی نداشت و XMLهای نهایی `skipped=0` دارند. assertion timestamp اخطار نیز در مسیر canary اجرا شد. آزمون‌های screen-on مطابق تصمیم صریح ثبت و رد نمی‌کنند.
- **Task غیرآزمونی skipped:** `:app:preDebugAndroidTestBuild SKIPPED` مربوط به چرخه‌ی AndroidTest است، نه تست JUnit؛ AndroidTest instrumented روی دستگاه اجرا نشد.
- **اعتبارسنجی تاریخی ۱۱ اکتبر (پیش از follow-up):** پس از دو تلاش سرد ناموفق (GC thrashing در `:app:lintAnalyzeDebug`؛ سپس kernel global OOM با daemon PID 2475 حین شروع آزمون app)، `localVerify` با init script موقت موفق شد: `BUILD SUCCESSFUL in 1m 23s`; ۴۴ task (۱۰ اجرا، ۳۴ up-to-date), شامل app=۱۳۲ و safety=۱۷، `verifySafetyIntegrity` و `verifySkillRegistry`. Lint جداگانه نیز ۳۳/۳۳ task، صفر error و ۱۵ warning داشت. این نتیجه‌ی تاریخی با verification follow-up جدیدتر در بخش Phase F superseded شده است؛ init script موقت پاک شد.
- **مرزبندی شبیه‌سازی:** persistence/store recreation و Worker در Robolectric/JVM آزموده شدند؛ این‌ها process restart واقعی Android یا اجرای سخت‌افزار/GPU نیستند. Caveatهای idle همچنان برقرارند.
- **اعتبارسنجی اصلی Phase F (۱۱ اکتبر ۲۰۲۶؛ تاریخی):** `localVerify` موفق شد (`BUILD SUCCESSFUL`, ۴۴ actionable tasks: ۱۰ executed / ۳۴ up-to-date); XML JUnit: `:app`=۱۳۵ و `:safety`=۱۷، مجموع=۱۵۲، صفر failure/error/skipped. نسبت به baseline پیش از Phase F (`app=۱۳۲`, `safety=۱۷`) سه تست اصلی Phase F اضافه شد. Lint جداگانه با `:app:lintDebug --rerun-tasks` موفق شد: ۳۳ task، صفر error، ۱۵ warning (۸ `GradleDependency`, ۳ `SetTextI18n`, ۱ `MissingApplicationIcon`, ۱ `DataExtractionRules`, ۲ `ApplySharedPref`). `verifySafetyIntegrity` و `verifySkillRegistry` سبز ماندند. این خروجی، baseline follow-up زیر را جایگزین نمی‌کند.
- **Follow-up fault-injection (۱۲ اکتبر ۲۰۲۶):** پنج تست جدید در `AgentContainerPanicResetTest` افزوده شد؛ فهرست نام‌ها و شرح در همین بخش آمده است. `localVerify` موفق شد: ۴۴ actionable tasks (۱۸ executed / ۲۶ up-to-date)، XML JUnit: app=۱۴۰ (+۵ نسبت به ۱۳۵)، safety=۱۷، مجموع=۱۵۷، failure=0، error=0، skipped=0. هیچ تست موجودی در follow-up حذف/disabled نشد؛ جست‌وجوی `@Ignore`, `@Disabled`, `Assume` در source tests موردی نداشت. Lint جداگانه با `:app:lintDebug --rerun-tasks` موفق شد: ۳۳/۳۳ task، صفر error، ۱۵ warning (۸ `GradleDependency`, ۳ `SetTextI18n`, ۲ `ApplySharedPref`, ۱ `DataExtractionRules`, ۱ `MissingApplicationIcon`). `verifySafetyIntegrity` در `localVerify` سبز ماند. `:app:preDebugAndroidTestBuild SKIPPED` یک task چرخه AndroidTest است؛ JUnit skipped=0 و تست instrumented واقعی اجرا نشد. اجرای سرد نخست با Gradle metaspace=۳۸۴m به GC thrashing خورد؛ همان `localVerify` با metaspace=۵۱۲m موفق شد.


- **Phase G full verification (۱۳ اکتبر ۲۰۲۶; نتیجه‌ی جاری):** app=۱۴۸ (+۸ نسبت به app=۱۴۰)، safety=۱۷ (+۰)، مجموع=۱۶۵؛ ۰ failure، ۰ error، ۰ skipped. هیچ test methodی حذف/disabled نشد؛ هشت مورد App Lock/credential/recovery در `AgentContainerAppLockIntegrationTest` موجودند. `localVerify`=۴۴ actionable tasks (۱۲ executed/۳۲ up-to-date); `:app:verifySafetyIntegrity --rerun-tasks` جداگانه نیز اجرا شد و موفق بود. Lint مستقل `--rerun-tasks`=۳۳/۳۳ executed، ۰ error، ۱۱ warning (۸ `GradleDependency`, ۱ `Overdraw`, ۲ `UnusedResources`). `:app:preDebugAndroidTestBuild SKIPPED` مربوط به task AndroidTest است، نه JUnit؛ device/instrumented verification انجام نشد.

---

### خلاصه‌ی پیاده‌سازی و اعتبارسنجی فاز ج (Phase G implementation/JVM tested؛ device verification باز)

- ciphertext کلیدهای Provider در جدول Room `provider_secrets` (BLOB) ذخیره می‌شود؛ Migration افزایشی `2 → 3` جدول را اضافه می‌کند. فایل‌های schema نسخه‌ی ۲ و ۳ در `app/schemas/persistence.AgentDatabase/2.json` و `3.json` وجود دارند؛ فایل ۳ نسخه‌اش ۳ است و entity مربوط به `provider_secrets` را دارد. در snapshot تاریخی فاز ج `git status --short` آن‌ها را به شکل `?? app/schemas/` نشان می‌داد. در clone حاضر hash `8ca150c` در دسترس نیست و هر دو schema در index به‌صورت فایل افزوده stage شده‌اند، نه در commit؛ وضعیت دقیق Git در سرصفحه آمده است.
- تست migration اکنون یک DB واقعیِ Room v2 می‌سازد (DDL و identity hash از schema v2)، با محتوای حافظه و embedding رمز‌شده و رکورد `action_idempotency`؛ پس از مهاجرت، رکوردها قابل‌خواندن‌اند، بایت‌های ciphertext embedding حفظ شده‌اند، و `provider_secrets` خالی و قابل‌استفاده است. تست بررسی خام BLOB در برابر plaintext اضافه نشده و همچنان مورد فاز هـ است.
- هر Provider از کلید AES-256-GCM جداگانه در Android Keystore استفاده می‌کند؛ alias به‌صورت namespaced و مبتنی بر SHA-256 شناسه‌ی Provider مشتق می‌شود. کلید API فقط پس از رمزگشایی به caller بازگردانده می‌شود. `AgentContainer` مدیر production را با کلیددهنده‌ی Android Keystore پیش‌فرض ارائه می‌کند.
- تست‌های failure-injection، رفتار دقیق حذف را پوشش می‌دهند: شکست حذف alias پس از حذف ردیف DB به alias یتیم می‌انجامد؛ شکست حذف ردیف DB پس از آن alias را حذف می‌کند و ردیفِ باقی‌مانده بدون کلید قابل‌خواندن نیست. در هر دو مسیر، `SecretStorageException` عمومی، بدون cause و بدون plaintext گزارش می‌شود.
- `clearAllSecrets()` در شکست یک alias نیز sweep را ادامه می‌دهد؛ کلیدهای Provider دیگر و alias یتیم پاک می‌شوند، سپس خطای عمومی و بدون cause برمی‌گردد. پیاده‌سازی production از قبل این sweep best-effort را داشت؛ قرارداد آن مستند و تست test-only key provider برای تزریق شکست گسترش یافت.
- سایر تست‌ها: بازگشایی فایل Room پس از close، جداسازی و پاک‌سازی کلید Provider، و کنترل عدم نشت به log/exception/`toString`/audit. محدودیت Robolectric API 28 در بخش محدودیت‌ها «پذیرفته‌شده با فرض» است و اثبات رفتار API 34 نیست.

#### وضعیت فعلی ورود کلید GEN-579/580 (این بخش Phase G وضعیت Phase C را به‌روز می‌کند)

- Phase C به‌تنهایی مسیر Settings نداشت. Phase G یک ورودی محافظت‌شده در Settings افزوده که از `AgentContainer.storeProviderCredential()` استفاده می‌کند و مقدار را فقط از مسیر موجود `SecretManager.storeSecret()` ذخیره می‌کند؛ مقدار کلید در UI/audit نمایش داده نمی‌شود و حذف تکی در دسترس است.
- `ProviderCredentialCatalog` شناسه‌های `gemini_free` و `groq_free` را به‌عنوان شناسه‌ی `desc.name` نگه می‌دارد. آزمون هماهنگی جدید، این IDها را با descriptorهای آزمایشی و lookup واقعی `ExternalAiProviderPoolImpl` بررسی می‌کند. در `src/main` فعلاً registration/transport واقعی providerها پیدا نشد؛ این آزمون live network/provider integration را اثبات نمی‌کند.
- GEN-579/580 همچنان «پیاده‌سازی و آزموده‌شده در JVM؛ تأیید روی دستگاه باز» است، نه کامل؛ آزمون ciphertext خام در برابر plaintext و بررسی با Keystore واقعی نیز open می‌مانند.

### خلاصه‌ی فاز د — GEN-EDGE-1 (فاز د بسته شد؛ GEN-EDGE-1 هنوز کامل نیست)

**وضعیت دقیق:** کارهای کد، تست هدفمند و مستندسازی فاز د بسته شدند؛ GEN-EDGE-1 همچنان «سیم‌کشی‌شده و اندازه‌گیری‌نشده» است، نه کامل. ممیزی فاز هـ فقط سندی است و در بخش ممیزی زیر ثبت شده؛ هیچ اجرای دستگاه واقعی، process restart واقعی یا inference runtime واقعی ادعا نمی‌شود.

- `AgentApplication` snapshot فعلی DataStore را به‌صورت غیرهمزمان می‌خواند و سپس `HardwareBenchmarkWorkScheduler` را برای enqueue صدا می‌زند؛ `AppInitializer` measurement/inference اجرا نمی‌کند. Work یکتا بر پایه‌ی fingerprint و `ExistingWorkPolicy.KEEP` است و یک periodic monitor نیز برای starvation صف زمان‌بندی می‌شود.
- WorkManager constraintهای شارژ و idle سیستمی را دارد؛ Worker قبل و بعد از اندازه‌گیری شارژ، حداقل باتری و thermal را دوباره snapshot می‌کند و از gate زمان‌دار idle استفاده می‌کند. **Caveat بی‌کاری حفظ است:** `setRequiresDeviceIdle` معادل دقیق پنج دقیقه inactivity کاربر نیست و عبور آزمون JVM پیوستگی idle واقعی/قابلیت اجرای constraint روی دستگاه را ثابت نمی‌کند.
- **تصمیم صریح صفحه‌خاموش:** شرط interactive از Worker حذف شده است؛ صفحه فقط در snapshotهای قبل/بعد ثبت می‌شود. همه‌ی event/resultها label دقیق `BACKGROUND_SCREEN_OFF` دارند، حتی اگر مقدار `screenInteractive` در همان snapshot `true` باشد؛ بنابراین label به‌تنهایی ادعای خاموش‌بودن واقعی صفحه نیست. این یک انحراف آگاهانه از شرط صفحه‌روشن GEN-PERF-1 است و هیچ تطبیق روش‌شناختی با GEN-PERF-1 ادعا نمی‌شود.
- مقدار واقعی شارژ، درصد باتری، وضعیت screen، idle و thermal در snapshot ذخیره می‌شود. در API 28 thermal برابر `UNKNOWN` است و fail-closed می‌شود؛ API 29+ نیز وضعیت `SEVERE` یا بالاتر را رد می‌کند. تغییر thermal/شرایط ناایمن پس از measurement مانع ذخیره‌ی profile می‌شود. این آستانه‌های انتخابی `PROVISIONAL` هستند.
- fingerprint شامل `device`, `os_build`, `model_sha256`, `runtime_version` است. نتیجه‌ی کامل در DataStore Preferences (`hardware_delegate_profiles`) با کلید وابسته به fingerprint ذخیره و هنگام startup preload می‌شود؛ هیچ Room migration/schema تازه‌ای برای این کار لازم نشده است.
- GPU فقط برای نتیجه‌ی `MEASURED` با fingerprint دقیق، label و snapshotهای سازگار، profile کامل، آمار پایدار، شرایط امن و freshness معتبر انتخاب می‌شود. `UNSTABLE`, `NOT_MEASURED`, `MODEL_UNAVAILABLE`, داده‌ی ناقص/منقضی و headroom ناکافی همگی CPU می‌مانند. حاشیه‌ی watermark سخت‌گیرانه‌تر از `peak < raw watermark` است؛ اعداد آن در بخش provisional ثبت شده‌اند.
- **تصمیم فعلی canary و قفل GPU (W جدید، جایگزین تصمیم ۱۰ دقیقه‌ای):** `prefillLatencyMs` خروجی delegate با p95 همان prefill profile مقایسه می‌شود. خطای قطعی GPU غیرحافظه‌ای قفل پایدار `GPU_DISABLED` می‌دهد؛ telemetry مفقود در canary نیز fail-closed است و به CPU و قفل پایدار می‌رسد. **فشار حافظه یا تخطی latency به‌تنهایی قفل دائمی نمی‌دهد**: همان engine instance به CPU می‌رود و warning timestampدار ثبت می‌شود. قفل پایدار پس از `N=3` warning در پنجره‌ی rolling `W=24 ساعت` برای fingerprint فعال ساخته می‌شود؛ warning با timestamp دقیقاً در مرز `now-W` منقضی است (`timestamp > cutoff`). `W=24 ساعت` تصمیم تازه‌ای است که مقدار پیشین `10 دقیقه` را جایگزین می‌کند؛ هر دو `N=3` و `W` همچنان `PROVISIONAL - pending GEN-PERF-1` هستند.
- **تعامل دو بازه‌ی ۲۴ساعته:** warning window فقط برای شمارش warningهاست؛ حداقل انتظار بازگشایی profile از `lockedAt + 24h` محاسبه می‌شود و به شروع/پایان W وابسته نیست. پس از قفل، بازشدن همچنان به profile معتبر/تازه‌ای نیاز دارد که خودش در یا پس از `unlockEligibleAt` اندازه‌گیری شده باشد؛ W برابر با wait نه آن را دور می‌زند و نه زمان انتظار را دوبرابر می‌کند. با سیاست فعلی، warning شماره‌ی سوم قفل می‌سازد و پاک‌سازی فهرست warningها best-effort است؛ حتی اگر پاک‌سازی انجام نشود، شمارش فعال از روی cutoff جداگانه محاسبه می‌شود.
- **بودجه‌ی canary و سقف ثبت:** `nextCanaryOrdinal` در SharedPreferences پایدار است و با بازسازی store/engine خودکار reset نمی‌شود؛ پنج ordinal اول برای هر fingerprint تا تغییر fingerprint یا unlock واجدشرایط‌اند، نه پنج بار برای هر process restart. بعد از warning، همان `LocalInferenceEngine` برای آن fingerprint CPU-only می‌شود؛ بنابراین سریع‌ترین مسیر از نشانه‌های گذرا سه warning در سه engine instance جدا، روی سه ordinal اولِ پنج‌تایی و در کمتر از ۲۴ ساعت است. این counter/monitoring scope در این تغییر دست‌کاری نشده؛ اگر پنج ordinal مصرف شوند، این تغییر عددی به‌تنهایی warning جدیدی خارج از canary ایجاد نمی‌کند. Safety store حداکثر `N−1=2` warning record را در کلید `WARNINGS` نگه می‌دارد وقتی هنوز unlocked است؛ warning سوم بلافاصله lock می‌سازد و فهرست را best-effort پاک می‌کند. Diagnostics حلقه‌ی جداگانه‌ی `MAX_EVENTS=64` دارد که سقف کل رویدادهاست، نه سقف warningها.
- قفل پایدار با fingerprint متفاوت reset می‌شود؛ همچنین با profile معتبر/تازه‌ای که **پس از قفل** و پس از حداقل انتظار `24 ساعت` اندازه‌گیری شده باشد قابل بازشدن است. این minimum wait نیز `PROVISIONAL` و منتظر GEN-PERF-1 است. پس از unlock شمارنده‌ی canary از ابتدا شروع می‌شود. خطای persistence در همان فرایند fail-closed به CPU می‌ماند. رویدادهای warning/lock/unlock و گزارش متنی، وضعیت فعلی lock، reason و شرط unlock را در diagnostics typed ثبت می‌کنند.
- memory pressure مسیر جدا دارد: provider اندروید از `ActivityManager.MemoryInfo.lowMemory` استفاده می‌کند؛ signal ناموجود/خطادار fail-closed به CPU می‌رود. خارج از canary، `ResourceExhausted` یا memory signal به CPU/`LOW_MEMORY` می‌رود بدون latch دائمی `GPU_DISABLED`; در canary warning می‌سازد و CPU fallback می‌دهد، اما فقط آستانه‌ی rolling فوق lock پایدار می‌سازد. هیچ Guard یا Self-Evolution path به این مسیر متصل نشده است.
- production measurement adapter هنوز runtime یا مدل واقعی ندارد: نبود model hash/runtime version به `MODEL_UNAVAILABLE` و نبود benchmark adapter به `NOT_MEASURED` می‌انجامد؛ هیچ عددی جعل نمی‌شود. engine production نیز همچنان stub فعلی دارد؛ بنابراین تست‌های executor یکپارچگی منطق delegation/fallback را ثابت می‌کنند، نه اجرای GPU یا inference واقعی.
- diagnostics محلی و typed-only است: ring محدود در SharedPreferences، گزارش فقط‌خواندنی متنی/رویدادی، fingerprint هش‌شده و enum/snapshot/latency؛ بدون prompt، متن inference، کلید، provider، free-form payload یا upload شبکه. API گزارش از UI فراخوانی نشده است. SRE-1 در حال حاضر `InMemorySliEventSink` دارد و دوام/معنای این eventها را فراهم نمی‌کند. `GEN-CM-6 ChangeProvenanceLogger` برای rationale تغییرات است، داده‌ی روایت‌دار را در Room و حافظه‌ی بلندمدت ثبت می‌کند و semantic مناسبی برای diagnostics استنتاج نیست؛ از آن استفاده نشد. `SharedWorkspaceManager.kt` نه sink متریک است و نه به GEN-EDGE متصل. diff واقعی آن فقط provider پیش‌فرض `AesGcmCipher` را از `StandardKeystoreKeyProvider` به `AndroidKeystoreKeyProvider` تغییر می‌دهد؛ منطق ذخیره، بازیابی و purge workspace دست نخورده است. علت commit-level آن Keystore hardening/security-storage است: `SecurityCipher.kt` هم‌زمان provider فرایندی/JVM را با provider واقعی Android Keystore جایگزین می‌کند و `SecretManager.kt` نیز همان production default را دارد؛ بنابراین در برنامه‌ی فازبندی این فایل را در فاز ب (رمزنگاری حافظه/Workspace) بگذارید؛ تغییرش متعلق به `SecretManager` فاز ج نیست، هرچند هر دو از Android Keystore بهره می‌برند. پوشش موجود در `PhaseXviMultiAgentOrchestrationTest.testPanicResetInvokesRealPhaseXVIJobAndWorkspacePurge` با `TestOnlyKeystoreKeyProvider` cipher تزریقی می‌دهد، دو artifact را ذخیره/می‌خواند و پس از Panic Reset خالی‌شدن workspace را assert می‌کند؛ این تست استفاده‌ی `AndroidKeystoreKeyProvider` روی دستگاه واقعی را اثبات نمی‌کند. هیچ تغییر جدیدی به این فایل در جریان همین پیگیری اضافه نشده است.
- **مرز SharedPreferences در برابر DataStore:** profile/result ساختاریافته و keyed-by-fingerprint مرجع در AndroidX Preferences DataStore است؛ `getResult()` آن را async preload می‌کند و `saveResult()` از `DataStore.edit` برای write اتمیک بهره می‌برد. `BenchmarkDiagnosticsStore` حلقه‌ی JSON کوچک و محدود (`MAX_EVENTS=64`) را در SharedPreferences خصوصی نگه می‌دارد؛ `AndroidGpuRuntimeSafetyStore` همان ذخیره‌ی ساده را برای latch، دلیل/زمان قفل، اخطارهای زمان‌دار و شمارنده‌ی canary استفاده می‌کند. `commit()` نتیجه‌ی Boolean لازم برای fail-closed کردن persistence ایمنی را می‌دهد؛ دو هشدار `ApplySharedPref` ثبت شده و در این محدوده تغییری داده نشد.
- **API 28 / Android 9 — بله، این محدودیت در مستند ثبت شده:** `AndroidDeviceConditionSnapshotProvider` زیر API 29 مقدار thermal را `UNKNOWN` برمی‌گرداند؛ شرط benchmark، `UNKNOWN` را fail-closed رد می‌کند و `DevicePerformanceProfile.isUsableFor` نیز فقط NORMAL/LIGHT/MODERATE را می‌پذیرد. در نتیجه API 28 profile واجد شرایط برای فعال‌کردن GPU تولید/قبول نمی‌کند و GPU در Android 9 هرگز انتخاب نمی‌شود. این نتیجه از شروط کد می‌آید؛ ادعای اجرای دستگاه/API واقعی نیست.
- Retry محدود به حداکثر ۳ retry پس از تلاش اولیه است؛ `setBackoffCriteria` صریح روی Work دارای idle-mode استفاده نمی‌شود و `Result.retry()` به backoff پیش‌فرض WorkManager واگذار شده است. cancellation پیش از write نهایی، profile/result ناقص تولید نمی‌کند.
- وابستگی runtime استنتاج اضافه نشده؛ MediaPipe/llama.cpp یا backend واقعی ساخته/نصب نشده است. `abiFilters` فقط از نظر وضعیت موجود بررسی شد و تغییر نکرد. Manifest/app shell و `SecretManager` در تغییرهای تاریخی فازهای الف/ج حاضر بودند؛ این follow-up آن‌ها را ویرایش نکرد. `SafetyGuard` و منطق task `verifySafetyIntegrity` نیز تغییر نکردند. گزارش‌های فاز هـ از schemaهای untracked سخن می‌گفتند؛ در clone حاضر هر دو schema در index stage شده‌اند و هنوز commit نشده‌اند. وضعیت Git جاری در سرصفحه مستند آمده است.

### ج) فهرست صریح مقادیر موقت (`// PROVISIONAL - pending GEN-PERF-1`)

کلیه مقادیر عددی زیر، با استدلال فنی در کد علامت‌گذاری شده و در انتظار اندازه‌گیری مستقیم روی سخت‌افزار فیزیکی ARM64 قرار دارند:

1. **بودجه کانتکست و حاشیه امنیتی (`ContextWindowBudgetPolicy.kt`):**
   - سقف کانتکست محلی: `4096 tokens`
   - حاشیه امنیتی خطای توکنایزر و سربار فریم: `safety_margin = 64 tokens` (نیاز به محاسبه دقیق انحراف توکنایزر BPE محلی در برابر فرمت چت)
2. **برآورد مصرف حافظه KV-Cache (`PhaseVIIIModelAbstractionTest.kt`):**
   - برآورد مصرف به ازای توکن در لایه‌های Q4_K_M: تقریبی `~144 KB/token`
3. **انتخابگر/زمان‌بندی GEN-EDGE-1 (`HardwareBenchmarkProfile.kt`, `HardwareBenchmarkWork.kt`, `BenchmarkDiagnostics.kt`):**
   - زمینه‌ی Prefill: `4096` توکن؛ warm-up: ۲ تا ۳ اجرا؛ نمونه‌ی لازم برای p95: `n >= 20` و برای p99: `n >= 50`.
   - انتظار idle موقت: ۵ دقیقه؛ کف باتری GEN-PERF-1: ۵۰٪؛ retry محدود: حداکثر ۳ بار پس از تلاش اولیه.
   - آمار ثبات: `stdev / p95 <= 0.25`; headroom حافظه‌ی GPU: `peak < 0.80 × LMK watermark`; تازگی profile: ۷ روز.
   - canary: پنج invocation نخست (`K=5`) به‌ازای fingerprint و رد prefill latency بالاتر از `2.0 × p95 prefill`; diagnostics ring حداکثر ۶۴ event و صف‌های fingerprint حداکثر ۱۶ مورد نگه می‌دارند.
   - warningهای موقت canary: `N=3` در پنجره‌ی rolling `W=24 ساعت` برای قفل پایدار؛ حداقل انتظار بازگشایی با profile پساقفل نیز `24 ساعت` است ولی از زمان `lockedAt` جداگانه محاسبه می‌شود. هر سه مقدار با comment دقیق `// PROVISIONAL - pending GEN-PERF-1` در کد هستند؛ تصمیم W=24h مقدار قبلی ۱۰ دقیقه را جایگزین کرد و هنوز معیار تأییدشده‌ی کارایی نیستند.
   - starvation پس از ۷ روز صف بدون نتیجه معتبر گزارش می‌شود؛ سقف تشخیصی تقریبی Worker برابر ۱۰ دقیقه است و timeout واقعی را enforce نمی‌کند. آستانه‌ی thermal پیشنهادی `SEVERE` است؛ `UNKNOWN` نیز fail-closed می‌شود.
   - تمام اعداد و آستانه‌های این بند `PROVISIONAL` هستند؛ در کد با `PROVISIONAL` علامت خورده‌اند و باید در اجرای واقعی پیشنهاد/بازبینی/آزموده شوند. Watermark یا measurement از سخت‌افزار واقعی موجود نیست.
4. **سقف ذخیره‌سازی و بازیابی کاندیداها (`MemoryManager.kt`):**
   - سقف رکورد حافظه بلندمدت پیش از نیاز به پالایش/حذف: `maxRecordCeiling = 20,000 records`
   - تعداد پیش‌فرض کاندیداهای برتر برای رتبه‌بندی مجدد: `topK = 20 candidates`
   - ابعاد بردار تعبیه‌سازی در بستر آزمون: `384 floats`
5. **تشخیص ویرایش کاربر و بازخورد منفی (`FeedbackSignalPipeline.kt`):**
   - آستانه شباهت متنی (نسبت ویرایش لِوِنشتین): `edit_ratio < 0.15` جهت تفکیک پذیرش مستقیم از ویرایش جزئی
6. **آستانه منتقد شرطی (`ConditionalCriticCall.kt`):**
   - آستانه فعال‌سازی بررسی مجدد منتقد: `confidence < 0.6`
7. **آستانه تأخیر چرخه‌های مکالمه در SRE (`SreContracts.kt`):**
   - صدک ۹۵ تأخیر نوبت مکالمه: `Turn Latency p95 <= 30,000 ms` (۳۰ ثانیه بر روی مدل ۴B محلی)
8. **سیاست‌های ارزیابی تغییرات و پایداری (`ChangeManagementContracts.kt` و `ChangeManagementCore.kt`):**
   - تحمل افت کیفیت در Holdout منجمد: `maxPermittedQualityDrop = 0.0f` (پایستگی رگرسیون صفر)
   - قفل ضد تلاطم: `maxConsecutiveRollbacksBeforeLockout = 3`
   - کف‌های اطمینان و تعداد تعاملات شبیه‌سازی (`GEN-CM-12`):
     - به‌روزرسانی وزن‌ها (`WEIGHT`): ۲۰ تعامل سایه / کف اطمینان ۰.۷۰
     - تغییرات ساختار حافظه (`MEMORY`): ۲۵ تعامل سایه / کف اطمینان ۰.۸۰
     - الگوهای پرامپت سیستمی (`PROMPT`): ۳۰ تعامل سایه / کف اطمینان ۰.۹۰
9. **سقف اقدامات حساس سطح ۳ در ساعت (`ActionRateLimiter.kt` و `SafetyInternalStore.kt`):**
   - سقف اقدامات برگشت‌ناپذیر مجاز در یک ساعت: `maxLevel3ActionsPerHour = 5`

---

### اجرای میدانی باقی‌مانده‌ی E0–E4 (ممیزی سندی فاز هـ در بخش بعد انجام شده است)

**نقاط باقیمانده برای adapter واقعی — هدف‌های میدانی‌اند، نه اجرای انجام‌شده:**
1. **E0 / قرارداد adapter:** backend واقعی باید fingerprint مدل/runtime واقعی، warm-up و نمونه‌ها، prefill p50/p95/p99/stdev، decode throughput و peak anonymous memory/watermark را بدون عدد ساختگی گزارش کند؛ خروجی inference واقعی نیز باید `prefillLatencyMs` را به canary بدهد.
2. **E1 / اجرای دستگاه:** نصب/اجرای adapter روی سخت‌افزار ARM64؛ ثبت snapshot واقعی قبل/بعد (از جمله صفحه‌ی واقعی جدا از label، شارژ، battery, idle و thermal) و حفظ caveat idle. هیچ آزمون JVM جای این checkpoint را نمی‌گیرد.
3. **E2 / پذیرش profile:** DataStore باید فقط نتیجه‌ی کامل، پایدار، تازه و منطبق بر fingerprint را نگه دارد؛ نتیجه‌ی ناقص/ناامن/ناپایدار هرگز GPU را فعال نکند. اعداد `PROVISIONAL` باید با داده‌ی واقعی بازبینی شوند، نه خودکار جایگزین.
4. **E3 / ایمنی runtime:** روی دستگاه، GPU delegate failure و canary latency/missing telemetry را تا CPU fallback، latch/counter پس از process restart، پاک‌شدن latch با fingerprint جدید و تفکیک memory-pressure/LOW_MEMORY از GPU-specific disable بررسی کنید. بدون این شواهد GPU safety path تأیید نهایی نیست.
5. **E4 / گزارش نهایی:** ثبت build/task/test/device evidence و caveatها؛ سپس فقط با مجوز مجزای کاربر درباره‌ی runtime dependency یا تغییرات خارج از این scope تصمیم بگیرید.

- اجرای Worker و Constraintهای واقعی WorkManager روی دستگاه Android؛ سنجش واقعی idle/شارژ، صفحه‌روشن (صفحه فقط snapshot است)، سطح باتری و thermal.
- اجرای GEN-PERF-1 و GEN-NET-1، ثبت آمار سخت‌افزار ARM64 و بازبینی مقادیر `PROVISIONAL` فقط با خروجی اندازه‌گیری معتبر.
- پیشنهاد باز برای Panic Reset: retry/cleanup یک alias یتیمِ Keystore پس از شکست حذف؛ در این فاز تغییر رفتار `SecretManager` انجام نشد.
- هزینه‌ی decrypt بردارهای ذخیره‌شده در brute-force cosine و ارزیابی آن با بودجه‌ی GEN-PERF-1.
- تست خام BLOB embedding در برابر plaintext؛ عمداً اضافه نشد.
- **محدودیت JVM/Android Keystore — پذیرفته‌شده با فرض:** persistence روی Robolectric API 28 با `TestOnlyKeystoreKeyProvider` آزموده شده است؛ این مدرک دوام واقعی Keystore، اجرای Worker روی دستگاه، یا رفتار API 34 نیست.

### ممیزی سندی فاز هـ — placeholders، مرز production/test و بدهی‌های باز (۱۱ اکتبر ۲۰۲۶)

دامنه‌ی جست‌وجو: `app/src/main`, `safety/src/main`, آزمون‌ها و build scripts؛ build/cache artifacts کنار گذاشته شدند. در source، hit واژه‌مرزی `TODO/FIXME/XXX/HACK` پیدا نشد و هیچ declaration از نوع `Fake*` یا `Stub*` در `src/main` پیدا نشد؛ چهار کلاس `InMemory*` در production source و placeholderهای صریح زیر ثبت شدند. مرجع `MASTER-ROADMAP-v4-FINAL.md` در workspace حاضر نیست؛ بنابراین موردی که ID آن از کد/اسناد موجود قابل‌تأیید نبود «بدون دسته‌ی roadmap / نیازمند تصمیم» مانده، نه اینکه حدس زده شود. در هر ردیف فقط یک دسته‌ی نیازمندی درج شده است.

#### E0–E4: آنچه JVM ثابت می‌کند و آنچه هنوز باز است

| مسیر | یافته‌ی یک‌خطی | دسته‌ی نیازمندی |
|---|---|---|
| `app/src/main/kotlin/inference/HardwareBenchmarkProfile.kt:105,318–326` | هویت مدل/runtime در production فعلاً نامشخص است و adapter فقط `MODEL_UNAVAILABLE` یا `NOT_MEASURED` برمی‌گرداند؛ عدد benchmark ساختگی تولید نمی‌شود. | E0 |
| `app/src/main/kotlin/runtime/di/AgentContainer.kt:66–100` | wiring واقعی پروفایل/ایمنی دارد اما `LocalInferenceEngine` بدون delegate واقعی و با پاسخ deterministic placeholder ساخته می‌شود؛ prefill telemetry واقعی در دسترس نیست. | E0 |
| `app/src/main/kotlin/reasoning/StructuredGrammarConstraint.kt:9` | GBNF در کد به‌صورت متن/قرارداد است و constrained decoding توسط runtime بومی سیم‌کشی نشده است. | E0 |
| `app/src/main/kotlin/com/mhk2340/agent/AgentApplication.kt` | startup فقط snapshot را async preload و Work را enqueue می‌کند؛ اجرای فیزیکی/ARM64 انجام نشده است. | E1 |
| `app/src/main/kotlin/inference/HardwareBenchmarkWork.kt` | gate شارژ/idle/thermal و snapshotهای قبل/بعد فقط در JVM/Robolectric آزموده شده‌اند؛ رفتار WorkManager واقعی روی دستگاه اثبات نشده است. | E1 |
| `app/src/main/kotlin/inference/HardwareBenchmarkProfile.kt` | DataStore مرجع پایدار است و map داخل `DataStoreDeviceProfileStore` فقط cache انتخاب synchronous است؛ اندازه‌گیری واقعی profile موجود نیست. | E2 |
| `app/src/test/kotlin/inference/HardwareBenchmarkProfilePersistenceTest.kt` | چهار آزمون store recreation/fingerprint را در Robolectric پاس می‌کند، نه بعد از مرگ/راه‌اندازی مجدد process واقعی Android. | E2 |
| `app/src/main/kotlin/inference/GpuRuntimeSafetyStore.kt` | latch/warning/canary در `AndroidGpuRuntimeSafetyStore` پایدارسازی می‌شود؛ شاهد فعلی `SharedPreferences`/Robolectric است، نه Android process restart. | E3 |
| `app/src/test/kotlin/inference/HardwareDelegateSafetyTest.kt` | سیزده تست JVM/Robolectric خطا، telemetry، threshold، انقضای warning، fingerprint و unlock را می‌آزماید؛ اجرای GPU واقعی نیست. | E3 |
| `PROJECT_STATUS_AND_SETUP.md` | نتایج build/test و caveatها ثبت شده‌اند، اما E4 سخت‌افزاری تا افزودن شواهد دستگاه باز می‌ماند. | E4 |

#### مرز fake/in-memory در برابر wiring production

| مسیر | یافته‌ی یک‌خطی | دسته‌ی نیازمندی |
|---|---|---|
| `app/src/main/kotlin/inference/HardwareDelegateSelector.kt:67` | constructor پیش‌فرض به `InMemoryDeviceProfileStore` می‌رود؛ `AgentContainer` در production صریحاً DataStore می‌دهد، ولی caller دیگر می‌تواند ناخواسته profile غیرماندگار بسازد. | E2 |
| `app/src/main/kotlin/inference/HardwareBenchmarkProfile.kt:253–260` | `InMemoryDeviceProfileStore` در `src/main` تعریف شده و فقط test seam است؛ منبع authoritative در production `DataStoreDeviceProfileStore` است. | E2 |
| `app/src/main/kotlin/inference/HardwareDelegateSelector.kt:68` | constructor پیش‌فرض safety store حافظه‌ای است؛ wiring production از `AndroidGpuRuntimeSafetyStore` استفاده می‌کند. | E3 |
| `app/src/main/kotlin/inference/GpuRuntimeSafetyStore.kt:420` | `InMemoryGpuRuntimeSafetyStore` فقط seam آزمون است و بقا پس از process death ندارد. | E3 |
| `app/src/main/kotlin/reliability/HealthAggregator.kt:16` | `InMemorySliEventSink` فقط در call siteهای آزمون دیده شد و sink ماندگار در `AgentContainer` پیدا نشد. | SRE-1 |
| `app/src/main/kotlin/security/SecurityComponents.kt:18` | تنها implementation موجود برای `SecurityEpochStore` در production source حافظه‌ای است و `reset_epoch` را بین processها نگه نمی‌دارد. | Panic Reset |
| `app/src/main/kotlin/security/SecurityComponents.kt:34–83` | PIN hash و lock state در `AppLockManager` فقط فیلدهای instance هستند؛ persistence/قفل ماندگار بعد از process restart اثبات نشده است. | GEN-SEC-2 |
| `app/src/main/kotlin/inference/InferenceEngine.kt:69` | مسیر بدون executor پاسخ deterministic ثابت می‌دهد و مدل/runtime واقعی نیست. | E0 |
| `app/src/main/kotlin/inference/InferenceFallback.kt:36` | `NoMemoryPressureSignal` پیش‌فرض همیشه `false` است؛ `AgentContainer` آن را با provider اندرویدی جایگزین می‌کند. | E3 |
| `app/src/main/kotlin/inference/InferenceEngine.kt:27` | callback پیش‌فرض memory fallback no-op است؛ wiring production آن را به transition حالت LOW_MEMORY متصل می‌کند. | E3 |
| `app/src/main/kotlin/memory/ContextRetrievalPipeline.kt:103–120` | query embedding با hash و بردار مصنوعی ۳۸۴بعدی ساخته می‌شود؛ embedding معنایی مدل واقعی نیست. | GEN-MEM-4 |
| `app/src/main/kotlin/multiagent/ExternalAiProviderPoolImpl.kt:47–94` | pool فقط provider instance تزریق‌شده را انتخاب می‌کند و transport/API واقعی در wiring production پیدا نشد. | GEN-NET-1 |
| `app/src/main/kotlin/inference/ModelDownloadProtocol.kt` | protocol منبع `HttpStreamSource` را از caller می‌گیرد؛ منبع شبکه/دانلود production در app wiring دیده نشد. | GEN-NET-1 |
| `app/src/test/kotlin/testutil/RoomTestEnvironment.kt` | کلیددهنده‌ی `TestOnlyKeystoreKeyProvider` فقط fixture آزمون است و مدرک Android Keystore واقعی نیست. | GEN-579/580 |

#### ممیزی `ConcurrentHashMap` و state درون‌فرایندی در production

| مسیر | یافته‌ی یک‌خطی | دسته‌ی نیازمندی |
|---|---|---|
| `app/src/main/kotlin/learning/ExampleBankConsolidationPolicy.kt:32,34` | raw experience و `patternCounts` تا promotion در collectionهای فرایندی‌اند؛ فقط داده‌ی promotion‌شده به MemoryManager می‌رسد. | GEN-363 |
| `app/src/main/kotlin/inference/InferenceEngine.kt:33` | CPU-only fallback set برای engine/session است و با instance تازه reset می‌شود؛ latch پایدار جداگانه در safety store قرار دارد. | E3 |
| `app/src/main/kotlin/inference/HardwareBenchmarkProfile.kt:254,280` | mapهای in-memory برای test store و snapshot cache هستند؛ map دوم جایگزین DataStore authoritative نیست. | E2 |
| `app/src/main/kotlin/multiagent/AsyncJobManager.kt:52–53` | jobها و صف اجرای آن‌ها در حافظه‌اند و بازیابی بعد از process death مشخص/آزموده نشده است. | GEN-MA-3 |
| `app/src/main/kotlin/multiagent/SharedWorkspaceManager.kt:43` | artifactهای رمز‌شده فقط در mapهای همان process هستند؛ purge تستی، دوام پس از restart را ثابت نمی‌کند. | GEN-MA-8 |
| `app/src/main/kotlin/multiagent/ExternalAiProviderPoolImpl.kt:52–53` | descriptor، instance، usage و circuit state providerها در mapهای حافظه‌ای‌اند. | GEN-NET-1 |
| `app/src/main/kotlin/multiagent/MultiAgentOrchestrator.kt:152` | آمار `ToolApproachLearner` در `ConcurrentHashMap` است و ماندگاری آن مشخص نشده است. | GEN-MA |
| `app/src/main/kotlin/shared/change_management/ChangeManagementCore.kt:78,218,422–423` | isolated/live/backup state و شمارنده‌های rollback در collectionهای حافظه‌ای‌اند؛ دوام مورد انتظار تعیین نشده است. | GEN-CM |
| `safety/src/main/kotlin/safety/SkillRegistry.kt:16` | registered skills در registry فرایندی نگه‌داری می‌شوند؛ reload/persistence این map در این ممیزی اثبات نشد. | Safety registry |
| `safety/src/main/kotlin/safety/SafetyGuard.kt:18` | registry ابزارهای SafetyGuard در حافظه است؛ رفتار reload در restart جداگانه‌ی دستگاهی آزموده نشد. | Safety registry |
| `safety/src/main/kotlin/safety/SkillRiskRegistry.kt:16` | risk registry یک map درون‌فرایندی است؛ در این فاز تغییر Safety/Integrity داده نشد. | Safety registry |

#### ممیزی تاریخی Panic Reset (snapshot پیش از Phase F؛ وضعیت جاری در بخش Phase F آمده است)

**نتیجه‌ی تاریخیِ checkpoint پیش از Phase F:** `DefaultPanicResetPhaseXVIHooks.cancelActiveJobs()` فقط log می‌کرد و `0` برمی‌گرداند؛ هیچ jobی را لغو نمی‌کرد. `DefaultPanicResetPhaseXVIHooks.purgeActiveSharedWorkspace()` فقط یک Boolean محلی را `true` می‌کند و موفقیت برمی‌گرداند؛ به `SharedWorkspaceManager` دسترسی ندارد و artifactی را پاک نمی‌کند. `DefaultConfigStoreResetHook.resetConfigStoreToSeedDefaults()` هم فقط log می‌نویسد و هیچ Config Storeای را تغییر نمی‌دهد. `AgentOperatingModeManager` به‌طور پیش‌فرض `DefaultSecurityLockdownEnforcer` flag-only دارد، و `AgentContainer` فعلی `RealSecurityLockdownEnforcer` را نمی‌سازد/تزریق نمی‌کند. بنابراین از مسیر فعلی container، Panic Reset واقعاً **کلیدهای API، حافظه‌ی Room یا session، Workspace و jobها را پاک/لغو نمی‌کند**. اگر `RealSecurityLockdownEnforcer` مستقلاً با production dependencies ساخته و فراخوانی شود، بدنه‌اش مستقیماً `memoryStorage.clearAll()`, `memoryManager.clearSession()` و `secretManager.clearAllSecrets()` را صدا می‌زند؛ اما hookهای پیش‌فرض آن همچنان Workspace/jobها را پاک/لغو نمی‌کنند و Config reset همچنان log-only است. فقط با تزریق `RealPanicResetPhaseXVIHooks` می‌توان jobهای active در همان `AsyncJobManager` حافظه‌ای را لغو و همان `SharedWorkspaceManager` درون‌فرایندی را purge کرد؛ این به WorkManager/system jobs تعمیم ندارد. تست‌های زیر enforcer/hookهای حقیقی را دستی می‌سازند؛ wiring واقعی container یا Android process restart را اثبات نمی‌کنند.

| مسیر | یافته‌ی یک‌خطی | دسته‌ی نیازمندی |
|---|---|---|
| `app/src/main/kotlin/security/RealSecurityLockdownEnforcer.kt` | مسیر real ترتیب پاک‌سازی حافظه/secret، hookها، epoch، app lock و Genesis record را اجرا می‌کند، اما در wiring فعلی AgentContainer ساخته/تزریق نشده است. | Panic Reset |
| `app/src/main/kotlin/security/PanicResetPhaseXVIHooks.kt` | default hook لغو job مقدار صفر و purge را بدون دسترسی به workspace موفق گزارش می‌کند؛ فقط با hook واقعی معتبر است. | Panic Reset |
| `app/src/main/kotlin/security/ConfigStoreResetHook.kt` | default config hook فقط log می‌نویسد و mutation واقعیِ store را انجام نمی‌دهد. | Panic Reset |
| `app/src/main/kotlin/reliability/OperatingModeManager.kt:72–93` | `AgentOperatingModeManager` به‌طور پیش‌فرض `DefaultSecurityLockdownEnforcer` flag-only را می‌گیرد، نه enforcement واقعی حافظه/شبکه/tool. | Panic Reset |
| `app/src/main/kotlin/runtime/di/AgentContainer.kt` | container فعلی `RealSecurityLockdownEnforcer`, durable epoch store و config reset hook واقعی را wire نمی‌کند. | Panic Reset |
| `app/src/main/kotlin/multiagent/RealPanicResetPhaseXVIHooks.kt` | پیاده‌سازی واقعی hookها `cancelAllActiveJobs` و purge فضای کاری جاری را صدا می‌زند. | Panic Reset |
| `app/src/test/kotlin/security/PhaseXiiiSecuritySystemTest.kt:171,244` | آزمون‌ها wipe/secret/epoch/quota/Genesis و rate rejection را با test seam، in-memory epoch و config spy بررسی می‌کنند. | Panic Reset |
| `app/src/test/kotlin/multiagent/PhaseXviMultiAgentOrchestrationTest.kt:95` | آزمون integration لغو دو job و purge artifact را در همان JVM با key provider تزریقی می‌سنجد؛ restart/Keystore واقعی نیست. | Panic Reset |
| `app/src/main/kotlin/skills/ActionRateLimiter.kt:16` | quota timestampها در `ConcurrentLinkedQueue` است؛ آزمون مصرف quota در یک instance را می‌سنجد، نه دوام یا reset پس از process restart. | Panic Reset |

#### `PROVISIONAL`, تطبیق GEN-PERF-1 و موارد فاقد comment دقیق

- مقادیر canary فعلی `N=3`, `W=24 ساعت` و minimum wait `۲۴ ساعت` در `HardwareBenchmarkProfile.kt:237–239` همگی `PROVISIONAL` و دارای comment دقیق `// PROVISIONAL - pending GEN-PERF-1` هستند. `W=24h` در این نوبت جایگزین تصمیم قبلی `W=10 دقیقه` شد. کد latency را از خروجی delegate می‌خواند، اما مقدارهای آزمون از executor تزریقی می‌آیند و measurement دستگاهی نداریم؛ این‌ها اندازه‌گیری GEN-PERF-1 نیستند.
- comment دقیق روی declarationهای عددی زیر وجود دارد: `HardwareBenchmarkProfile.kt:224–230,237–239` (idle، battery، context، sample/warmup و N/W/wait)، `HardwareBenchmarkWork.kt:27` (retry)، `InferenceEngine.kt:41`, `ContextWindowBudgetPolicy.kt:14,16`, `MemoryManager.kt:30,32`, `Contracts.kt:95`, `FeedbackSignalPipeline.kt:39`, `ConditionalCriticCall.kt:23`, `SreContracts.kt:73`, `shared/change_management/ChangeManagementContracts.kt:43,113–114,128–129`, `shared/change_management/ChangeManagementCore.kt:178–199`, `skills/ActionRateLimiter.kt:12`, safety `SafetyGuard.kt:64` / `SafetyInternalStore.kt:28`, و fixtureهای `app/src/test/kotlin/contracts/InferenceProviderContractTest.kt:22`, `app/src/test/kotlin/inference/PhaseVIIIModelAbstractionTest.kt:29,43`, و `safety/src/test/kotlin/safety/SafetyGuardAdversarialTest.kt:105`.

| مسیر | یافته‌ی یک‌خطی | دسته‌ی نیازمندی |
|---|---|---|
| `app/src/main/kotlin/inference/HardwareBenchmarkProfile.kt:231` | stability ceiling برابر `0.25` با comment `PROVISIONAL` برای GEN-EDGE-1 مشخص شده، اما comment دقیق `// PROVISIONAL - pending GEN-PERF-1` ندارد. | GEN-EDGE-1 |
| `app/src/main/kotlin/inference/HardwareBenchmarkProfile.kt:232,235–236` و `app/src/main/kotlin/inference/HardwareDelegateSelector.kt:51` | headroom `0.80`, canary `K=5` و latency multiplier `2.0` با کلمه‌ی `PROVISIONAL` آمده‌اند، اما declarationها comment دقیق `// PROVISIONAL - pending GEN-PERF-1` ندارند. | GEN-PERF-1 |
| `app/src/main/kotlin/memory/MemoryContracts.kt:32` | dimension حدود `384` فقط در توضیح inline `PROVISIONAL - pending GEN-PERF-1` دارد و comment دقیق در ابتدای comment درج نشده است. | GEN-PERF-1 |
| `app/src/main/kotlin/inference/HardwareBenchmarkProfile.kt:78,233–234` | threshold حرارتی، freshness هفت‌روزه و starvation هفت‌روزه provisional هستند اما comment دقیق GEN-PERF-1 ندارند. | GEN-EDGE-1 |
| `app/src/main/kotlin/inference/BenchmarkDiagnostics.kt:167,170` | سقف ring برابر ۶۴ event و queue map برابر ۱۶ fingerprint provisional است ولی comment دقیق GEN-PERF-1 ندارد. | GEN-EDGE-1 |
| `app/src/main/kotlin/inference/HardwareBenchmarkWork.kt:28,309` | ۱۰ دقیقه فقط diagnostic classification است و timeout را enforce نمی‌کند؛ deviation صفحه‌روشن نیز comment provisional اختصاصی دارد، نه comment دقیق GEN-PERF-1. | GEN-EDGE-1 |
| `app/src/main/kotlin/skills/ActionAuditLog.kt:124` | تأخیر retry برابر `30_000 ms` provisional است ولی interval از roadmap حاضر قابل‌استخراج نیست و دسته‌ی عملکردی قطعی برایش ثبت نشده است. | بدون شناسه‌ی roadmap / نیازمند تصمیم |
| `app/src/main/kotlin/memory/SecurityCipher.kt:142` | IV دوازده‌بایتی و GCM tag صدوبیست‌وهشت‌بیتی provisional هستند؛ roadmap فقط AES-256-GCM را الزام می‌کند و این انتخاب performance-threshold نیست. | Cryptographic choice |
| `app/src/main/kotlin/persistence/AgentDatabase.kt:32` و `app/src/main/kotlin/persistence/DatabaseEntities.kt:96` | نام فایل DB و auxiliary reservation index provisional‌اند؛ schema ۲→۳ و تست‌ها تحت Room هستند. | GEN-579/580 |
| `app/src/main/kotlin/reasoning/StructuredGrammarConstraint.kt:9` | GBNF/runtime integration provisional است و backend native حاضر نیست. | E0 |
| `app/build.gradle.kts:16–17,34,40,53,64,66,68` و `build.gradle.kts:2,5` | versionCode/name، bytecode target و versionهای AGP/KSP/Room/Robolectric/Test Core با rationale compatibility provisional هستند، نه اندازه‌گیری GEN-PERF؛ versionی در این پیگیری عوض نشد. | Build/Android |
| KDoc در `ContextWindowBudgetPolicy.kt:7,9,38`, `FeedbackSignalPipeline.kt:14`, `MemoryManager.kt:23`, `ConditionalCriticCall.kt:14`, `SreContracts.kt:13` و `ChangeManagementContracts.kt:106,108–109,120` | عبارت‌های روایی PROVISIONAL فرم comment دقیق ندارند؛ declarationهای اجراییِ مربوط که بالا فهرست شدند جداگانه بررسی شدند. | GEN-PERF-1 |
| test comments در `PhaseVMemoryTierTest.kt:177`, `persistence/AgentDatabasePersistenceTest.kt:36`, `AgentDatabaseRecoveryTest.kt:14`, `LongTermMemorySchemaTest.kt:14` و `KeystoreSecretManagerPersistenceTest.kt:33` | provisional در این محل‌ها درباره‌ی آزمون/انتخاب Robolectric API 28 است، نه مدرک API 34 یا اندازه‌گیری سخت‌افزار. | GEN-579/580 |

#### GEN-579/580 و بدهی‌های قبلی، از جمله lint

| مسیر | یافته‌ی یک‌خطی | دسته‌ی نیازمندی |
|---|---|---|
| `app/src/main/kotlin/security/SecretManager.kt` و Settings call sites | در ممیزی پیش از Phase G مسیر Settings غایب بود؛ Phase G ورودی امن را از `AgentContainer` اضافه کرد، اما این مورد فقط JVM-tested است و device/provider-network integration باز می‌ماند. | GEN-579/580 |
| `app/src/test/kotlin/security/KeystoreSecretManagerPersistenceTest.kt` | failure paths/clear sweep را پوشش می‌دهد، اما آزمون خام BLOB در برابر plaintext هنوز اضافه نشده است. | GEN-579/580 |
| `app/src/main/kotlin/multiagent/MultiAgentOrchestrator.kt:...` | `ResultAssemblyVerifier` پذیرش «yes» را عمدتاً با طول ≥۱۰ و نبود `REJECTED` می‌سنجد؛ معیار معنایی قوی‌تر در کد حاضر دیده نشد. | GEN-MA |
| `app/build.gradle.kts` | lint فعلی ۸ `GradleDependency` warning دارد (Room 2.6.1 ×3, Work 2.9.0 ×2, DataStore 1.1.1, Test Core 1.6.1, Room testing 2.6.1); version change خارج از scope بود. | Build/Android |
| `app/src/main/kotlin/inference/GpuRuntimeSafetyStore.kt:414–421` | Phase G حفظ `commit()` را مستند کرد و فقط روی `persistWarnings()` افزود `@SuppressLint("ApplySharedPref")`: caller به Boolean همگام نیاز دارد تا شکست ذخیره‌سازی را fail-closed تشخیص دهد؛ `apply()` موفقیت durable را گزارش نمی‌کند. دو lint warning قبلی رفع‌شده‌اند؛ باقی‌ماندن I/O همگام یک trade-off ثبت‌شده است، نه ادعای main-thread safety. | د / E3 |
| `app/src/main/kotlin/com/mhk2340/agent/MainActivity.kt` | سه `SetTextI18n` در lint تاریخیِ پیش از Phase G بودند؛ lint نهایی ۱۳ اکتبر این ID را گزارش نکرد. | Build/Android |
| `app/src/main/AndroidManifest.xml` | `DataExtractionRules` و `MissingApplicationIcon` در lintهای پیشین وجود داشتند؛ Manifest در Phase G صریح شد و lint نهایی هیچ‌یک را گزارش نکرد. | Build/Android |
| `app/src/main/kotlin/multiagent/MultiAgentOrchestrator.kt:127` | compile warning: `originalPrompt` استفاده‌نشده است؛ در scope این ممیزی تغییری نکرد. | GEN-MA |
| `app/src/test/kotlin/multiagent/PhaseXviMultiAgentOrchestrationTest.kt:195` و `app/src/test/kotlin/persistence/AgentDatabasePersistenceTest.kt:45` | دو warning آزمون: `job` استفاده‌نشده و initializer غیرضروری `memoryId`. | Test quality |

خروجی دقیق lint فاز هـ تاریخی بود: `:app:lintDebug --rerun-tasks` با ۱۵ warning (۸ GradleDependency، ۳ SetTextI18n، ۲ ApplySharedPref، یک DataExtractionRules و یک MissingApplicationIcon) موفق شد. Phase G بعداً warningهای هدف UI/Manifest و دو ApplySharedPref را رفع کرد؛ lint جاری ۱۳ اکتبر ۱۱ warning (۸ GradleDependency، ۱ Overdraw، ۲ UnusedResources) و صفر error داشت. `TODO/FIXME/XXX/HACK` در ممیزی فاز هـ صفر بود. E0–E4 میدانی، GEN-PERF-1 و GEN-NET-1 همچنان بازند.

### د) وضعیت فاز XII (Self-Evolution)

- **تأیید صریح:** فاز XII (تکامل خودکار معماری و بهینه‌سازی الگوریتم‌ها) **عمداً و طبق تصمیم مکتوب نقشه راه و درخواست کاربر ساخته نشده است**.
- **دلیل:** طبق توصیه بخش Tier 1 نقشه راه، فعال‌سازی حلقه‌های تکاملی فراتر از یادگیری بازیابی، مستلزم استخراج داده‌های کارکردی پایدار از رفتار کاربر طی چندین ماه استفاده واقعی روی سخت‌افزار نهایی است تا از ایجاد چرخه‌های بازخورد مخرب اجتناب شود.

---

## ۲. راهنمای نصب، راه‌اندازی و اجرای روی سخت‌افزار واقعی

### الف) ساختار ماژول‌ها و پوشه‌های ریپازیتوری

```text
android-agent/
├── app/                                # ماژول اصلی کارکردی عامل
│   ├── src/main/kotlin/
│   │   ├── contracts/                  # قراردادهای داده‌ای منجمد (Contract Freeze v1.0)
│   │   ├── runtime/                    # چرخه حیات، سیستم عصبی، خطایابی
│   │   ├── memory/                     # دیتابیس AES-256، بازیابی کسینوسی و کانتکست
│   │   ├── reasoning/                  # موتور استدلال شناختی، منتقد شرطی، بودجه توکن
│   │   ├── inference/                  # انتزاع موتور مدل، برآورد KV Cache و دانلود استاب
│   │   ├── skills/                     # مهارت‌ها، شبیه‌ساز، نرخ‌سنج و ثبت لاگ
│   │   ├── learning/                   # خط لوله سیگنال‌ها و به‌روزرسانی تطبیقی اوزان
│   │   ├── shared/change_management/   # زیرساخت مشترک مدیریت تغییرات (GEN-CM)
│   │   ├── reliability/                # مانیتورینگ سلامت، SLI/SLO، خودترمیمی و حالت‌ها
│   │   ├── security/                   # رمزنگاری Keystore، ضدتزریق پرامپت، قفل اپ و Panic Reset
│   │   └── multiagent/                 # هماهنگی نقش‌ها، کارهای ناهمگام و استخر هوش مصنوعی بیرونی
│   └── src/test/kotlin/                # آزمون‌های جامع واحد و یکپارچه‌سازی (:app)
├── safety/                             # ماژول مستقل و غیرقابل دورزدن امنیت
│   ├── src/main/kotlin/safety/         # SafetyGuard، رجیستری ابزارها و قوانین سطح ریسک
│   └── src/test/kotlin/safety/         # آزمون‌های متخاصم و صحه‌گذاری یکپارچگی قوانین
├── build.gradle.kts                    # اسکریپت ریشه بیلد Gradle
├── settings.gradle.kts                 # پیکربندی پروژه‌ها و وابستگی‌ها
├── CONSTITUTION.md                     # اصول و قوانین اساسی تغییرناپذیر سیستم
├── INVARIANTS.md                       # ناورداهای معماری و امنیتی
└── PROJECT_STATUS_AND_SETUP.md         # مستند حاضر
```

---

### ب) پیش‌نیازهای محیط توسعه و کامپایل

- **سیستم‌عامل:** Linux / macOS / Windows (با WSL2)
- **محیط توسعه (IDE):** Android Studio Jellyfish (2023.3.1) یا Ladybug (2024.2+)
- **نسخه جاوا (JDK):** OpenJDK 21 (اجباری به دلیل سازگاری با Gradle 8.7 و کامپایلرهای مدرن کاتلین)
- **نسخه Gradle:** Gradle 8.7 (از طریق Gradle Wrapper پروژه: `./gradlew`)
- **نسخه کامپایلر کاتلین:** Kotlin 1.9.22 با افزونه رسمی سریال‌سازی
- **مشخصات هدف اندروید:**
  - `minSdkVersion`: 28 (Android 9.0 Pie جهت پشتیبانی از سخت‌افزار Keystore مدرن و Biometrics)
  - `targetSdkVersion`: 34 (Android 14 جهت تطابق با محدودیت‌های پس‌زمینه و Foreground Service Types)
  - `compileSdkVersion`: 34

---

### ج) مراحل دریافت و باز کردن پروژه در Android Studio

1. کلون یا استخراج محتویات دایرکتوری مخزن به مسیر کاری محلی:
   ```bash
   git clone <REPO_URL> android-agent
   cd android-agent
   ```
2. اطمینان از تنظیم متغیر `JAVA_HOME` روی JDK 21:
   ```bash
   export JAVA_HOME=/path/to/jdk-21
   ```
3. باز کردن Android Studio و انتخاب گزینه **Open**:
   - مسیر پوشه ریشه `android-agent` را انتخاب کنید.
   - اجازه دهید Gradle Sync اولیه تکمیل شده و وابستگی‌ها از مخازن بارگیری شوند.

---

### د) اجرای محلی دستور اعتبارسنجی سلامت (`localVerify`)

این دستور آزمون‌های واحد ماژول‌های `:app` و `:safety`، Lint و تسک‌های صحت‌سنجی محلی را اجرا می‌کند. اجرای تأیید فعلی از JDK 21 در `/home/user/.local/jdk21` و SDK 34 در `/home/user/.local/android-sdk` استفاده کرد؛ این ابزارها خارج از repository هستند و ممکن است پس از بازسازی sandbox باقی نمانند.

```bash
set -euo pipefail
export JAVA_HOME="$HOME/.local/jdk21"
export ANDROID_HOME="$HOME/.local/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
INIT_SCRIPT=$(mktemp /tmp/gradle-test-memory-XXXXXX.gradle)
trap 'rm -f "$INIT_SCRIPT"' EXIT
cat > "$INIT_SCRIPT" <<'EOF'
allprojects {
    tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
        maxHeapSize = "384m"
        jvmArgs "-XX:MaxMetaspaceSize=256m"
    }
}
EOF
./gradlew --no-daemon --console=plain --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.jvmargs='-Xmx256m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8' \
  --init-script "$INIT_SCRIPT" localVerify
```

در اجرای موفق این نوبت، `org.gradle.jvmargs` فقط با `-D` روی command line تنظیم شد؛ محدودیت heap/metaspace تست‌ها فقط در init script موقت `/tmp` بود و `trap` آن را پس از خروج پاک کرد. هیچ Gradle memory setting در repository یا `~/.gradle/gradle.properties` نوشته نشد. `gradle.properties` مخزن فقط `android.useAndroidX=true` دارد و در این نوبت تغییر/مرحله‌بندی نشد.

- **آخرین اجرای موفق `localVerify` پس از تغییر W و assertionهای صریح invariant در ۱۱ اکتبر:** `BUILD SUCCESSFUL in 1m 23s`; ۴۴ actionable tasks (۱۰ اجرا، ۳۴ up-to-date), با JUnit XML `:app`=۱۳۲ و `:safety`=۱۷. دو اجرای موفق قدیمی‌تر همان روز به‌ترتیب ۱۴ اجرا/۳۰ up-to-date و ۵ اجرا/۳۹ up-to-date بودند؛ آن‌ها قبل از این تغییر بودند. در اجرای فعلی JVM memory از command-line و سقف Test worker از init script موقت `/tmp` آمدند؛ script پاک شد و memory config در repository یا `~/.gradle/gradle.properties` نوشته نشد. `gradle.properties` مخزن فقط `android.useAndroidX=true` دارد.

- **آخرین اجرای کامل موفق `localVerify` پیش از افزودن تست تکمیلی:** `BUILD SUCCESSFUL in 2m 32s`; ۴۴ actionable tasks (۱۸ اجرا، ۲۶ up-to-date). JUnit XML: `:app` = ۱۱۰، `:safety` = ۱۷؛ مجموع ۱۲۷، failure/error/skipped = ۰.
- پس از افزودن تست، کلاس `HardwareBenchmarkProfilePersistenceTest` به‌طور هدفمند اجرا شد: `BUILD SUCCESSFUL in 18s`; ۴ تست، صفر failure/error/skipped (۳۰ task؛ ۳ اجرا، ۲۷ up-to-date). شامل `changedFingerprintInvalidatesOldResultAndSchedulesMeasurementForNewFingerprint` و `measuredProfileIsReusedAfterRestartOnlyAfterThePersistentSnapshotIsLoaded` است.
- **تلاش‌های تاریخیِ پیش از این نوبت** برای full build بعد از افزودن تست کامل نشدند: یکی در `:app:lintAnalyzeDebug` با GC thrashing متوقف شد و تلاش بعدی PID `7245` را با `global_oom` در `:app:testDebugUnitTest` از دست داد (RSS=682,400 kB). این نتایج فقط به همان تلاش‌های قدیمی مربوط‌اند و با اجرای موفق `localVerify` همین نوبت، شامل ۱۳۲ تست app و ۱۷ تست safety، جایگزین شده‌اند.
- **خروجی تاریخی lint پیش از بازبینی ۱۱ اکتبر:** اجرای مستقل پس از افزودن تست با `--rerun-tasks :app:lintDebug` موفق شد و هر ۳۳ task را اجرا کرد؛ آن گزارش ۰ خطا و ۱۳ هشدار داشت (۸ `GradleDependency`, ۳ `SetTextI18n`, ۱ `MissingApplicationIcon`, ۱ `DataExtractionRules`). این عدد با گزارش جدیدتر ۱۱ اکتبر در بخش ممیزی جایگزین شده است: ۱۵ هشدار، با افزوده‌شدن ۲ `ApplySharedPref`. XML/HTML تاریخی: `app/build/reports/lint-results-debug.xml` و `.html`. سه dependency فاز د که آن گزارش `GradleDependency` داد: `androidx.work:work-runtime-ktx:2.9.0`، `androidx.datastore:datastore-preferences:1.1.1` و `androidx.work:work-testing:2.9.0`. هشدار Kotlin `originalPrompt` نیز در گزارش‌های build دیده شد؛ هشدار backup با فرض کفایت `allowBackup=false` همان‌جا ثبت شده بود.
- اجرای ۸ اکتبر با heap `128m` و metaspace `192m` موفق بود، اما cold-build این workspace با همان metaspace کافی نبود؛ یک تلاش با `-Xmx256m -XX:MaxMetaspaceSize=192m` در `:safety:compileKotlin` خطای JVM `OutOfMemoryError: Metaspace` داد. اجرای `:app:compileDebugUnitTestKotlin` با `-Xmx256m -XX:MaxMetaspaceSize=512m` موفق شد (`BUILD SUCCESSFUL`, ۲۵ tasks؛ ۴ اجرا و ۲۱ up-to-date).
- **تلاش ناموفق جداگانه‌ی `localVerify` در ۹ اکتبر:** پس از ۳۰ دقیقه tool timeout، خروجی Gradle آخرین task را `:app:kspDebugUnitTestKotlin` نشان می‌داد. Wrapper PID `4215` و daemon PID `4246` هنوز فعال بودند؛ فایل `~/.gradle/daemon/8.7/daemon-4246.out.log` عبارت‌های مکرر `Problem in daemon expiration check` و `java.lang.OutOfMemoryError: Metaspace` داشت. سپس این دو PID دستی با SIGKILL خاتمه داده شدند؛ جست‌وجوی `dmesg` برای این PIDها/رویداد OOM خروجی نداشت. این تلاش موفق محسوب نمی‌شود و به OOM-killer کرنل نسبت داده نشده است؛ اجرای موفق بعدی با metaspace موقت ۵۱۲m انجام شد.
- اجرای مستقیم `--rerun-tasks localVerify` در دو تلاش cold-build قدیمی‌تر کامل نشد: `dmesg -T` برای PID `10279` در ۱۴:۳۲:۳۶ UTC (RSS=919,932 kB) رکورد `global_oom` حین `:app:compileDebugJavaWithJavac` دارد؛ برای PID `13269` در ۱۴:۴۶:۰۸ UTC (RSS=922,180 kB) رکورد متناظر حین `:app:compileDebugKotlin` است. این شواهد فقط به همان تلاش‌ها مربوط‌اند.
- Task غیرJUnit `:app:preDebugAndroidTestBuild SKIPPED` در اجرای نهایی دیده شد؛ JUnit skipped نیست و AndroidTest instrumented روی دستگاه اجرا نشد.

---

### هـ) گام بعدی: اجرای سخت‌افزاری GEN-PERF-1 و GEN-NET-1

در محیط مجازی/sandbox به موبایل فیزیکی، سخت‌افزار ARM64 یا شتاب‌دهنده GPU دسترسی نیست؛ هیچ اجرای Worker، benchmark یا شبکه روی دستگاه انجام نشده است. برای فاز هـ:

1. **دستگاه اندازه‌گیری:** ARM64 فیزیکی؛ کف رم ۸ GB مطابق یادداشت فعلی پروژه، نه اندازه‌گیری این اجرا.
2. **مسیر مجری:** `AppInitializer` تنها enqueue می‌کند؛ `HardwareBenchmarkWorkScheduler` می‌سازد و `HardwareBenchmarkWorker` تحت WorkManager اجرا می‌شود. قبل از اندازه‌گیری واقعی باید شروط/شرایط screen-on در GEN-PERF-1 و charging+idle در GEN-EDGE-1 روی دستگاه بررسی شوند.
3. **روش و خروجی‌های مستقل:** ۲–۳ warm-up؛ حداقل `n >= 20` برای p95 و `n >= 50` برای p99؛ گزارش p50/p95/p99/stdev. Prefill باید با context=4K ثبت شود، Decode token/s پایدار جدا بماند، و Peak Anonymous GPU با LMK watermark مقایسه شود؛ هیچ score میانگین ساخته نشود.
4. **محدودیت scheduling:** WorkManager idle constraint تعریف دقیق پنج دقیقه inactivity نیست؛ Worker یک انتظار ۵ دقیقه‌ای و بازبینی `isDeviceIdleMode` دارد. GEN-PERF-1 صفحه‌ی روشن و باتری ≥۵۰٪ می‌خواهد، اما GEN-EDGE-1 شارژ و idle می‌خواهد؛ کد این‌ها را هم‌ارز فرض نمی‌کند و اگر صفحه خاموش باشد `NOT_MEASURED` ثبت می‌کند.
5. **جایگزینی اعداد:** فقط با نتایج واقعی و قابل‌بازتولید روی دستگاه، profile ذخیره و مقادیر `// PROVISIONAL - pending GEN-PERF-1` بازبینی شوند. برای GEN-NET-1 نیز انتقال/دانلود واقعی و خطاهای شبکه روی دستگاه آزموده شود.

---

### و) وضعیت واقعی وزن مدل، runtime و ABI

- باینری مدل اصلی در repository نیست. `ModelDownloadProtocol` فقط utilityای است که `HttpStreamSource` را از caller می‌گیرد و SHA-256 را بررسی می‌کند؛ منبع شبکه‌ی concrete، ذخیره/نصب مدل و اتصال startup برای دانلود در کد حاضر سیم‌کشی نشده است.
- `LocalInferenceEngine` در صورت نبود backend تزریقی پاسخ deterministic stub می‌دهد؛ runtime استنتاج واقعی در این کد انتخاب یا بسته‌بندی نشده است. وابستگی MediaPipe/llama.cpp اضافه نشد.
- `abiFilters` در `app/build.gradle.kts` وجود ندارد؛ طبق محدوده تغییر داده نشد. اجرای GEN-NET-1 و اجرای واقعی دانلود/استنتاج روی دستگاه به فاز هـ موکول است.

---

### ز) Phase F — GEN-SEC-3 Panic Reset (فقط «سیم‌کشی‌شده و آزموده‌شده در JVM»)

**وضعیت و مرز تغییر:** این فاز GEN-SEC-3 را کامل اعلام نمی‌کند. `AgentContainer` production اکنون `RealSecurityLockdownEnforcer`، `RealPanicResetPhaseXVIHooks`، `AndroidAgentPolicyConfigResetHook`، storeهای مستقل epoch/lock و لغوکننده‌ی واقعی WorkManager را wire می‌کند. `AgentApplication.onCreate()` ابتدا latch پایدار را restore می‌کند و بعد مسیر preload/scheduling benchmark را فراخوانی می‌کند. default no-opهای موجود حفظ شده و با `TEST ONLY` مشخص‌اند؛ `AgentContainer` از آن‌ها استفاده نمی‌کند. در این فاز `SafetyGuard`, `SafetyInternalStore`, task `verifySafetyIntegrity`, Manifest، API/پیاده‌سازی `SecretManager` و نسخه/وابستگی‌های Gradle تغییر نکردند؛ UI ساخته نشد.

**ترتیب reset:**
1. `AgentContainer.executePanicReset(reason)` قفل scheduling benchmark را می‌گیرد؛ enforcer ابتدا latch فرایندی و lockdown را فعال می‌کند، سپس persistence latch را best-effort می‌نویسد.
2. یک واحد `RiskLevel.LEVEL_3` از `ActionRateLimiter.checkAndRecord()` مصرف می‌شود؛ هیچ فراخوانی `clear()` روی GEN-NEW-RATE نداریم. اگر سقف قبلاً پر شده باشد، مرحله‌ی بودجه fail می‌شود؛ cleanupهای بعدی باز هم اجرا می‌شوند و reset در حالت lockdown خطا می‌دهد.
3. هر مرحله جداگانه تلاش می‌شود: پاک‌سازی `long_term_memory`، پاک‌کردن session، `SecretManager.clearAllSecrets()` برای Provider keyها و aliasهایشان، لغو jobهای فعال، لغو benchmark Workهای tagشده و starvation monitor یکتا، purge فضای کاری و reset Config Store به seed. Config Store واقعی فقط SharedPreferences مستقل `agent_policy_config` را پاک می‌کند؛ snapshot پس از آن risk override خالی و سقف Level-3 seed برابر ۵ می‌دهد. سقف rate از همین store خوانده می‌شود؛ map مربوط به risk override در این فاز صرفاً ذخیره/پاک‌سازی می‌شود و به `SafetyGuard` وصل نشده است.
4. `reset_epoch` در SharedPreferences/HMAC store جدا از Config نگه‌داری می‌شود و API فقط `incrementEpoch()` دارد: مقدار بعدی با `Math.addExact(old, 1)` ساخته می‌شود؛ overflow، HMAC نامعتبر یا state ناقص fail-closed است. Config Reset به آن فایل/alias دسترسی ندارد.
5. کلید AES دیتابیس `android_agent_memory_key` بازنشسته می‌شود. این کلید برای ciphertext حافظه بلندمدت و payloadهای AUDIT مشترک است؛ Provider API keyها کلیدهای مجزایی دارند و تنها از API موجود `SecretManager` حذف می‌شوند.
6. پس از در دسترس‌بودن epoch جدید و غیرقابل‌خواندن/حذف‌شدن تاریخچه‌ی قبلی، Genesis مستقیماً در AUDIT نوشته می‌شود: `Reset at T, epoch=N, prior history unreadable`. Genesis به `long_term_memory` projection نمی‌شود.

**چرا ترتیب کلید و Genesis چنین است؟** در مسیر عادی، چرخش/حذف کلید AES قبل از Genesis باعث می‌شود ciphertext قدیمی memory/AUDIT با کلید تازه قابل رمزگشایی نباشد؛ سپس Genesis با کلید تازه رمز می‌شود. اگر بازنشستگی کلید شکست بخورد، fallback حذف ردیف‌های `long_term_memory` و پاک‌سازی تراکنشی AUDIT و idempotency index را امتحان می‌کند؛ Genesis فقط وقتی نوشته می‌شود که هر دو سابقه‌ی لازم واقعاً حذف شده باشند. حتی اگر fallback موفق شود، شکست مرحله‌ی بازنشستگی ثبت می‌ماند و reset به‌عنوان ناقص گزارش می‌شود؛ کلید/تاریخچه‌ی قدیمی با Genesis موفق‌نما پوشانده نمی‌شود.

**Epoch، retry و Work:** کلید تازه قالب canonical `<reset_epoch>:<uuid>` دارد. `SkillExecutionEngine` پیش از رزرو idempotency/اجرای handler و دوباره پیش از bridge، epoch کلید را کنترل می‌کند؛ کلید قدیمی رد می‌شود، نه اینکه handler یا audit reservation را اجرا کند. لغو Work با tag همه‌ی benchmarkهای یکتا را پوشش می‌دهد و starvation monitor هم با tag و نام یکتا cancel می‌شود. `benchmarkSchedulingLock` جلوی enqueue هم‌زمان را می‌گیرد؛ پس از latch، `preloadCurrentHardwareProfile()` و startup restore از schedule مجدد جلوگیری می‌کنند. این بخش فقط Work فعال را لغو می‌کند؛ profile/diagnostics/GPU safety state را پاک نمی‌کند.

**خرابی و lockdown:** هر stage با catch مستقل best-effort است؛ پیام/علت exception اصلی، reason ورودی و plaintext به خطای نهایی وصل یا log نمی‌شود. خطای نهایی `PanicResetIncompleteException` فقط شناسه‌های ثابت stageها را دارد و `cause == null` است. latch فرایندی پیش از اولین عملیات خطاپذیر فعال می‌شود؛ `enforceLockdown(false)` و transition عادی به mode دیگر پس از latch رد می‌شوند. اگر نوشتن latch پایدار fail شود، همین process قفل می‌ماند، اما دوام آن پس از process death تضمین‌شده نیست؛ این محدودیت در بخش ادعاهای اثبات‌نشده هم آمده است.

**مرز Safety — دامنه‌ی آنچه واقعاً آزموده شده:** بررسی کد نشان می‌دهد `AndroidAgentPolicyConfigStore` فقط preference file خودش را می‌خواند/می‌نویسد و `RealSecurityLockdownEnforcer` از `ConfigStoreResetHook` استفاده می‌کند. Konsist rule موجود (`SafetyArchitectureKonsistTest`) دست‌نخورده است و diff آن خالی است؛ این rule فقط نام importها را به‌صورت ایستا می‌خواند و assert می‌کند فایل‌های خارج از package `safety..` هیچ importای با prefix `safety.internal` نداشته باشند. rule نه SafetyGuard عمومی را منع می‌کند و نه call/runtime access را می‌سنجد. تست production-container مقدار عمومی `SafetyGuard.riskRegistry` را پیش و پس از Reset مقایسه می‌کند؛ این فقط ثابت می‌کند آن مقدار در همان آزمون تغییر نکرده، نه اینکه Reset/Config اصلاً به Guard یا `SafetyInternalStore` دست نزده باشند. دسترسی مستقیم آزمون به `safety.internal` برای رعایت مرز حذف شد؛ بنابراین اکنون assertion مستقیمی درباره‌ی state داخلی `SafetyInternalStore` وجود ندارد. `verifySafetyIntegrity` در اجرای build سبز است، اما اجرای آن به‌تنهایی تعاملات runtime یا نبود dependency Reset/Config را ثابت نمی‌کند. هیچ فایل production ماژول `safety/` در Phase F تغییر نکرده است.

**تست‌های production-container و verification نهایی:** تست‌های reset در `PhaseXiiiSecuritySystemTest`, `PhaseXviMultiAgentOrchestrationTest` و `AgentContainerPanicResetTest` از `AgentContainer` Production آغاز می‌شوند؛ برای پنج failure injection جدید از Room triggerها و constructor overrides موجود استفاده شد و production logic/seam تغییر نکرد.

- پنج تست جدید fault-injection: `databaseMemoryAndAuditIdempotencyPurgeFailuresDoNotStopLaterStages`, `databaseKeyRetirementFailureFallsBackToHistoryPurgeAndKeepsLockdown`, `epochWriteFailureStillRunsLaterStagesAndKeepsLockdown`, `genesisAuditWriteFailureLeavesAllEarlierCleanupDoneAndKeepsLockdown`, `persistentLatchWriteFailureStillRunsLaterStagesAndKeepsProcessLocked`. هر تست ادامه‌ی مراحل بعدی، lockdown، stage-only/cause-free و نشت‌ندادن sentinelها، و شکست `enforceLockdown(false)`/transition عادی را می‌سنجد. تمام پنج injection قابل انجام بودند؛ SQLite trigger برای خطاهای DB/Genesis و test overrideها برای Keystore/epoch/latch استفاده شدند.
- Baseline بلافاصله پیش از این follow-up: app=۱۳۵، safety=۱۷ (مجموع=۱۵۲). اجرای نهایی فعلی `localVerify`: app=۱۴۰ (+۵)، safety=۱۷، مجموع=۱۵۷؛ failure=0، error=0، skipped=0. پنج test method بالا اضافه شدند؛ هیچ methodی در follow-up حذف یا disabled نشد و در source tests `@Ignore`, `@Disabled`, `Assume` پیدا نشد.
- `localVerify` موفق شد: ۴۴ actionable tasks (۱۸ اجرا، ۲۶ up-to-date)، شامل `:app:testDebugUnitTest`, `:safety:test`, `:app:verifySafetyIntegrity` و `:app:verifySkillRegistry`. `:app:compileDebugKotlin` و `:app:compileDebugUnitTestKotlin` نیز موفق‌اند. `:app:preDebugAndroidTestBuild SKIPPED` یک task غیرJUnit است؛ JUnit skipped=0 و تست Android instrumented/device اجرا نشد.
- Lint جداگانه با `:app:lintDebug --rerun-tasks` موفق: ۳۳/۳۳ task، صفر error و ۱۵ warning (۸ `GradleDependency`, ۳ `SetTextI18n`, ۲ `ApplySharedPref`, ۱ `DataExtractionRules`, ۱ `MissingApplicationIcon`). `verifySafetyIntegrity` در `localVerify` سبز ماند. اجرای سرد نخست `localVerify` با metaspace=۳۸۴m به GC thrashing خورد؛ اجرای بعدی با `-Xmx256m/-XX:MaxMetaspaceSize=512m` موفق شد. تنظیم Gradle دائمی/repository ایجاد نشد و init script موقت حذف شد.
- تلاش اول `localVerify` پس از افزودن تست‌ها نیز به Konsist boundary test خورد، چون تست مستقیماً `safety.internal` import می‌کرد. آن دسترسی از تست حذف و invariant با API عمومی `AgentContainer` آزموده شد؛ اجرای نهایی `localVerify` سبز است. هیچ فایل Safety یا `verifySafetyIntegrity` دست‌کاری نشد.

**محدودیت‌های اثبات:** Robolectric reset integration با SDK 28 و Keystore/test providers در JVM است؛ `TestOnlyKeystoreKeyProvider` و HMAC test provider اثبات Android Keystore سخت‌افزاری/غیرقابل‌استخراج نیستند. reopen کردن store با instance تازه در همان Robolectric process، process restart واقعی Android نیست. WorkManager testing و Room test DB نیز رفتار scheduler/SQLite روی دستگاه واقعی، قطع برق، key eviction یا race چند process را ثابت نمی‌کنند. هیچ تست دستگاهی، restart واقعی، Keystore واقعی یا benchmark سخت‌افزاری اجرا نشده است.

> **مرزبندی زمانی:** فهرست زیر وضعیت پایان Phase F را ثبت می‌کند و درباره‌ی App Lock/Recovery با Phase G superseded شده است؛ برای وضعیت جاری به بخش «Phase G — App Lock…» در انتهای سند رجوع کنید. انتخاب حفظ benchmark state و caveatهای دیگر همچنان معتبرند.

**App Lock، تصمیم حفظ benchmark state، recovery و entry point — وضعیت هنگام پایان Phase F:**
- `AppLockManager`، hash مربوط به PIN و unlocked state را فقط در حافظه‌ی instance نگه می‌دارد؛ persistence پس از process restart وجود ندارد/اثبات نشده است. طبق سند پروژه Panic Reset باید پشت App Lock باشد، اما مسیر فعلی `AgentContainer.executePanicReset(reason)` هنوز احراز هویت App Lock را به‌عنوان پیش‌شرط enforce نمی‌کند؛ gate احراز هویت و تأیید صریح در کد پیاده‌سازی نشده‌اند.
- **تصمیم صریح کاربر: benchmark option 1 انتخاب شد.** profile در DataStore (`hardware_delegate_profiles`)، diagnostics در SharedPreferences و GPU safety latch پس از Panic Reset حفظ می‌شوند؛ این‌ها داده‌ی کاربر نیستند و پاک‌کردن GPU latch ممکن است GPU ناامن را دوباره فعال کند. فقط benchmark Work و starvation monitor لغو می‌شوند؛ storeهای profile/diagnostics/GPU latch پاک یا reset نمی‌شوند.
- **Recovery/release پذیرفته‌شده اما پیاده‌سازی‌نشده:** آزادسازی lockdown فقط پس از App Lock authentication و تأیید صریح کاربر مجاز است؛ پس از Reset، کاربر باید API keyها را دوباره وارد کند. Recovery آینده باید state موجود در SharedPreferences قفل پایدار (`agent_panic_reset_lockdown`) و integrity-key alias متناظر Android Keystore را هماهنگ پاک/بازسازی کند؛ پاک‌کردن فقط یکی از این دو، به‌دلیل نبودن state/key متناظر، integrity failure و fail-closed lockdown می‌دهد. `AndroidPanicResetLockdownStore` فعلاً فقط `isLocked()` و `latch()` دارد و هیچ recovery/unlock API یا recovery implementationی در برنامه وجود ندارد.
- entry point callable در production همان wrapper `AgentContainer.executePanicReset(reason)` است که به `RealSecurityLockdownEnforcer.executePanicReset(reason)` واگذار می‌کند؛ هیچ UI یا eventی آن را فراخوانی نمی‌کند و trigger کاربر/سیستمی هنوز ساخته نشده است.
- **بدهی‌های صریح:** lockdown release پیاده‌سازی نشده؛ App Lock/PIN فقط instance-memory است و gate مورد انتظار پیش از Reset وجود ندارد؛ `riskLevelOverrides` ذخیره و reset می‌شود اما به engine یا `SafetyGuard` وصل نیست؛ WorkManager history و متن/payload jobهای لغوشده پاک نمی‌شوند؛ تست‌ها JVM/Robolectric هستند و Android Keystore واقعی یا process restart واقعی را ثابت نمی‌کنند.

**وضعیت پایان Phase F (تاریخی):** در آن snapshot GEN-SEC-3/579/580 و release UI باز بودند. Phase G بعداً برای lock UI، key entry و release orchestration پیاده‌سازی JVM افزود، اما هیچ‌یک از شناسه‌های GEN-SEC-2, GEN-SEC-3, GEN-579/580 یا GEN-EDGE-1 «کامل» اعلام نمی‌شوند. وضعیت Git جاری در سرصفحه است؛ جمله‌های قدیمی درباره‌ی branch/commit در گزارش‌های تاریخی به snapshot جاری قابل تعمیم نیستند.

---

### ح) Phase G — App Lock، Credential Vault و Recovery UX (JVM-tested؛ device verification باز)

**دامنه و ادعا:** Phase G لایه‌ی view/XML و orchestration امنیتی را تکمیل می‌کند، نه runtime استنتاج. `GEN-SEC-2`, `GEN-SEC-3`, `GEN-579/580` و `GEN-EDGE-1` همچنان باز و غیرکامل‌اند: کد/آزمون JVM پاس شده، ولی device verification باز است. تغییرهای UI Panic Reset فقط ورودی/تأیید/نمایش نتیجه را می‌افزایند و به منطق reset موجود دست نمی‌زنند. به `SecretManager` داخلی دست‌کاری نشده، runtime/dependency inference یا `INTERNET` permission اضافه نشده و dependency versionها تغییر نکرده‌اند.

- PIN برنامه مستقل و اجباری است در first-run؛ hashing کند salted و HMAC Keystore برای tamper detection استفاده می‌شود، backoff بعد از شکست persistent است و state نامعتبر به حالت fail-closed می‌رود. تلاش PIN اشتباه داده را خودکار پاک نمی‌کند. Biometric opt-in است؛ خروج/بازگشت و timeout نیز با lock state کنترل می‌شوند.
- Memory و اجرای command/Skill در production `AgentContainer` پشت App Lock gate شده‌اند. آزمون از container واقعی، حفظ Memory هنگام PIN failure/restart recreation، ورود/خروج و backoff پایدار، gate مهارت/حافظه و پاک‌سازی `CharArray` را بررسی می‌کند.
- Credential UI فقط در Settingsِ محافظت‌شده است؛ شناسه‌های provider allowlist هستند، ورود از `AgentContainer` به `SecretManager.storeSecret()` می‌رود، مقدارها mask می‌مانند و مسیر حذف تکی موجود است. `ProviderCredentialCatalog`/descriptor contract با آزمون جدید `providerCredentialIdsMatchDescriptorNamesConsumedByProviderPool` بررسی شد. هنوز provider adapter/descriptor registration production و شبکه‌ی واقعی wiring نشده‌اند.
- Panic Reset entry point فقط در Settings پشت App Lock و confirmation دقیق قرار دارد؛ UI stageهای ناموفق را نشان می‌دهد و PIN را نگه می‌دارد. Lockdown release جداست: App Lock + phrase، پاک‌کردن provider keys، audit timestamp/epoch و finalization دو مرحله‌ای. Integrity failure مسیر reset security state جداگانه و rotation HMAC alias می‌گیرد.
- Android UI/View XML منابع انگلیسی و فارسی و RTL دارد؛ `FLAG_SECURE`, application icon, backup/data-extraction policy صریح‌اند؛ manifest همچنان بدون `INTERNET` است.
- دو `ApplySharedPref` در `GpuRuntimeSafetyStore.persistWarnings()` با suppression محدود و توضیح‌دار رفع شدند؛ `commit()` عمدی حفظ شد چون caller باید Boolean همگام برای fail-closed داشته باشد و `apply()` چنین نتیجه‌ای نمی‌دهد.

**تست‌های Phase G و verification:**

- Baseline اعلام‌شده‌ی پیش از Phase G: app=۱۴۰, safety=۱۷. در این فاز ۸ app tests در `AgentContainerAppLockIntegrationTest` (۷ قبلی + آزمون تازه‌ی descriptor-ID coordination) افزوده/گنجانده شدند؛ هیچ تستی حذف یا disabled نشد و full-suite JUnit XMLها `skipped=0` دارند.
- نام ۸ آزمون: `pinHashAndPersistentBackoffSurviveProductionContainerRecreationWithoutDeletingMemory`, `productionContainerGatesMemorySkillsAndProviderKeysAndNeverReturnsCredentialValues`, `providerCredentialIdsMatchDescriptorNamesConsumedByProviderPool`, `panicResetRequiresExactConfirmationPreservesPinAndLeavesPersistentLockdown`, `lockdownReleaseRequiresAppLockAndExactConfirmationThenClearsProviderKeysAndAuditsSafely`, `failedHalfwayReleaseLeavesDurablePendingMarkerAndLockdownActive`, `finalizeFailureBestEffortPersistsReleasePendingMarker`, `corruptedLockdownIntegrityRequiresExplicitSecurityStateResetAndRotatesAlias`.
- نتیجه‌ی full suite: `:app:testDebugUnitTest` = ۱۴۸ (baseline +۸), `:safety:test` = ۱۷ (+۰), total=۱۶۵; failures=0, errors=0, skipped=0. Production-container test class خودش ۸/۸ پاس شد.
- `localVerify`: `BUILD SUCCESSFUL`, ۴۴ actionable tasks (۱۲ executed / ۳۲ up-to-date); شامل lint, app/safety unit tests, `verifySafetyIntegrity`, `verifySkillRegistry`. `:app:verifySafetyIntegrity --rerun-tasks` نیز جداگانه موفق شد (۱ task executed). `:app:preDebugAndroidTestBuild SKIPPED` یک task چرخه‌ی AndroidTest است، نه JUnit test؛ instrumented/device test اجرا نشده است.
- Lint مستقل `:app:lintDebug --rerun-tasks`: `BUILD SUCCESSFUL`, ۳۳/۳۳ task executed، ۰ error و ۱۱ warning: ۸ `GradleDependency` (نسخه‌ها طبق scope دست‌نخورده)، ۱ `Overdraw` (`activity_main.xml`)، ۲ `UnusedResources` (`agent_success`, `security_title`). `SetTextI18n`, `MissingApplicationIcon`, `DataExtractionRules`, `ApplySharedPref` در گزارش نهایی نبودند.
- یک تلاش targeted پیش از کاهش heap/metaspace به‌علت `global_oom` در daemon خاتمه یافت و XML تازه‌ای ننوشت؛ تکرار با heap/memory محدودتر موفق شد و سپس full app/safety/localVerify سبز شد. این یک خطای محیط ساخت بود، نه شکست JUnit؛ SDK XML parser نیز warning version 4 vs 3 داد.
- Kotlin compile warnings غیرمسدودکننده هنوز دیده شدند: `MultiAgentOrchestrator.originalPrompt` و چند assignment متغیر `stage` در `LockdownReleaseCoordinator`; lint/build موفق‌اند، اما این هشدارهای compiler از lint warning count جدا هستند.

**محدودیت‌ها و پیگیری Phase G:** تمام شواهد آن فاز JVM/Robolectric هستند (از جمله state-store recreation)، نه Android Keystore سخت‌افزاری، process death واقعی یا اجرای دستگاه؛ رفتار biometrics، RTL روی دستگاه، `FLAG_SECURE`، lifecycle/background lock، دیتابیس واقعی و recovery interruption باید روی دستگاه هدف بررسی شوند. شناسه‌های امنیتی فوق «پیاده‌سازی و آزموده‌شده در JVM؛ تأیید روی دستگاه باز» می‌مانند. یادداشت design-only درباره‌ی Phase H که در نسخه‌ی ۱۳ اکتبر ثبت شده بود، با پیاده‌سازی محدود و تأییدشده‌ی ۱۶ اکتبر در بخش انتهایی superseded شده است.

---

### Snapshot تاریخی طراحی Phase H (۱۳ اکتبر ۲۰۲۶؛ superseded later)

- نسخه‌ی قبلی این گزارش (ممیزی ۱۱ اکتبر) نوشته بود `MASTER-ROADMAP-v4-FINAL.md` در workspace در دسترس نیست. فایل اکنون در `/home/user/uploads/MASTER-ROADMAP-v4-FINAL.md` موجود است، اما بیرون از repository/Git است؛ این گزارش و `docs/PHASE_H_INFERENCE_DESIGN.md` متن دقیق Phase VIII و GEN-310 را به همین فایل/شماره‌خط ارجاع می‌دهند. GEN-310 در roadmap فقط retry خطای گذرا و اتصال خطای منابع به GEN-NEW-3 را می‌گوید؛ تعداد retry/backoff مشخص نشده است. GPU→CPU fallback جداگانه در GEN-EDGE-1 آمده است.
- **فقط در snapshot مورخ ۱۳ اکتبر** طراحی Phase H هنوز design-only بود: Qwen3-4B GGUF Q4_K_M، محاسبه‌ی تقریبی وزن+KV، مقایسه‌ی MediaPipe/LiteRT-LM/upstream llama.cpp و binding آماده، محدودیت‌ها، download/resume/hash/consent، Wi‑Fi-only پیش‌فرض، محل build، APK-size evidence، و نقشه‌ی ز→ح→ط→ی. این وضعیت در ۱۶ اکتبر با approval مجزای کاربر superseded شد؛ وضعیت اجرایی فعلی در بخش بعد ثبت شده است. مرزهای Panic Reset/permissions حفظ شدند.
- binding آماده‌ی مشاهده‌شده `net.ladenthin:llama-android:5.1.0` است؛ AAR از Maven Central دریافت و zip-inspect شد: `19,839,083` bytes، SHA-256=`8bae2f2e9eaeb3c8a971813f8e819326c1a7ded9308ca66f1db6dd3e0ca5ab72`. این فقط اندازه‌ی AAR است، نه APK. `llama-kotlin:5.1.0` در README به Kotlin 2.3+ نیاز دارد، درحالی‌که پروژه Kotlin 1.9.22 دارد؛ ارتقای نسخه/افزودن dependency انجام نشده و approval جدا لازم است.
- **در snapshot مورخ ۱۳ اکتبر:** آن محیط NDK/CMake نداشت و `app/build/outputs` فاقد APK/AAB بود؛ بنابراین اندازه‌ی نهایی APK و native-runtime/device behavior اثبات‌نشده بودند. CI نسخه‌دار، گوشی واقعی برای GEN-PERF-1 و Termux صرفاً محیط اختیاری آزمایش پیشنهاد شده بودند؛ workflow هنوز وجود نداشت. وضعیت ۱۶ اکتبر در بخش Phase H runtime زیر به‌روزرسانی شده است.

### Phase H runtime implementation و وضعیت validation (۱۶ اکتبر ۲۰۲۶)

- **Scope/guardrails:** پیاده‌سازی مطابق approval کاربر و `docs/PHASE_H_INFERENCE_DESIGN.md` انجام شد. Kotlin همچنان `1.9.22` است؛ binding شخص ثالث، GPU، MediaPipe، in-app download یا permission تازه اضافه نشد. `SafetyGuard`, `SafetyInternalStore`, API عمومی `SecretManager` و Panic Reset دست‌نخورده‌اند.
- **Source/toolchain pins:** `native/llama.cpp.lock` قفل می‌کند llama.cpp b11669 را به commit کامل `620d2225cc436ab0b001e3200e590e3b286ef9ab`، NDK `29.0.14206865`, CMake `3.28.3`, Android `android-28`, ABI `arm64-v8a`. `native/CMakeLists.txt` upstream llama.cpp را با backend CPU، بدون OpenMP و با backendهای غیر CPU خاموش می‌سازد؛ JNI bridge مالک پروژه است.
- **Build/CI:** `native/build-android-cpu.sh` نسخه‌های قفل‌شده و tag→commit را بررسی می‌کند و عمداً خارج از GitHub Actions متوقف می‌شود. `.github/workflows/phase-h-android-cpu.yml` native `.so`، `libc++_shared.so` و debug APK را برای artifact upload می‌سازد و verification JVM را اجرا می‌کند؛ `native/verify-jvm-test-counts.py` حداقل app=148، safety=17 دقیق و skipped=0 را enforce می‌کند. **Workflow اجرا نشده است:** Git remote تنظیم نشده و GitHub connector در این محیط unsupported بود؛ پس هیچ Actions run، `.so`، APK یا artifact upload واقعی وجود ندارد. این workspace نیز native build یا APK packaging انجام نداده است.
- **Model/SAF:** `Qwen3ModelAsset` نام `Qwen3-4B-Q4_K_M.gguf`، revision `bc640142c66e1fdd12af0bd68f40445458f3869b` و SHA-256 `7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5` را ثابت می‌کند. Settings یک SAF document picker پشت App Lock دارد؛ `ModelAssetStore` به صورت streaming هش می‌کند، فقط hash دقیق را در app-private `noBackupFilesDir` نصب اتمیک می‌کند و تا marker تأییدشده وجود نداشته باشد model را usable نمی‌داند. digest از `ChecksumUtil` مشترک با `ModelDownloadProtocol` است؛ download path آن استفاده نشده. Manifest و merged manifests هر دو بدون `INTERNET` هستند؛ permissionهای موجود تغییر نکردند.
- **Runtime wiring:** `LlamaCppInferenceBackend` در `LocalInferenceEngine` و `AgentContainer` سیم‌کشی شده و تنها CPU را می‌پذیرد؛ پشتیبانی streaming/seed/cancellation صادقانه `false` است و GBNF فقط برای frozen cognitive-call schema عرضه می‌شود. نبود مدل یا کتابخانه fail-closed به خطای محلی می‌انجامد. JVM آزمون‌ها bridge جعلی تزریق می‌کنند؛ بنابراین compile/JVM result به معنی اجرای JNI/llama.cpp یا inference واقعی نیست.
- **Pinned API audit:** در source commit قفل‌شده بررسی شد که `llama_batch_get_one` فقط token و count می‌گذارد و `llama_batch_compat` فیلدهای null مربوط به sequence/position/logits را با sequence پیش‌فرض، position خودکار و output token آخر کامل می‌کند. `contextTokens` نیز در `NativeSession` نگه‌داری می‌شود. این بررسی source-level است، نه C++ compile test.
- **هشت تست JVM جدید:** در `ModelAssetStoreTest`: `streamsAndInstallsOnlyTheHashVerifiedFile`, `hashMismatchLeavesNoInstalledModelOrVerificationMarker`, `alreadyVerifiedModelIsNotReplacedByAnotherImportAttempt`; در `LlamaCppInferenceBackendTest`: `missingVerifiedAssetReturnsUnavailableWithoutLoadingNativeLibrary`, `verifiedAssetUsesInjectedCpuBridgeAndReportsReportedTokenUsage`, `frozenCognitiveSchemaIsMappedToTheExistingGbnfGrammar`, `arbitrarySchemaIsRejectedBeforeCallingTheNativeBridge`, `nonStreamingCapabilityIsReportedHonestly`. تست‌های backend فقط `FakeBridge` را اجرا می‌کنند.
- **JVM verification:** آخرین `localVerify` موفق است (۴۴ actionable tasks: ۷ executed / ۳۷ up-to-date). XML: `:app:testDebugUnitTest`=۱۵۶، `:safety:test`=۱۷، مجموع=۱۷۳، failure=0/error=0/skipped=0؛ test-count script موفق است. `:app:verifySafetyIntegrity --rerun-tasks` جداگانه ۱/۱ task موفق شد. Lint مستقل `:app:lintDebug --rerun-tasks`=۳۳/۳۳ task موفق، ۰ error و ۱۲ warning (۸ `GradleDependency`, ۱ `ChromeOsAbiSupport`, ۱ `Overdraw`, ۲ `UnusedResources`). `:app:preDebugAndroidTestBuild SKIPPED` task چرخه‌ی instrumented AndroidTest است؛ skipped در JUnit صفر است.
- **باقی‌مانده‌ی صریح:** CMake/JNI هنوز compile نشده، GitHub Actions اجرا نشده، GGUF واقعی از SAF وارد نشده و هیچ گوشی/Android runtime آزموده نشده است. شواهد فعلی فقط Kotlin/JVM + XML/resource/lint verification هستند؛ device/native validation همچنان باز است. یک اجرای مجدد full verification پس از ویرایش متن resourceهای UI و تغییر نام یکی از test methodها با OOM/daemon disappearance خاتمه یافت؛ اجرای بعدی `localVerify` با محدودیت حافظه‌ی تنظیم‌شده موفق شد و XML نهایی همان ۱۵۶/۱۷، صفر failure/error/skipped را دارد.

*پایان مستند وضعیت پروژه و راه‌اندازی.*
